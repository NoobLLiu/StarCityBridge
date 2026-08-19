package com.starcity.bridge.module.team;

import cn.gmzc.mgteam.MGTeamPlugin;
import cn.gmzc.mgteam.web.WebTeamManager;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

/**
 * 团队插件（MGTeam-JE）对接模块。
 * <p>直接调用 MGTeam 的 WebTeamManager 全功能导出接口。传送相关接口已按需求从
 * MGTeam 导出层删除，本模块不再暴露 warp / teleport 动作。</p>
 */
public class TeamModule implements BridgeModule {

    private final StarCityBridge plugin;

    public TeamModule(StarCityBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "team";
    }

    private WebTeamManager web() {
        Plugin team = Bukkit.getPluginManager().getPlugin("MGTeam");
        if (team == null || !team.isEnabled() || !(team instanceof MGTeamPlugin)) {
            return null;
        }
        WebTeamManager w = ((MGTeamPlugin) team).getWebTeamManager();
        return w != null ? w : null;
    }

    @Override
    public JsonObject handleRequest(String action, JsonObject payload) {
        WebTeamManager w = web();
        if (w == null) {
            return result(false, "MGTeam 插件未加载或未启用", null);
        }
        try {
            // 所有只读接口都带调用者身份（player_uuid）与 admin 标志，
            // 权限校验统一放在 MGTeam-JE 的 WebTeamManager 内（与游戏内一致）。
            String tid = str(payload, "tid");
            String uuid = str(payload, "player_uuid");
            boolean admin = bool(payload, "admin", false);
            JsonObject r = switch (action) {
                // 只读
                case "list" -> toJson(w.teamList(intField(payload, "page", 1), intField(payload, "page_size", 20), str(payload, "query")));
                case "all" -> toJson(w.allTeams(admin, intField(payload, "page", 1), intField(payload, "page_size", 20)));
                case "detail" -> toJson(w.teamDetail(tid, uuid, admin));
                case "members" -> listToJson(w.teamMembers(tid, uuid, admin));
                case "applications" -> listToJson(w.teamApplications(tid, uuid, admin));
                case "funds" -> toJson(w.teamFunds(tid, uuid, admin));
                case "logs" -> toJson(w.fundLogs(tid, uuid, admin, intField(payload, "page", 1), intField(payload, "page_size", 50)));
                case "messages" -> toJson(w.teamMessages(tid, uuid, admin, intField(payload, "page", 1), intField(payload, "page_size", 50)));
                case "message_state" -> toJson(w.messageState(tid, uuid, admin));
                case "my_team" -> toJson(w.myTeam(uuid));
                case "online_teammates" -> toJson(w.onlineTeammates(uuid));
                case "search" -> toJson(w.teamSearch(str(payload, "query")));
                // 写：团队生命周期
                case "create" -> toJson(w.createTeam(uuid, str(payload, "name")));
                case "join" -> toJson(w.applyJoin(uuid, tid));
                case "accept_application" -> toJson(w.acceptApplication(tid, uuid, str(payload, "applicant_uuid"), admin));
                case "reject_application" -> toJson(w.rejectApplication(tid, uuid, str(payload, "applicant_uuid"), admin));
                case "promote" -> toJson(w.promoteMember(tid, uuid, str(payload, "target_uuid"), admin));
                case "demote" -> toJson(w.demoteOperator(tid, uuid, str(payload, "target_uuid"), admin));
                case "remove_member" -> toJson(w.removeMember(tid, uuid, str(payload, "target_uuid"), admin));
                case "quit" -> toJson(w.quitTeam(uuid));
                case "rename" -> toJson(w.renameTeam(tid, uuid, str(payload, "name"), admin));
                case "set_notice" -> toJson(w.setNotice(tid, uuid, str(payload, "notice"), admin));
                case "set_public" -> toJson(w.setPublic(tid, uuid, bool(payload, "public", false), admin));
                case "set_friendly_fire" -> toJson(w.setFriendlyFire(tid, uuid, bool(payload, "allow", false), admin));
                case "disband" -> toJson(w.disbandTeam(tid, uuid, str(payload, "confirm_name"), admin));
                // 写：资金与留言
                case "deposit_funds" -> toJson(w.depositFunds(tid, uuid, longField(payload, "amount", 0)));
                case "withdraw_funds" -> toJson(w.withdrawFunds(tid, uuid, longField(payload, "amount", 0), admin));
                case "post_message" -> toJson(w.postMessage(tid, uuid, str(payload, "content")));
                case "mark_messages_read" -> toJson(w.markMessagesRead(tid, uuid));
                case "mark_notice_read" -> toJson(w.markNoticeRead(tid, uuid));
                // 管理
                case "admin_sync_names" -> toJson(w.adminSyncNames(admin));
                case "admin_reload" -> toJson(w.adminReloadConfig(admin));
                case "admin_disband" -> toJson(w.adminDisband(admin, tid, str(payload, "confirm_name")));
                default -> null;
            };
            return r;
        } catch (Exception e) {
            plugin.getLogger().warning("[team] 执行失败: " + action + " -> " + e.getMessage());
            return result(false, "团队操作失败: " + e.getMessage(), null);
        }
    }

    // ===================== 转换工具 =====================

    /** 列表型接口（团队成员/申请）：null 表示权限不足或团队不存在。 */
    private JsonObject listToJson(List<Map<String, Object>> list) {
        if (list == null) return result(false, "权限不足或团队不存在", null);
        return result(true, "", plugin.gson().toJsonTree(list));
    }

    private JsonObject toJson(Map<String, Object> r) {
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        Object message = r.get("message");
        Object data = r.get("data");
        return result(ok, message == null ? "" : String.valueOf(message), data == null ? null : plugin.gson().toJsonTree(data));
    }

    private String str(JsonObject p, String key) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject p, String key, boolean def) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsBoolean() : def;
    }

    private static int intField(JsonObject p, String key, int def) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsInt() : def;
    }

    private static long longField(JsonObject p, String key, long def) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsLong() : def;
    }

    private static JsonObject result(boolean ok, String message, com.google.gson.JsonElement data) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("message", message == null ? "" : message);
        out.add("data", data == null ? new JsonObject() : data);
        return out;
    }
}
