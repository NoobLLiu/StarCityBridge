package com.starcity.bridge.command;

import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * /site 命令：查看网站账户绑定状态、在游戏内重置网站密码。
 */
public class BridgeCommand implements CommandExecutor, TabCompleter {

    private final StarCityBridge plugin;

    public BridgeCommand(StarCityBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令仅限游戏内使用");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "bind" -> handleBind(player);
            case "resetpw" -> handleResetPw(player, args);
            case "mute" -> handleMute(player);
            default -> sendHelp(player);
        }
        return true;
    }

    /** 发送消息；静音模式下不返回（mute 指令自身的确认除外） */
    private void sendMsg(Player player, String message) {
        if (!plugin.isQuietMode()) {
            player.sendMessage(message);
        }
    }

    private void sendHelp(Player player) {
        sendMsg(player, "§e===== 网站账户命令 =====\n"
                + "§a/site bind §7查看邮箱绑定状态（网站注册前提）\n"
                + "§a/site resetpw <新密码> §7在游戏内重置网站密码（至少 6 位）");
    }

    private void handleBind(Player player) {
        if (!player.hasPermission("starcitybridge.site.bind")) {
            sendMsg(player, "§c无权限执行该命令");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("player", player.getName());
                JsonObject data = plugin.request("authme", "check_email", payload).get(10, TimeUnit.SECONDS);
                boolean hasEmail = data.has("has_email") && data.get("has_email").getAsBoolean();
                String email = data.has("email") && !data.get("email").isJsonNull() ? data.get("email").getAsString() : null;
                String msg = hasEmail
                        ? "§a你的账号已绑定邮箱 §f" + email + "§a，可直接在网站注册。"
                        : "§c你还没有绑定邮箱，请先执行 §e/email add <邮箱> §c并确认后，再来网站注册。";
                Bukkit.getScheduler().runTask(plugin, () -> sendMsg(player, msg));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> sendMsg(player, "§c查询失败：网站后端未连接或超时"));
            }
        });
    }

    private void handleResetPw(Player player, String[] args) {
        if (!player.hasPermission("starcitybridge.site.resetpw")) {
            sendMsg(player, "§c无权限执行该命令");
            return;
        }
        if (args.length < 2 || args[1].length() < 6) {
            sendMsg(player, "§c用法：/site resetpw <新密码>（至少 6 位）");
            return;
        }
        String newPassword = args[1];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("player", player.getName());
                payload.addProperty("new_password", newPassword);
                JsonObject data = plugin.request("auth", "reset_site_password", payload).get(10, TimeUnit.SECONDS);
                boolean ok = data.has("ok") && data.get("ok").getAsBoolean();
                String error = data.has("error") && !data.get("error").isJsonNull() ? data.get("error").getAsString() : "未知原因";
                String msg = ok ? "§a网站密码已重置，请使用新密码登录网站。" : "§c网站密码重置失败：" + error;
                Bukkit.getScheduler().runTask(plugin, () -> sendMsg(player, msg));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> sendMsg(player, "§c重置失败：网站后端未连接或超时"));
            }
        });
    }


    private void handleMute(Player player) {
        if (!player.hasPermission("starcitybridge.site.bind")) {
            sendMsg(player, "§c无权限执行该命令");
            return;
        }
        boolean quiet = plugin.toggleQuietMode();
        // 静音指令本身始终给出确认，便于知道当前状态
        player.sendMessage(quiet
            ? "§a已开启静音：命令不再返回消息（已保存，重启后仍生效）。再次输入 /site mute 恢复。"
            : "§a已恢复：命令消息正常输出。");
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("bind", "resetpw", "mute", "help");
        }
        return List.of();
    }
}