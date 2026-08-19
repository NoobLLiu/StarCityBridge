package com.starcity.bridge.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 插件配置：从 config.yml 读取模块开关、一致性备份与网页后端（HTTP REST）配置。
 */
public record PluginConfig(
        boolean authMeEnabled,
        boolean quietMode,
        boolean consistentBackupEnabled,
        int autosaveIntervalMinutes,
        LocalTime backupDailyTime,
        int backupRetryMinutes,
        String coldSnapshotRoot,
        boolean webApiEnabled,
        String webApiHost,
        int webApiPort,
        String webApiTokenSecret,
        int webApiTokenTtlSeconds,
        String webApiAdminToken,
        String webApiCorsOrigin) {

    public static PluginConfig from(FileConfiguration cfg) {
        return new PluginConfig(
                cfg.getBoolean("modules.authme.enabled", true),
                cfg.getBoolean("settings.quiet_mode", false),
                cfg.getBoolean("consistent-backup.enabled", false),
                Math.max(1, cfg.getInt("consistent-backup.autosave-interval-minutes", 20)),
                parseDailyTime(cfg.getString("consistent-backup.daily-time", "06:00")),
                Math.max(5, cfg.getInt("consistent-backup.retry-minutes", 60)),
                cfg.getString("consistent-backup.cold-snapshot-root", "E:\\StarCity-Backup\\StarCity-ColdRecovery"),
                cfg.getBoolean("web-api.enabled", true),
                cfg.getString("web-api.host", "0.0.0.0"),
                Math.max(1, cfg.getInt("web-api.port", 8083)),
                cfg.getString("web-api.token-secret", "change_me_web_api_secret"),
                Math.max(60, cfg.getInt("web-api.token-ttl-seconds", 3600)),
                cfg.getString("web-api.admin-token", "change_me_admin_token"),
                cfg.getString("web-api.cors-origin", "*"));
    }

    private static LocalTime parseDailyTime(String value) {
        try {
            return LocalTime.parse(value == null ? "06:00" : value.trim());
        } catch (DateTimeParseException ignored) {
            return LocalTime.of(6, 0);
        }
    }
}