package com.starcity.bridge.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

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
        boolean quietMode,
        boolean consistentBackupEnabled,
        int autosaveIntervalMinutes,
        LocalTime backupDailyTime,
        int backupRetryMinutes,
        String coldSnapshotRoot) {

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
                cfg.getBoolean("settings.quiet_mode", false),
                cfg.getBoolean("consistent-backup.enabled", false),
                Math.max(1, cfg.getInt("consistent-backup.autosave-interval-minutes", 20)),
                parseDailyTime(cfg.getString("consistent-backup.daily-time", "06:00")),
                Math.max(5, cfg.getInt("consistent-backup.retry-minutes", 60)),
                cfg.getString("consistent-backup.cold-snapshot-root", "E:\\StarCity-Backup\\StarCity-ColdRecovery"));
    }

    private static LocalTime parseDailyTime(String value) {
        try {
            return LocalTime.parse(value == null ? "06:00" : value.trim());
        } catch (DateTimeParseException ignored) {
            return LocalTime.of(6, 0);
        }
    }
}
