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

/**
 * /site 命令：查看网站账户绑定状态、切换静音模式。
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
                + "§a/site bind §7查看邮箱绑定状态（网站注册前提）");
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
                JsonObject data = plugin.modules().handleRequest("authme", "check_email", payload);
                if (data == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> sendMsg(player, "§c查询失败：AuthMe 模块未就绪"));
                    return;
                }
                boolean hasEmail = data.has("has_email") && data.get("has_email").getAsBoolean();
                String email = data.has("email") && !data.get("email").isJsonNull() ? data.get("email").getAsString() : null;
                String msg = hasEmail
                        ? "§a你的账号已绑定邮箱 §f" + email + "§a，可直接在网站注册。"
                        : "§c你还没有绑定邮箱，请先执行 §e/email add <邮箱> §c并确认后，再来网站注册。";
                Bukkit.getScheduler().runTask(plugin, () -> sendMsg(player, msg));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> sendMsg(player, "§c查询失败：" + e.getMessage()));
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
            return List.of("bind", "mute", "help");
        }
        return List.of();
    }
}