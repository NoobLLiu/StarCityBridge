package com.starcity.bridge.module.authme;

import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;
import com.starcity.bridge.module.ModuleManager;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.EmailConfirmedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 登录插件（AuthMe）对接模块。
 * <p>提供网站后端请求处理：邮箱绑定状态查询、绑定校验；
 * 监听邮箱确认完成事件并推送给后端。</p>
 */
public class AuthMeModule implements BridgeModule, Listener {

    private final StarCityBridge plugin;

    public AuthMeModule(StarCityBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "authme";
    }

    @Override
    public void onRegister(ModuleManager manager) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public JsonObject handleRequest(String action, JsonObject payload) {
        return switch (action) {
            case "check_email" -> checkEmail(payload);
            case "verify_binding" -> verifyBinding(payload);
            case "login_check" -> loginCheck(payload);
            default -> null;
        };
    }

    /**
     * 查询玩家邮箱绑定状态：{player} -> {available, has_email, email, pending_email}
     */
    private JsonObject checkEmail(JsonObject payload) {
        JsonObject out = new JsonObject();
        AuthMeApi api = api();
        if (api == null) {
            out.addProperty("available", false);
            return out;
        }
        String player = payload.has("player") ? payload.get("player").getAsString() : "";
        String email = api.getEmail(player);
        out.addProperty("available", true);
        out.addProperty("has_email", email != null);
        out.addProperty("email", email);
        out.addProperty("pending_email", api.getPendingEmail(player));
        return out;
    }

    /**
     * 校验邮箱是否已绑定到指定玩家：{player, email} -> {available, valid, email}
     */
    private JsonObject verifyBinding(JsonObject payload) {
        JsonObject out = new JsonObject();
        AuthMeApi api = api();
        if (api == null) {
            out.addProperty("available", false);
            out.addProperty("valid", false);
            return out;
        }
        String player = payload.has("player") ? payload.get("player").getAsString() : "";
        String email = payload.has("email") ? payload.get("email").getAsString() : "";
        String bound = api.getEmail(player);
        boolean valid = bound != null && bound.equalsIgnoreCase(email);
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(player);
        out.addProperty("player_uuid", offline.hasPlayedBefore() ? offline.getUniqueId().toString() : "");
        out.addProperty("available", true);
        out.addProperty("valid", valid);
        out.addProperty("email", bound);
        return out;
    }

    /**
     * 玩家通过 /email confirm 完成邮箱绑定后，通知网站后端。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmailConfirmed(EmailConfirmedEvent event) {
        JsonObject payload = new JsonObject();
        payload.addProperty("player", event.getPlayer().getName());
        payload.addProperty("email", event.getEmail());
        plugin.wsClient().sendEvent("authme", "email_confirmed", payload);
        plugin.getLogger().info("邮箱绑定完成事件已推送: " + event.getPlayer().getName() + " -> " + event.getEmail());
    }


    /**
     * 网站登录校验：邮箱 + 服务器内登录密码。
     * 返回 {available, valid, player, player_uuid, email, is_op}
     */
    private JsonObject loginCheck(JsonObject payload) {
        JsonObject out = new JsonObject();
        AuthMeApi api = api();
        if (api == null) {
            out.addProperty("available", false);
            return out;
        }
        String email = payload.has("email") ? payload.get("email").getAsString() : "";
        String password = payload.has("password") ? payload.get("password").getAsString() : "";
        boolean valid = api.checkPasswordByEmail(email, password);
        out.addProperty("available", true);
        out.addProperty("valid", valid);
        if (valid) {
            String player = api.getPlayerNameByEmail(email);
            out.addProperty("player", player);
            out.addProperty("email", email);
            org.bukkit.OfflinePlayer offline = player == null ? null : Bukkit.getOfflinePlayer(player);
            out.addProperty("player_uuid", offline != null && offline.hasPlayedBefore() ? offline.getUniqueId().toString() : "");
            out.addProperty("is_op", offline != null && offline.isOp());
        }
        return out;
    }
    private AuthMeApi api() {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) {
            return null;
        }
        return AuthMeApi.getInstance();
    }
}