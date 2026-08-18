package com.starcity.bridge.module.team;

import cn.gmzc.mgteam.MGTeamPlugin;
import cn.gmzc.mgteam.web.WebTeamManager;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

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
            Map<String, Object> r = switch (action) {
                // 只读
                case "list" -> w.teamList(intField(payload, "page", 1), intField(payload, "page_size", 20), str(payload, "query"));
                case "all" -> w.allTeams(bool(payload, "admin", false), intField(payload, "page", 1), intField(payload, "page_size", 20));
                case "detail" -> w.teamDetail(str(payload, "tid"));
                case "members" -> w.teamMembers(str(payload, "tid"));
                case "applications" -> w.teamApplications(str(payload, "tid"), str(payload, "player_uuid"), bool(payload, "admin", false));
                case "funds" -> w.teamFunds(str(payload, "tid"));
                case "logs" -> w.fundLogs(str(payload, "tid"), intField(payload, "page", 1), intField(payload, "page_size", 50));
                case "messages" -> w.teamMessages(str(payload, "tid"), intField(payload, "page", 1), intField(payload, "page_size", 50));
                case "message_state" -> w.messageState(str(payload, "tid"), str(payload, "player_uuid"));
                case "my_team" -> w.myTeam(str(payload, "player_uuid"));
                case "online_teammates" -> w.onlineTeammates(str(payload, "player_uuid"));
                case "search" -> w.teamSearch(str(payload, "query"));
                // 写：团队生命周期
                case "create" -> w.createTeam(str(payload, "player_uuid"), str(payload, "name"));
                case "join" -> w.applyJoin(str(payload, "player_uuid"), str(payload, "tid"));
                case "accept_application" -> w.acceptApplication(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "applicant_uuid"), bool(payload, "admin", false));
                case "reject_application" -> w.rejectApplication(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "applicant_uuid"), bool(payload, "admin", false));
                case "promote" -> w.promoteMember(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "target_uuid"), bool(payload, "admin", false));
                case "demote" -> w.demoteOperator(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "target_uuid"), bool(payload, "admin", false));
                case "remove_member" -> w.removeMember(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "target_uuid"), bool(payload, "admin", false));
                case "quit" -> w.quitTeam(str(payload, "player_uuid"));
                case "rename" -> w.renameTeam(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "name"), bool(payload, "admin", false));
                case "set_notice" -> w.setNotice(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "notice"), bool(payload, "admin", false));
                case "set_public" -> w.setPublic(str(payload, "tid"), str(payload, "player_uuid"), bool(payload, "public", false), bool(payload, "admin", false));
                case "set_friendly_fire" -> w.setFriendlyFire(str(payload, "tid"), str(payload, "player_uuid"), bool(payload, "allow", false), bool(payload, "admin", false));
                case "disband" -> w.disbandTeam(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "confirm_name"), bool(payload, "admin", false));
                // 写：资金与留言
                case "deposit_funds" -> w.depositFunds(str(payload, "tid"), str(payload, "player_uuid"), longField(payload, "amount", 0));
                case "withdraw_funds" -> w.withdrawFunds(str(payload, "tid"), str(payload, "player_uuid"), longField(payload, "amount", 0), bool(payload, "admin", false));
                case "post_message" -> w.postMessage(str(payload, "tid"), str(payload, "player_uuid"), str(payload, "content"));
                case "mark_messages_read" -> w.markMessagesRead(str(payload, "tid"), str(payload, "player_uuid"));
                case "mark_notice_read" -> w.markNoticeRead(str(payload, "tid"), str(payload, "player_uuid"));
                // 管理
                case "admin_sync_names" -> w.adminSyncNames(bool(payload, "admin", false));
                case "admin_reload" -> w.adminReloadConfig(bool(payload, "admin", false));
                case "admin_disband" -> w.adminDisband(bool(payload, "admin", false), str(payload, "tid"), str(payload, "confirm_name"));
                default -> null;
            };
            if (r == null) return null;
            return toJson(r);
        } catch (Exception e) {
            plugin.getLogger().warning("[team] 执行失败: " + action + " -> " + e.getMessage());
            return result(false, "团队操作失败: " + e.getMessage(), null);
        }
    }

    // ===================== 转换工具 =====================

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
