package com.starcity.bridge.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置：从 config.yml 读取后端地址、令牌、重连与模块开关。
 */
public record PluginConfig(
        String backendUrl,
        String token,
        int reconnectDelaySeconds,
        int heartbeatSeconds,
        boolean authMeEnabled,
        String connectionMode,
        String serverHost,
        int serverPort,
        String serverPath,
        boolean quietMode) {

    public static PluginConfig from(FileConfiguration cfg) {
        return new PluginConfig(
                cfg.getString("backend.url", "ws://localhost:8080/api/ws/plugin"),
                cfg.getString("backend.token", "change_me_plugin_token"),
                cfg.getInt("backend.reconnect-delay-seconds", 5),
                cfg.getInt("backend.heartbeat-seconds", 20),
                cfg.getBoolean("modules.authme.enabled", true),
                cfg.getString("connection.mode", "server"),
                cfg.getString("server.host", "0.0.0.0"),
                cfg.getInt("server.port", 8082),
                cfg.getString("server.path", "/ws"),
                cfg.getBoolean("settings.quiet_mode", false));
    }
}