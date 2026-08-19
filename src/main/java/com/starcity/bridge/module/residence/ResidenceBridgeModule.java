package com.starcity.bridge.module.residence;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
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
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
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
                case "market" -> read(() -> market(payload));
                case "my_rents" -> read(() -> myRents(payload));
                // 写（领地锁 + 主线程串行）：以下操作不需要玩家在线（CommandSender / 无 Player 变体）
                case "set_flag" -> write(residenceName(payload), () -> setFlag(payload));
                case "set_player_flag" -> write(residenceName(payload), () -> setPlayerFlag(payload));
                case "remove_player_flag" -> write(residenceName(payload), () -> removePlayerFlag(payload));
                case "clear_player_flags" -> write(residenceName(payload), () -> clearPlayerFlags(payload));
                case "apply_default_flags" -> write(residenceName(payload), () -> applyDefaultFlags(payload));
                case "set_owner" -> write(residenceName(payload), () -> setOwner(payload));
                case "set_message" -> write(residenceName(payload), () -> setMessage(payload));
                case "rename" -> write(residenceName(payload), () -> rename(payload));
                case "mirror_perms" -> write(residenceName(payload), () -> mirrorPerms(payload));
                case "delete" -> write(residenceName(payload), () -> deleteRes(payload));
                case "sell" -> write(residenceName(payload), () -> sell(payload));
                case "unlist_sell" -> write(residenceName(payload), () -> unlistSell(payload));
                case "unlist_rent" -> write(residenceName(payload), () -> unlistRent(payload));
                // 写：需要玩家在线（Residence 交易/出租/转让 API 只接受 Player 对象，离线时返回明确提示）
                case "buy" -> write(residenceName(payload), () -> buy(payload));
                case "set_rent" -> write(residenceName(payload), () -> setRent(payload));
                case "rent" -> write(residenceName(payload), () -> rent(payload));
                case "unrent" -> write(residenceName(payload), () -> unrent(payload));
                case "pay_rent" -> write(residenceName(payload), () -> payRent(payload));
                case "transfer" -> write(residenceName(payload), () -> transfer(payload));
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
        boolean admin = boolField(payload, "admin", false);

        ResidenceManager manager = Residence.getInstance().getResidenceManager();
        List<ClaimedResidence> all = new ArrayList<>(manager.getResidences().values());
        List<JsonObject> rows = new ArrayList<>();
        for (ClaimedResidence r : all) {
            if (mine && (caller == null || !r.isOwner(caller))) continue;
            // 可见性过滤（与游戏内一致）：服务器领地 / 非 hidden 领地 / 本人拥有 / 管理员
            if (!mine && !canView(r, caller, admin)) continue;
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
        // 可见性 / 可管理性 / 服务器领地 / 隐藏状态 / 经济状态
        UUID caller = uuidField(payload, "player_uuid");
        boolean admin = boolField(payload, "admin", false);
        data.addProperty("is_server_land", isServerLand(r));
        data.addProperty("hidden", r.getPermissions().has(Flags.hidden, false));
        data.addProperty("viewable", canView(r, caller, admin));
        data.addProperty("can_manage", admin || (caller != null && isOwnerOrTopOwner(r, caller)));
        data.addProperty("economy_enabled", Residence.getInstance().getConfigManager().enableEconomy());
        data.addProperty("rent_system_enabled", Residence.getInstance().getConfigManager().enabledRentSystem());

        // 传送点坐标（玩家在线时可取；离线省略，不影响只读展示）
        Player callerPlayer = caller == null ? null : Bukkit.getPlayer(caller);
        if (callerPlayer != null) {
            try {
                Location tp = r.getTeleportLocation(callerPlayer, false);
                if (tp != null) {
                    JsonObject t = new JsonObject();
                    t.addProperty("world", tp.getWorld() == null ? r.getWorldName() : tp.getWorld().getName());
                    t.addProperty("x", tp.getBlockX());
                    t.addProperty("y", tp.getBlockY());
                    t.addProperty("z", tp.getBlockZ());
                    data.add("teleport", t);
                }
            } catch (Exception ignored) {
                // 无传送点 / 玩家对象受限时忽略
            }
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
        // 分类结构（参考 ResidenceList 分类，供前端分组渲染与权限编辑）
        data.add("categories", flagCategories(r, payload));
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

    // ===================== 市场（只读） =====================

    /** 领地市场：出售中 + 可租（未租出）领地，分页。 */
    private JsonObject market(JsonObject payload) {
        int page = Math.max(1, intField(payload, "page", 1));
        int pageSize = Math.max(1, Math.min(100, intField(payload, "page_size", 20)));
        Residence plugin = Residence.getInstance();
        List<JsonObject> rows = new ArrayList<>();
        // 出售中
        Map<String, Integer> forSale = plugin.getTransactionManager().getBuyableResidences();
        if (forSale != null) {
            for (Map.Entry<String, Integer> e : forSale.entrySet()) {
                ClaimedResidence r = plugin.getResidenceManager().getByName(e.getKey());
                if (r == null) continue;
                JsonObject o = marketRow(r, "sell");
                o.addProperty("price", e.getValue());
                rows.add(o);
            }
        }
        // 可租（未租出）
        for (ClaimedResidence r : plugin.getRentManager().getRentableResidences()) {
            if (r == null || r.isRented()) continue;
            JsonObject o = marketRow(r, "rent");
            o.addProperty("price", plugin.getRentManager().getCostOfRent(r));
            o.addProperty("days", plugin.getRentManager().getRentDays(r));
            o.addProperty("renewable", plugin.getRentManager().getRentableRepeatable(r));
            rows.add(o);
        }
        // 排序：先按类型（sell 在前），再按名称
        rows.sort((a, b) -> {
            int c = a.get("type").getAsString().compareTo(b.get("type").getAsString());
            return c != 0 ? c : a.get("name").getAsString().compareToIgnoreCase(b.get("name").getAsString());
        });
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
        data.add("items", arr);
        data.addProperty("economy_enabled", plugin.getConfigManager().enableEconomy());
        data.addProperty("rent_system_enabled", plugin.getConfigManager().enabledRentSystem());
        return result(true, "", data);
    }

    private JsonObject marketRow(ClaimedResidence r, String type) {
        JsonObject o = new JsonObject();
        o.addProperty("residence", r.getName());
        o.addProperty("name", r.getName());
        o.addProperty("owner", r.getOwner());
        UUID ou = r.getOwnerUUID();
        o.addProperty("owner_uuid", ou == null ? "" : ou.toString());
        o.addProperty("world", r.getWorldName());
        o.addProperty("type", type);
        o.addProperty("size", r.getTotalSize());
        o.addProperty("areas", r.getAreaCount());
        return o;
    }

    /** 我租用的领地（只读）。 */
    private JsonObject myRents(JsonObject payload) {
        UUID uuid = uuidField(payload, "player_uuid");
        if (uuid == null) {
            return result(false, "缺少 player_uuid（登录令牌未携带玩家信息）", null);
        }
        List<ClaimedResidence> rented = Residence.getInstance().getRentManager().getRentedLands(uuid);
        JsonArray arr = new JsonArray();
        if (rented != null) {
            for (ClaimedResidence r : rented) {
                JsonObject o = new JsonObject();
                o.addProperty("residence", r.getName());
                o.addProperty("owner", r.getOwner());
                o.addProperty("world", r.getWorldName());
                o.addProperty("cost", Residence.getInstance().getRentManager().getCostOfRent(r));
                o.addProperty("days", Residence.getInstance().getRentManager().getRentDays(r));
                RentedLand rl = r.getRentedLand();
                if (rl != null) {
                    o.addProperty("renter", orEmpty(rl.getRenterName()));
                    o.addProperty("end_time", rl.endTime);
                    o.addProperty("auto_pay", rl.AutoPay);
                }
                arr.add(o);
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("total", arr.size());
        data.add("rents", arr);
        return result(true, "", data);
    }

    // ===================== 写：不需要玩家在线 =====================

    /** 重命名领地（CommandSender 变体 + resadmin，console 安全）。 */
    private JsonObject rename(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        String newName = str(payload, "new_name").trim();
        if (newName.isEmpty()) {
            return result(false, "请输入新的领地名称（new_name）", null);
        }
        boolean ok = Residence.getInstance().getResidenceManager()
                .renameResidence(Bukkit.getConsoleSender(), ctx.residence.getName(), newName, true);
        return ok ? result(true, "", null)
                : result(false, "重命名失败（名称可能不合法、长度超限或已被占用）", null);
    }

    /** 镜像权限：把源领地的全部权限复制到目标领地（applyTemplate(null, ...) 内部强制 resadmin，console 安全）。 */
    private JsonObject mirrorPerms(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        String sourceName = str(payload, "source").trim();
        if (sourceName.isEmpty()) {
            return result(false, "缺少 source（源领地名称）", null);
        }
        ClaimedResidence source = residence(sourceName);
        if (source == null) {
            return result(false, "源领地不存在: " + sourceName, null);
        }
        // 权限：同时是目标领地与源领地的拥有者（或父领地拥有者），或管理员
        UUID uuid = uuidField(payload, "player_uuid");
        boolean admin = boolField(payload, "admin", false);
        if (!admin && uuid != null) {
            boolean manageTarget = isOwnerOrTopOwner(ctx.residence, uuid);
            boolean manageSource = isOwnerOrTopOwner(source, uuid);
            if (!manageTarget || !manageSource) {
                return result(false, "无权镜像权限（需同时拥有目标领地和源领地，或为管理员）", null);
            }
        }
        ctx.residence.getPermissions().applyTemplate(null, source.getPermissions(), true);
        return result(true, "", null);
    }

    /** 删除领地（不可逆）：仅领地主人/父领地主人或管理员，需 confirm=true 二次确认。 */
    private JsonObject deleteRes(JsonObject payload) {
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        boolean admin = boolField(payload, "admin", false);
        UUID uuid = uuidField(payload, "player_uuid");
        boolean owner = uuid != null && isOwnerOrTopOwner(r, uuid);
        if (!admin && !owner) {
            return result(false, "只有领地主人或管理员可以删除领地", null);
        }
        if (!boolField(payload, "confirm", false)) {
            return result(false, "删除是不可逆操作，请传 confirm=true 确认删除", null);
        }
        String confirmName = str(payload, "confirm_name").trim();
        if (!confirmName.isEmpty() && !confirmName.equalsIgnoreCase(r.getName())) {
            return result(false, "确认名称与领地名称不匹配，已取消删除", null);
        }
        Residence.getInstance().getResidenceManager().removeResidence(Bukkit.getConsoleSender(), r, true);
        return result(true, "", null);
    }

    /** 出售挂牌：使用无 Player 变体 putForSale(ClaimedResidence, int)，权限在模块内校验（玩家无需在线）。 */
    private JsonObject sell(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        if (!Residence.getInstance().getConfigManager().enableEconomy()) {
            return result(false, "服务器未启用经济系统，无法出售领地", null);
        }
        if (r.isForRent()) {
            return result(false, "领地正在出租，无法同时出售（请先取消出租）", null);
        }
        int price = intField(payload, "price", -1);
        if (price <= 0) {
            return result(false, "请输入正确的出售价格（price，大于 0）", null);
        }
        boolean ok = Residence.getInstance().getTransactionManager().putForSale(r, price);
        return ok ? result(true, "", null) : result(false, "挂牌失败（领地可能已在出售）", null);
    }

    /** 取消出售挂牌（无 Player 变体 + 模块内权限校验）。 */
    private JsonObject unlistSell(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        if (!r.isForSell()) {
            return result(false, "该领地未在出售", null);
        }
        Residence.getInstance().getTransactionManager().removeFromSale(r);
        return result(true, "", null);
    }

    /** 取消出租挂牌（无 Player 变体 removeRentable + 模块内权限校验；已租出的请先退租）。 */
    private JsonObject unlistRent(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        if (!r.isForRent()) {
            return result(false, "该领地未在出租", null);
        }
        if (r.isRented()) {
            return result(false, "该领地已被租出，请先退租（unrent）后再取消出租", null);
        }
        Residence.getInstance().getRentManager().removeRentable(r);
        return result(true, "", null);
    }

    // ===================== 写：需要玩家在线 =====================

    private JsonObject buy(JsonObject payload) {
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        if (!r.isForSell()) {
            return result(false, "该领地未在出售", null);
        }
        Player p = onlinePlayer(payload);
        if (p == null) {
            return result(false, "购买领地需要你在线（请先登录服务器后再购买）", null);
        }
        if (r.isOwner(p)) {
            return result(false, "不能购买自己名下的领地", null);
        }
        Residence.getInstance().getTransactionManager().buyPlot(r, p, boolField(payload, "admin", false));
        return r.isForSell() ? result(false, "购买失败（可能余额不足或已达领地数量上限）", null)
                : result(true, "", null);
    }

    private JsonObject setRent(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        if (!Residence.getInstance().getConfigManager().enabledRentSystem()) {
            return result(false, "服务器未启用租凭系统，无法出租领地", null);
        }
        if (r.isForSell()) {
            return result(false, "领地正在出售，无法同时出租（请先取消出售）", null);
        }
        Player p = onlinePlayer(payload);
        if (p == null) {
            return result(false, "设置出租需要你在线（请先登录服务器）", null);
        }
        int cost = intField(payload, "cost", 100);
        int days = intField(payload, "days", 7);
        if (cost < 0) {
            return result(false, "租金不能为负数", null);
        }
        if (days <= 0) {
            return result(false, "租期天数必须大于 0", null);
        }
        boolean renewing = boolField(payload, "allow_renewing", true);
        boolean stayInMarket = boolField(payload, "stay_in_market", true);
        boolean autoPay = boolField(payload, "allow_auto_pay", false);
        Residence.getInstance().getRentManager()
                .setForRent(p, r, cost, days, renewing, stayInMarket, autoPay, boolField(payload, "admin", false));
        return r.isForRent() ? result(true, "", null)
                : result(false, "设置出租失败（请检查租金/租期是否合法）", null);
    }

    private JsonObject rent(JsonObject payload) {
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        if (!r.isForRent()) {
            return result(false, "该领地未在出租", null);
        }
        if (r.isRented()) {
            return result(false, "该领地已被租出", null);
        }
        Player p = onlinePlayer(payload);
        if (p == null) {
            return result(false, "租用领地需要你在线（请先登录服务器）", null);
        }
        if (r.isOwner(p)) {
            return result(false, "不能租用自己名下的领地", null);
        }
        boolean autoPay = boolField(payload, "auto_pay", false);
        Residence.getInstance().getRentManager().rent(p, r, autoPay, boolField(payload, "admin", false));
        return r.isRented() ? result(true, "", null)
                : result(false, "租用失败（可能余额不足或已达租用上限）", null);
    }

    /** 退租 / 强制退租：领地主人、租客本人或管理员可执行。 */
    private JsonObject unrent(JsonObject payload) {
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        if (!r.isRented()) {
            return result(false, "该领地未被租出", null);
        }
        Player p = onlinePlayer(payload);
        if (p == null) {
            return result(false, "该操作需要你在线（请先登录服务器）", null);
        }
        boolean admin = boolField(payload, "admin", false);
        RentedLand rented = r.getRentedLand();
        String renter = rented == null ? "" : orEmpty(rented.getRenterName());
        boolean isRenter = !renter.isEmpty() && p.getName().equalsIgnoreCase(renter);
        boolean isOwner = r.isOwner(p);
        if (!admin && !isOwner && !isRenter) {
            return result(false, "只有领地主人、租客本人或管理员可以退租", null);
        }
        Residence.getInstance().getRentManager().unrent(p, r, admin);
        return result(true, "", null);
    }

    /** 支付租金：租客本人或管理员可执行（续租）。 */
    private JsonObject payRent(JsonObject payload) {
        ClaimedResidence r = residence(str(payload, "residence"));
        if (r == null) {
            return result(false, "领地不存在: " + str(payload, "residence"), null);
        }
        if (!r.isRented()) {
            return result(false, "该领地未被租出", null);
        }
        Player p = onlinePlayer(payload);
        if (p == null) {
            return result(false, "支付租金需要你在线（请先登录服务器）", null);
        }
        boolean admin = boolField(payload, "admin", false);
        RentedLand rented = r.getRentedLand();
        String renter = rented == null ? "" : orEmpty(rented.getRenterName());
        boolean isRenter = !renter.isEmpty() && p.getName().equalsIgnoreCase(renter);
        if (!admin && !isRenter) {
            return result(false, "只有租客本人或管理员可以支付租金", null);
        }
        Residence.getInstance().getRentManager().payRent(p, r, admin);
        return result(true, "", null);
    }

    /** 转让领地：Residence 本体要求「发起者在线 + 接收者在线」，游戏内同样如此。 */
    private JsonObject transfer(JsonObject payload) {
        WriteContext ctx = managed(payload);
        if (ctx.error != null) return ctx.error;
        ClaimedResidence r = ctx.residence;
        String targetName = playerString(payload);
        if (targetName.isEmpty()) {
            return result(false, "缺少 target（目标玩家名）", null);
        }
        Player requester = onlinePlayer(payload);
        if (requester == null) {
            return result(false, "转让需要你先登录服务器（转让发起者必须在线）", null);
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            return result(false, "目标玩家不在线（Residence 转让要求接收者在线，请让 TA 先登录服务器）", null);
        }
        boolean includeSubzones = boolField(payload, "include_subzones", true);
        Residence.getInstance().getResidenceManager()
                .giveResidence(requester, targetName, r, boolField(payload, "admin", false), includeSubzones);
        return target.getUniqueId().equals(r.getOwnerUUID()) ? result(true, "", null)
                : result(false, "转让失败（接收者可能已达领地数量上限或面积限制）", null);
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
        if (uuid != null && isOwnerOrTopOwner(r, uuid)) return true;
        // 注意：HTTP 路由中的 player 参数是“目标玩家”而非调用者，
        // 调用者身份一律以 token 注入的 player_uuid 为准（admin=true 仅限服务端内部/管理通道）。
        return false;
    }

    /** 玩家是否为领地主人（或父领地主人）。 */
    private boolean isOwnerOrTopOwner(ClaimedResidence r, UUID uuid) {
        if (r.isOwner(uuid)) return true;
        ClaimedResidence top = r.getTopParent();
        return top != null && top != r && top.isOwner(uuid);
    }

    /** 可见性（与游戏内 list 一致）：管理员 / 服务器领地 / 非 hidden 领地 / 本人拥有。 */
    private boolean canView(ClaimedResidence r, UUID uuid, boolean admin) {
        if (admin) return true;
        if (isServerLand(r)) return true;
        boolean hidden = r.getPermissions().has(Flags.hidden, false);
        if (!hidden) return true;
        return uuid != null && r.isOwner(uuid);
    }

    /** 服务器领地：ownerUUID 为空、占位 UUID 或等于服务器 UUID。 */
    private boolean isServerLand(ClaimedResidence r) {
        UUID ou = r.getOwnerUUID();
        if (ou == null) return true;
        UUID serverUuid = Residence.getInstance().getServerUUID();
        return serverUuid != null && serverUuid.equals(ou);
    }

    /** 在线玩家：Residence 交易/出租/转让 API 只接受 Player 对象，离线时返回 null 由调用方给出明确提示。 */
    private Player onlinePlayer(JsonObject payload) {
        UUID uuid = uuidField(payload, "player_uuid");
        return uuid == null ? null : Bukkit.getPlayer(uuid);
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

    // ===================== Flag 分类（参考 ResidenceList 分组） =====================

    private JsonArray flagCategories(ClaimedResidence r, JsonObject payload) {
        JsonArray cats = new JsonArray();
        boolean admin = boolField(payload, "admin", false);
        UUID caller = uuidField(payload, "player_uuid");
        boolean manager = admin || (caller != null && isOwnerOrTopOwner(r, caller));
        ResidencePermissions perms = r.getPermissions();
        for (FlagCategory cat : FlagCategory.values()) {
            JsonObject co = new JsonObject();
            co.addProperty("key", cat.key);
            co.addProperty("name", cat.displayName);
            JsonArray flagsArr = new JsonArray();
            for (String name : cat.flags) {
                Flags f = Flags.getFlag(name);
                if (f == null) continue;
                JsonObject fo = new JsonObject();
                fo.addProperty("flag", f.name());
                fo.addProperty("name", f.getName());
                fo.addProperty("desc", f.getDesc());
                fo.addProperty("default", f.isEnabled());
                fo.addProperty("mode", f.getFlagMode().name());
                Boolean v = perms.getFlags().get(f.name());
                if (v == null) {
                    fo.addProperty("value", (String) null);
                } else {
                    fo.addProperty("value", v);
                }
                // 与游戏内一致：全局 flag 仅 Residence/Both 模式可用；玩家 flag 仅 Player/Both 模式可用
                boolean validGlobal = manager && perms.checkValidFlag(f.name(), true)
                        && f.getFlagMode() != Flags.FlagMode.Player;
                boolean validPlayer = manager && perms.checkValidFlag(f.name(), false)
                        && f.getFlagMode() != Flags.FlagMode.Residence;
                fo.addProperty("global_editable", validGlobal);
                fo.addProperty("player_editable", validPlayer);
                flagsArr.add(fo);
            }
            if (flagsArr.size() > 0) {
                co.add("flags", flagsArr);
                cats.add(co);
            }
        }
        return cats;
    }

    /** Residence flag 分类（与 ResidenceList 的 ResidenceFlagCategory 一致，仅字符串引用、不依赖 ResidenceList）。 */
    private enum FlagCategory {
        BUILD_DESTROY("build_destroy", "建造与破坏",
                "build", "place", "destroy", "container"),
        INTERACT_USE("interact_use", "交互与使用",
                "use", "door", "button", "lever", "pressure", "diode", "note", "table", "enchant",
                "brew", "anvil", "beacon", "bed", "cake", "flowerpot", "egg", "honey", "honeycomb",
                "copper", "brush", "goathorn", "anchor", "commandblock", "command"),
        ITEMS_DROPS("items_drops", "物品与掉落",
                "itemdrop", "itempickup", "nodurability"),
        MOVEMENT_TELEPORT("movement_teleport", "移动与传送",
                "move", "tp", "enderpearl", "chorustp", "fly", "nofly", "elytra",
                "wspeed1", "wspeed2", "jump2", "jump3"),
        ENTITIES_MOBS("entities_mobs", "生物与实体",
                "mobkilling", "animalkilling", "vehicledestroy", "vehicleplacing", "riding", "leash",
                "shear", "dye", "animalfeeding", "nametag", "harvest", "trade", "hook",
                "animals", "monsters", "nomobs", "canimals", "cmonsters", "nanimals", "nmonsters",
                "sanimals", "smonsters", "creeper", "dragongrief", "witherspawn", "phantomspawn",
                "witherdamage", "witherdestruction", "mobexpdrop", "mobitemdrop", "boarding", "raid"),
        ENVIRONMENT_PHYSICS("environment_physics", "环境与物理",
                "ignite", "flow", "waterflow", "lavaflow", "explode", "tnt", "piston",
                "pistonprotection", "decay", "grow", "spread", "skulk", "iceform", "icemelt",
                "dryup", "coraldryup", "copperoxidation", "fallinprotection", "flowinprotection",
                "snowtrail", "trample", "golemopenchest", "burn", "fireball", "firespread", "anvilbreak"),
        COMBAT_PROTECTION("combat_protection", "战斗与保护",
                "friendlyfire", "pvp", "damage", "falldamage", "safezone", "shoot", "snowball",
                "hotfloor", "keepinv", "keepexp", "respawn", "healing", "feed"),
        VISUAL_EFFECTS("visual_effects", "视觉效果",
                "day", "night", "rain", "sun", "glow", "title", "visualizer", "coords", "hidden"),
        ECONOMY_RESIDENCE("economy_residence", "经济与领地",
                "admin", "bank", "subzone", "chat", "shop", "backup", "craft");

        final String key;
        final String displayName;
        final List<String> flags;

        FlagCategory(String key, String displayName, String... flags) {
            this.key = key;
            this.displayName = displayName;
            this.flags = Arrays.asList(flags);
        }
    }
}
