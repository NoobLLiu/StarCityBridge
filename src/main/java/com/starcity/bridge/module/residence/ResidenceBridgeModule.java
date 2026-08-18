package com.starcity.bridge.module.residence;

import com.artformgames.plugin.residencelist.ResidenceListAPI;
import com.artformgames.plugin.residencelist.api.residence.ResidenceData;
import com.artformgames.plugin.residencelist.api.residence.ResidenceRate;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 领地(Residence + ResidenceList)对接模块。
 *
 * <p>ResidenceList 自身没有并发保护（YAML + HashMap 直接读写），所以本模块自己兜底：
 * 所有写操作先按「领地名/负责人」拿可重入锁，再强制回服务器主线程串行执行
 * （{@link Bukkit#getScheduler() callSyncMethod}），避免网页请求与游戏内 GUI 操作交错。
 *
 * <p>容错说明：本系统只面向已注册玩家，但为防止从未上线/未注册的玩家入参导致插件崩溃，
 * 所有玩家相关动作都会先校验并 try/catch，无法解析时返回明确中文提示而不是抛异常。</p>
 */
public class ResidenceBridgeModule implements BridgeModule {

    private final StarCityBridge plugin;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ResidenceBridgeModule(StarCityBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "residencelist";
    }

    @Override
    public JsonObject handleRequest(String action, JsonObject payload) {
        if (!residenceReady()) {
            return result(false, "Residence 插件未加载或未启用", null);
        }
        try {
            return switch (action) {
                // 只读
                case "list" -> read(this::list);
                case "detail" -> read(() -> detail(payload));
                case "flags" -> read(() -> flags(payload));
                case "player_flags" -> read(() -> playerFlags(payload));
                // 写：按领地锁 + 主线程串行
                case "set_nickname" -> write(residenceName(payload), () -> setNickname(payload));
                case "set_description" -> write(residenceName(payload), () -> setDescription(payload));
                case "set_icon" -> write(residenceName(payload), () -> setIcon(payload));
                case "set_public" -> write(residenceName(payload), () -> setPublic(payload));
                case "set_rate" -> write(residenceName(payload), () -> setRate(payload));
                case "remove_rate" -> write(residenceName(payload), () -> removeRate(payload));
                case "set_pin" -> write(residenceName(payload), () -> setPin(payload));
                // 写：Residence 权限
                case "set_flag" -> write(residenceName(payload), () -> setFlag(payload));
                case "set_player_flag" -> write(residenceName(payload), () -> setPlayerFlag(payload));
                case "clear_player_flags" -> write(residenceName(payload), () -> clearPlayerFlags(payload));
                default -> null;
            };
        } catch (Exception e) {
            plugin.getLogger().warning("[residencelist] 执行失败: " + action + " -> " + e.getMessage());
            return result(false, "操作失败，请稍后再试（" + safeMessage(e) + "）", null);
        }
    }

    private String safeMessage(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // ===================== 只读动作 =====================

    private JsonObject list() {
        Map<String, ClaimedResidence> all = Residence.getInstance().getResidenceManager().getResidences();
        List<ClaimedResidence> sorted = new ArrayList<>(all.values());
        sorted.sort(Comparator.comparing(ClaimedResidence::getName));

        JsonArray arr = new JsonArray();
        for (ClaimedResidence r : sorted) {
            JsonObject o = new JsonObject();
            o.addProperty("name", r.getName());
            o.addProperty("owner", r.getOwner());
            o.addProperty("owner_uuid", r.getOwnerUUID().toString());
            if (residenceListReady()) {
                ResidenceData rd = ResidenceListAPI.getResidenceManager().getResidence(r.getName());
                if (rd != null) {
                    o.addProperty("alias", rd.getAliasName());
                    o.addProperty("public", rd.isPublicDisplayed());
                    o.addProperty("rate_count", rd.getRates().size());
                }
            }
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.addProperty("available", true);
        data.add("residences", arr);
        return result(true, "", data);
    }

    private JsonObject detail(JsonObject payload) {
        String name = str(payload, "residence");
        ClaimedResidence r = residence(name);
        if (r == null) {
            return result(false, "领地不存在: " + name, null);
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", r.getName());
        data.addProperty("owner", r.getOwner());
        data.addProperty("owner_uuid", r.getOwnerUUID().toString());
        ResidencePermissions perms = r.getPermissions();
        data.addProperty("world", perms.getWorldName());
        data.addProperty("trusted_count", perms.getPlayerFlags().size());
        data.add("flags", toJsonMap(perms.getFlags()));

        if (residenceListReady()) {
            ResidenceData rd = ResidenceListAPI.getResidenceManager().getResidence(name);
            if (rd != null) {
                data.addProperty("alias", rd.getAliasName());
                JsonArray desc = new JsonArray();
                for (String line : rd.getDescription()) {
                    desc.add(line);
                }
                data.add("description", desc);
                data.addProperty("public", rd.isPublicDisplayed());
                if (rd.getIconMaterial() != null) {
                    data.addProperty("icon", rd.getIconMaterial().name());
                    data.addProperty("custom_model_data", rd.getCustomModelData());
                }
                int recommend = 0;
                for (ResidenceRate rate : rd.getRates().values()) {
                    if (rate.recommend()) {
                        recommend++;
                    }
                }
                data.addProperty("rate_count", rd.getRates().size());
                data.addProperty("rate_recommend", recommend);
            }
        }
        return result(true, "", data);
    }

    private JsonObject flags(JsonObject payload) {
        String name = str(payload, "residence");
        ClaimedResidence r = residence(name);
        if (r == null) {
            return result(false, "领地不存在: " + name, null);
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        data.add("flags", toJsonMap(r.getPermissions().getFlags()));
        List<String> possible = new ArrayList<>(r.getPermissions().getposibleFlags());
        possible.sort(String::compareToIgnoreCase);
        JsonArray arr = new JsonArray();
        for (String f : possible) {
            arr.add(f);
        }
        data.add("possible_flags", arr);
        return result(true, "", data);
    }

    private JsonObject playerFlags(JsonObject payload) {
        String name = str(payload, "residence");
        String target = playerString(payload);
        if (target.isEmpty()) {
            return result(false, "缺少玩家名/UUID：请通过 player 传入玩家名或 UUID", null);
        }
        ClaimedResidence r = residence(name);
        if (r == null) {
            return result(false, "领地不存在: " + name, null);
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        data.addProperty("player", target);
        data.add("flags", toJsonMap(r.getPermissions().getPlayerFlags(target)));
        return result(true, "", data);
    }

    // ===================== ResidenceList 写动作 =====================

    private JsonObject setNickname(JsonObject payload) {
        if (!residenceListReady()) {
            return result(false, "ResidenceList 插件未加载", null);
        }
        ResidenceData rd = residenceData(str(payload, "residence"));
        if (rd == null) {
            return result(false, "领地数据未初始化", null);
        }
        rd.modify(d -> d.setNickname(str(payload, "nickname")));
        return result(true, "", null);
    }

    private JsonObject setDescription(JsonObject payload) {
        if (!residenceListReady()) {
            return result(false, "ResidenceList 插件未加载", null);
        }
        ResidenceData rd = residenceData(str(payload, "residence"));
        if (rd == null) {
            return result(false, "领地数据未初始化", null);
        }
        List<String> lines = new ArrayList<>();
        if (payload.has("description") && payload.get("description").isJsonArray()) {
            for (var ele : payload.getAsJsonArray("description")) {
                lines.add(ele.getAsString());
            }
        }
        rd.modify(d -> d.setDescription(lines));
        return result(true, "", null);
    }

    private JsonObject setIcon(JsonObject payload) {
        if (!residenceListReady()) {
            return result(false, "ResidenceList 插件未加载", null);
        }
        Material material = Material.matchMaterial(str(payload, "material"));
        if (material == null) {
            return result(false, "无效的图标材质: " + str(payload, "material"), null);
        }
        int cmd = intField(payload, "custom_model_data", -1);
        ResidenceData rd = residenceData(str(payload, "residence"));
        if (rd == null) {
            return result(false, "领地数据未初始化", null);
        }
        rd.modify(d -> d.setIconMaterial(material, cmd));
        return result(true, "", null);
    }

    private JsonObject setPublic(JsonObject payload) {
        if (!residenceListReady()) {
            return result(false, "ResidenceList 插件未加载", null);
        }
        ResidenceData rd = residenceData(str(payload, "residence"));
        if (rd == null) {
            return result(false, "领地数据未初始化", null);
        }
        rd.modify(d -> d.setPublicDisplayed(boolField(payload, "public", false)));
        return result(true, "", null);
    }

    private JsonObject setRate(JsonObject payload) {
        if (!residenceListReady()) {
            return result(false, "ResidenceList 插件未加载", null);
        }
        UUID author = uuidField(payload, "player");
        if (author == null) {
            return result(false, "无效的玩家 UUID：请传入 36 位 UUID 字符串", null);
        }
        ResidenceData rd = residenceData(str(payload, "residence"));
        if (rd == null) {
            return result(false, "领地数据未初始化", null);
        }
        boolean recommend = boolField(payload, "recommend", false);
        String content = str(payload, "content");
        rd.modify(d -> d.setRate(author, new ResidenceRate(author, content, recommend, LocalDateTime.now())));
        return result(true, "", null);
    }

    private JsonObject removeRate(JsonObject payload) {
        if (!residenceListReady()) {
            return result(false, "ResidenceList 插件未加载", null);
        }
        UUID author = uuidField(payload, "player");
        if (author == null) {
            return result(false, "无效的玩家 UUID：请传入 36 位 UUID 字符串", null);
        }
        ResidenceData rd = residenceData(str(payload, "residence"));
        if (rd == null) {
            return result(false, "领地数据未初始化", null);
        }
        rd.modify(d -> d.removeRate(author));
        return result(true, "", null);
    }

    private JsonObject setPin(JsonObject payload) {
        if (!residenceListReady()) {
            return result(false, "ResidenceList 插件未加载", null);
        }
        UUID player = uuidField(payload, "player");
        if (player == null) {
            return result(false, "无效的玩家 UUID：请传入 36 位 UUID 字符串", null);
        }
        String residence = str(payload, "residence");
        int index = intField(payload, "index", 0);
        ResidenceListAPI.getUserManager().modify(player, d -> d.setPin(residence, index));
        return result(true, "", null);
    }

    // ===================== Residence 权限写动作 =====================

    private JsonObject setFlag(JsonObject payload) {
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        String flag = str(payload, "flag");
        String state = str(payload, "state").toLowerCase(Locale.ROOT);
        if (flag.isEmpty() || state.isEmpty()) {
            return result(false, "缺少 flag 或 state", null);
        }
        if (r.getPermissions().setFlag(Bukkit.getConsoleSender(), flag, state, true)) {
            return result(true, "", null);
        }
        return result(false, "设置 flag 失败（请检查 flag 名与 state: true/false/remove）", null);
    }

    private JsonObject setPlayerFlag(JsonObject payload) {
        String target = playerString(payload);
        if (target.isEmpty()) {
            return result(false, "缺少玩家名/UUID：请通过 player 传入玩家名或 UUID", null);
        }
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        String flag = str(payload, "flag");
        String state = str(payload, "state").toLowerCase(Locale.ROOT);
        if (flag.isEmpty() || state.isEmpty()) {
            return result(false, "缺少 flag/state", null);
        }
        boolean ok = r.getPermissions().setPlayerFlag(Bukkit.getConsoleSender(), target, flag, state, true, false);
        return ok ? result(true, "", null)
                : result(false, "设置玩家 flag 失败（玩家未注册或从未上线，需至少进服一次；请检查玩家名/UUID）", null);
    }

    private JsonObject clearPlayerFlags(JsonObject payload) {
        String target = playerString(payload);
        if (target.isEmpty()) {
            return result(false, "缺少玩家名/UUID：请通过 player 传入玩家名或 UUID", null);
        }
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        boolean ok = r.getPermissions().removeAllPlayerFlags(Bukkit.getConsoleSender(), target, true);
        return ok ? result(true, "", null)
                : result(false, "清空玩家 flag 失败（玩家未注册或从未上线，需至少进服一次；请检查玩家名/UUID）", null);
    }

    // ===================== 并发与调度 =====================

    private JsonObject read(Callable<JsonObject> task) {
        return onMain(task);
    }

    private JsonObject write(String key, Callable<JsonObject> task) {
        if (key == null || key.isEmpty()) {
            key = "global";
        }
        key = key.toLowerCase(Locale.ROOT);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(false, "操作被中断，请重试", null);
        }
        if (!acquired) {
            return result(false, "该领地当前正被其他操作占用，请稍后再试", null);
        }
        try {
            return onMain(task);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(key, lock);
            }
        }
    }

    private JsonObject onMain(Callable<JsonObject> task) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                plugin.getLogger().warning("[residencelist] 主线程执行异常: " + safeMessage(e));
                return result(false, "操作失败，请稍后再试（" + safeMessage(e) + "）", null);
            }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("[residencelist] 主线程调度异常: " + safeMessage(e));
            return result(false, "服务器繁忙，请稍后再试", null);
        }
    }

    // ===================== 工具 =====================

    private String residenceName(JsonObject payload) {
        return str(payload, "residence");
    }

    private boolean residenceReady() {
        return Bukkit.getPluginManager().getPlugin("Residence") != null;
    }

    private boolean residenceListReady() {
        return residenceReady() && Bukkit.getPluginManager().getPlugin("ResidenceList") != null;
    }

    private ClaimedResidence residence(String name) {
        return Residence.getInstance().getResidenceManager().getByName(name);
    }

    private ResidenceData residenceData(String name) {
        return ResidenceListAPI.getResidenceManager().getResidence(name);
    }

    /** 玩家入参：支持 UUID 字符串或玩家名，交由 Residence 自身解析；无法解析时相关操作会返回失败提示。 */
    private String playerString(JsonObject payload) {
        return str(payload, "player").trim();
    }

    private String str(JsonObject payload, String key) {
        return str(payload, key, "");
    }

    private String str(JsonObject payload, String key, String def) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsString() : def;
    }

    private boolean boolField(JsonObject payload, String key, boolean def) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsBoolean() : def;
    }

    private int intField(JsonObject payload, String key, int def) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsInt() : def;
    }

    private UUID uuidField(JsonObject payload, String key) {
        String s = str(payload, key);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject toJsonMap(Map<String, Boolean> map) {
        JsonObject out = new JsonObject();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            out.addProperty(e.getKey(), e.getValue());
        }
        return out;
    }

    private JsonObject result(boolean ok, String message, JsonObject data) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("message", message == null ? "" : message);
        out.add("data", data == null ? new JsonObject() : data);
        return out;
    }
}