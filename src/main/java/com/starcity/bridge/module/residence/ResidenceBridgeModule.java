package com.starcity.bridge.module.residence;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.economy.rent.RentableLand;
import com.bekvon.bukkit.residence.economy.rent.RentedLand;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;
import org.bukkit.Bukkit;

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
 * 领地插件（Zrips/Residence）本体对接模块。
 *
 * <p>只调用 Residence 已有的公开接口，不修改 Residence 本体代码。
 * 覆盖领地信息查询与权限编辑（领地 flag / 玩家 flag / 重置 / 转让 / 进出提示语）。</p>
 *
 * <p>并发保护：Residence 内部数据并非并发安全，因此所有读写都强制回到服务器主线程串行执行；
 * 写操作额外按「领地全路径」加可重入锁，避免网页请求与游戏内操作交错导致数据异常。</p>
 *
 * <p>容错：面向已注册玩家，但所有玩家入参（玩家名/UUID）都会先解析并 try/catch，
 * 从未上线或无法解析时返回明确中文提示，绝不抛出异常。</p>
 */
public class ResidenceBridgeModule implements BridgeModule {

    private final StarCityBridge plugin;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ResidenceBridgeModule(StarCityBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "residence";
    }

    @Override
    public JsonObject handleRequest(String action, JsonObject payload) {
        if (!residenceReady()) {
            return result(false, "Residence 插件未加载或未启用", null);
        }
        try {
            return switch (action) {
                // 只读（主线程串行）
                case "list" -> read(() -> list(payload));
                case "detail" -> read(() -> detail(payload));
                case "flags" -> read(() -> flags(payload));
                case "player_flags" -> read(() -> playerFlags(payload));
                // 写（领地锁 + 主线程串行）
                case "set_flag" -> write(residenceName(payload), () -> setFlag(payload));
                case "set_player_flag" -> write(residenceName(payload), () -> setPlayerFlag(payload));
                case "remove_player_flag" -> write(residenceName(payload), () -> removePlayerFlag(payload));
                case "clear_player_flags" -> write(residenceName(payload), () -> clearPlayerFlags(payload));
                case "apply_default_flags" -> write(residenceName(payload), () -> applyDefaultFlags(payload));
                case "set_owner" -> write(residenceName(payload), () -> setOwner(payload));
                case "set_message" -> write(residenceName(payload), () -> setMessage(payload));
                default -> null;
            };
        } catch (Exception e) {
            plugin.getLogger().warning("[residence] 执行失败: " + action + " -> " + safeMessage(e));
            return result(false, "操作失败，请稍后再试（" + safeMessage(e) + "）", null);
        }
    }

    // ===================== 只读动作 =====================

    private JsonObject list(JsonObject payload) {
        int page = Math.max(1, intField(payload, "page", 1));
        int pageSize = Math.max(1, Math.min(100, intField(payload, "page_size", 20)));
        String query = str(payload, "query").toLowerCase(Locale.ROOT);
        String ownerFilter = str(payload, "owner").toLowerCase(Locale.ROOT);
        boolean mine = boolField(payload, "mine", false);
        UUID caller = uuidField(payload, "player_uuid");

        ResidenceManager manager = Residence.getInstance().getResidenceManager();
        List<ClaimedResidence> all = new ArrayList<>(manager.getResidences().values());
        List<JsonObject> rows = new ArrayList<>();
        for (ClaimedResidence r : all) {
            if (mine && (caller == null || !r.isOwner(caller))) continue;
            if (!ownerFilter.isEmpty()
                    && !r.getOwner().toLowerCase(Locale.ROOT).contains(ownerFilter)) continue;
            if (!query.isEmpty()) {
                String name = r.getName().toLowerCase(Locale.ROOT);
                String owner = r.getOwner().toLowerCase(Locale.ROOT);
                if (!name.contains(query) && !owner.contains(query)) continue;
            }
            rows.add(summary(r));
        }
        rows.sort(Comparator.comparing(o -> o.get("name").getAsString(), String.CASE_INSENSITIVE_ORDER));

        int total = rows.size();
        int from = (page - 1) * pageSize;
        JsonArray arr = new JsonArray();
        if (from < total) {
            int to = Math.min(total, from + pageSize);
            for (int i = from; i < to; i++) arr.add(rows.get(i));
        }
        JsonObject data = new JsonObject();
        data.addProperty("total", total);
        data.addProperty("page", page);
        data.addProperty("page_size", pageSize);
        data.add("residences", arr);
        return result(true, "", data);
    }

    private JsonObject summary(ClaimedResidence r) {
        JsonObject o = new JsonObject();
        o.addProperty("name", r.getName());
        o.addProperty("owner", r.getOwner());
        UUID ownerUuid = r.getOwnerUUID();
        o.addProperty("owner_uuid", ownerUuid == null ? "" : ownerUuid.toString());
        o.addProperty("world", r.getWorldName());
        o.addProperty("areas", r.getAreaCount());
        o.addProperty("subzones", r.getSubzonesAmount(false));
        o.addProperty("main", r.isMainResidence());
        o.addProperty("for_sale", r.isForSell());
        if (r.isForSell()) o.addProperty("sell_price", r.getSellPrice());
        o.addProperty("for_rent", r.isForRent());
        o.addProperty("rented", r.isRented());
        o.addProperty("size", r.getTotalSize());
        long created = r.getCreateTime();
        if (created > 0) o.addProperty("created_at", created);
        return o;
    }

    private JsonObject detail(JsonObject payload) {
        String name = str(payload, "residence");
        ClaimedResidence r = residence(name);
        if (r == null) {
            return result(false, "领地不存在: " + name, null);
        }
        JsonObject data = summary(r);
        data.addProperty("subzone", r.isSubzone());
        if (r.isSubzone() && r.getParent() != null) {
            data.addProperty("parent", r.getParent().getName());
        }

        // 区域边界
        JsonArray areas = new JsonArray();
        for (Map.Entry<String, CuboidArea> e : r.getAreaMap().entrySet()) {
            JsonObject a = new JsonObject();
            a.addProperty("name", e.getKey());
            a.addProperty("world", e.getValue().getWorldName());
            a.add("low", xyz(e.getValue().getLowVector()));
            a.add("high", xyz(e.getValue().getHighVector()));
            a.addProperty("size", e.getValue().getSize());
            areas.add(a);
        }
        data.add("areas", areas);

        // 子领地
        JsonArray subzones = new JsonArray();
        for (ClaimedResidence sub : r.getSubzones()) {
            JsonObject s = new JsonObject();
            s.addProperty("name", sub.getName());
            s.addProperty("owner", sub.getOwner());
            UUID u = sub.getOwnerUUID();
            s.addProperty("owner_uuid", u == null ? "" : u.toString());
            subzones.add(s);
        }
        data.add("subzones_detail", subzones);

        // 权限总览
        ResidencePermissions perms = r.getPermissions();
        data.add("flags", toJsonMap(perms.getFlags()));
        data.add("player_flags", toJsonMap2(perms.getPlayerFlagsByName()));

        // 受信玩家
        JsonArray trusted = new JsonArray();
        for (ResidencePlayer p : r.getTrustedPlayers()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", p.getName());
            UUID u = p.getUniqueId();
            t.addProperty("uuid", u == null ? "" : u.toString());
            trusted.add(t);
        }
        data.add("trusted_players", trusted);

        // 进出提示语
        data.addProperty("enter_message", orEmpty(r.getEnterMessage()));
        data.addProperty("leave_message", orEmpty(r.getLeaveMessage()));

        // 租售信息
        if (r.isForRent()) {
            RentableLand rentable = r.getRentable();
            if (rentable != null) {
                JsonObject rent = new JsonObject();
                rent.addProperty("cost", rentable.cost);
                rent.addProperty("days", rentable.days);
                rent.addProperty("allow_renewing", rentable.AllowRenewing);
                data.add("rentable", rent);
            }
        }
        if (r.isRented()) {
            RentedLand rented = r.getRentedLand();
            if (rented != null) {
                JsonObject rj = new JsonObject();
                rj.addProperty("player", orEmpty(rented.getRenterName()));
                rj.addProperty("end_time", rented.endTime);
                rj.addProperty("auto_pay", rented.AutoPay);
                data.add("rented_detail", rj);
            }
        }

        // 领地银行
        try {
            Double bank = r.getBank().getStoredMoneyD();
            if (bank != null) data.addProperty("bank", bank);
        } catch (Exception ignored) {
        }
        return result(true, "", data);
    }

    private JsonObject flags(JsonObject payload) {
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        ResidencePermissions perms = r.getPermissions();
        JsonObject data = new JsonObject();
        data.addProperty("name", r.getName());
        data.add("flags", toJsonMap(perms.getFlags()));
        List<String> possible = new ArrayList<>(perms.getposibleFlags());
        possible.sort(String.CASE_INSENSITIVE_ORDER);
        JsonArray arr = new JsonArray();
        for (String f : possible) arr.add(f);
        data.add("possible_flags", arr);
        return result(true, "", data);
    }

    private JsonObject playerFlags(JsonObject payload) {
        String target = playerString(payload);
        if (target.isEmpty()) {
            return result(false, "缺少玩家名/UUID：请通过 player 传入玩家名或 UUID", null);
        }
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", r.getName());
        data.addProperty("player", target);
        data.add("flags", toJsonMap(r.getPermissions().getPlayerFlags(target)));
        return result(true, "", data);
    }

    // ===================== 写动作（权限编辑） =====================

    private JsonObject setFlag(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        String flag = str(payload, "flag");
        String state = str(payload, "state").toLowerCase(Locale.ROOT);
        if (flag.isEmpty() || state.isEmpty()) {
            return result(false, "缺少 flag 或 state（state 可为 true/false/remove）", null);
        }
        boolean ok = r.getPermissions().setFlag(Bukkit.getConsoleSender(), flag, state, true);
        return ok ? result(true, "", null)
                : result(false, "设置领地 flag 失败（请检查 flag 名称，可通过 flags 接口获取 possible_flags）", null);
    }

    private JsonObject setPlayerFlag(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        String flag = str(payload, "flag");
        String state = str(payload, "state").toLowerCase(Locale.ROOT);
        if (flag.isEmpty() || state.isEmpty()) {
            return result(false, "缺少 flag 或 state（state 可为 true/false/remove）", null);
        }
        return setPlayerFlagInternal(r, payload, flag, state);
    }

    private JsonObject removePlayerFlag(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        String flag = str(payload, "flag");
        if (flag.isEmpty()) {
            return result(false, "缺少 flag", null);
        }
        return setPlayerFlagInternal(r, payload, flag, "remove");
    }

    private JsonObject setPlayerFlagInternal(ClaimedResidence r, JsonObject payload, String flag, String state) {
        String target = playerString(payload);
        if (target.isEmpty()) {
            return result(false, "缺少玩家名/UUID：请通过 player 传入玩家名或 UUID", null);
        }
        UUID uuid = parseUuid(target);
        boolean ok;
        if (uuid != null) {
            ok = r.getPermissions().setPlayerFlag(Bukkit.getConsoleSender(), uuid, flag, state, true, false);
        } else {
            ok = r.getPermissions().setPlayerFlag(Bukkit.getConsoleSender(), target, flag, state, true, false);
        }
        return ok ? result(true, "", null)
                : result(false, "设置玩家 flag 失败（玩家未注册或从未上线，需至少进服一次；请检查玩家名/UUID 与 flag 名称）", null);
    }

    private JsonObject clearPlayerFlags(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        String target = playerString(payload);
        if (target.isEmpty()) {
            return result(false, "缺少玩家名/UUID：请通过 player 传入玩家名或 UUID", null);
        }
        UUID uuid = parseUuid(target);
        boolean ok = uuid != null
                ? r.getPermissions().removeAllPlayerFlags(Bukkit.getConsoleSender(), uuid, true)
                : r.getPermissions().removeAllPlayerFlags(Bukkit.getConsoleSender(), target, true);
        return ok ? result(true, "", null)
                : result(false, "清空玩家 flag 失败（玩家未注册或从未上线，需至少进服一次；请检查玩家名/UUID）", null);
    }

    private JsonObject applyDefaultFlags(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        r.getPermissions().applyDefaultFlags();
        return result(true, "", null);
    }

    private JsonObject setOwner(JsonObject payload) {
        if (!boolField(payload, "admin", false)) {
            return result(false, "只有管理员可以转让领地", null);
        }
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        String target = playerString(payload);
        if (target.isEmpty()) {
            return result(false, "缺少玩家名/UUID：请通过 player 传入玩家名或 UUID", null);
        }
        boolean resetFlags = boolField(payload, "reset_flags", true);
        UUID uuid = parseUuid(target);
        if (uuid != null) {
            r.getPermissions().setOwner(uuid, resetFlags);
        } else {
            r.getPermissions().setOwner(target, resetFlags);
        }
        return result(true, "", null);
    }

    private JsonObject setMessage(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        String type = str(payload, "type").toLowerCase(Locale.ROOT);
        if (!type.equals("enter") && !type.equals("leave")) {
            return result(false, "type 必须为 enter 或 leave", null);
        }
        String message = str(payload, "message");
        r.setEnterLeaveMessage(Bukkit.getConsoleSender(), message.isEmpty() ? null : message, type.equals("enter"), true);
        return result(true, "", null);
    }

    // ===================== 权限校验 =====================

    /** 写操作前置校验：领地存在 + 调用者是领地主人（或父领地主人/管理员）。 */
    private WriteContext managed(JsonObject payload) {
        String name = str(payload, "residence");
        ClaimedResidence r = residence(name);
        if (r == null) {
            return WriteContext.error("领地不存在: " + name);
        }
        if (!canManage(r, payload)) {
            return WriteContext.error("无权操作该领地（仅领地主人/父领地主人或管理员）");
        }
        return WriteContext.ok(r);
    }

    private static final class WriteContext {
        final ClaimedResidence residence;
        final JsonObject error;

        private WriteContext(ClaimedResidence residence, JsonObject error) {
            this.residence = residence;
            this.error = error;
        }

        static WriteContext ok(ClaimedResidence r) {
            return new WriteContext(r, null);
        }

        static WriteContext error(String message) {
            return new WriteContext(null, result0(false, message));
        }
    }

    private static JsonObject result0(boolean ok, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("message", message == null ? "" : message);
        out.add("data", new JsonObject());
        return out;
    }

    private boolean canManage(ClaimedResidence r, JsonObject payload) {
        if (boolField(payload, "admin", false)) return true;
        UUID uuid = uuidField(payload, "player_uuid");
        if (uuid != null) {
            if (r.isOwner(uuid)) return true;
            ClaimedResidence top = r.getTopParent();
            if (top != null && top != r && top.isOwner(uuid)) return true;
        }
        // 注意：HTTP 路由中的 player 参数是“目标玩家”而非调用者，
        // 调用者身份一律以 token 注入的 player_uuid 为准（admin=true 仅限服务端内部/管理通道）。
        return false;
    }

    // ===================== 并发与调度 =====================

    private JsonObject read(Callable<JsonObject> task) {
        return onMain(task);
    }

    private JsonObject write(String key, Callable<JsonObject> task) {
        if (key == null || key.isEmpty()) key = "global";
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
            if (!lock.hasQueuedThreads()) locks.remove(key, lock);
        }
    }

    private JsonObject onMain(Callable<JsonObject> task) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                plugin.getLogger().warning("[residence] 主线程执行异常: " + safeMessage(e));
                return result(false, "操作失败，请稍后再试（" + safeMessage(e) + "）", null);
            }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("[residence] 主线程调度异常: " + safeMessage(e));
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

    private ClaimedResidence residence(String name) {
        if (name == null || name.isEmpty()) return null;
        return Residence.getInstance().getResidenceManager().getByName(name);
    }

    private String playerString(JsonObject payload) {
        return str(payload, "player").trim();
    }

    private UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }

    private UUID uuidField(JsonObject payload, String key) {
        return parseUuid(str(payload, key));
    }

    private JsonArray xyz(org.bukkit.util.Vector v) {
        JsonArray a = new JsonArray();
        a.add(v.getBlockX());
        a.add(v.getBlockY());
        a.add(v.getBlockZ());
        return a;
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private String safeMessage(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private String str(JsonObject payload, String key) {
        return str(payload, key, "");
    }

    private String str(JsonObject payload, String key, String def) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsString() : def;
    }

    private boolean boolField(JsonObject payload, String key, boolean def) {
        return payload.has(key) && !payload.get(key).isJsonNull() && payload.get(key).getAsBoolean();
    }

    private int intField(JsonObject payload, String key, int def) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsInt() : def;
    }

    private JsonObject toJsonMap(Map<String, Boolean> map) {
        JsonObject out = new JsonObject();
        if (map == null) return out;
        for (Map.Entry<String, Boolean> e : map.entrySet()) out.addProperty(e.getKey(), e.getValue());
        return out;
    }

    private JsonObject toJsonMap2(Map<String, Map<String, Boolean>> map) {
        JsonObject out = new JsonObject();
        if (map == null) return out;
        for (Map.Entry<String, Map<String, Boolean>> e : map.entrySet()) out.add(e.getKey(), toJsonMap(e.getValue()));
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