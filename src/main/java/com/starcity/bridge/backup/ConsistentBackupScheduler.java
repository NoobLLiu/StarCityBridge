package com.starcity.bridge.backup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Schedules authoritative online saves and consistent stopped-server snapshots.
 *
 * <p>The plugin never copies a live world. At the backup deadline it performs
 * a synchronous Bukkit save and requests a normal server shutdown. The launcher
 * creates and validates the cold snapshot only after all plugins and databases
 * have closed.</p>
 */
public final class ConsistentBackupScheduler {

    private static final long TICKS_PER_MINUTE = 20L * 60L;
    private static final long CHECK_PERIOD_TICKS = 20L * 15L;
    private static final ZoneId BACKUP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DAILY_BACKUP_KICK_MESSAGE =
            "服务器每日6:00进行停服备份，约30分钟后可进入";

    private final StarCityBridge plugin;
    private final PluginConfig config;
    private final Path backupRoot;
    private final Path manifestPath;
    private final Path completeMarkerPath;
    private final Path attemptPath;
    private final Path cleanShutdownHandoffPath;
    private final Path schedulerLogPath;

    private BukkitTask autosaveTask;
    private BukkitTask deadlineTask;
    private Instant nextBackupAt;
    private Instant lastAttemptAt;
    private boolean warnedFiveMinutes;
    private boolean warnedOneMinute;
    private boolean shutdownRequested;

    public ConsistentBackupScheduler(StarCityBridge plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.backupRoot = Path.of(config.coldSnapshotRoot()).toAbsolutePath().normalize();
        this.manifestPath = backupRoot.resolve("current").resolve("snapshot-manifest.json");
        this.completeMarkerPath = backupRoot.resolve("current").resolve("SNAPSHOT_COMPLETE.txt");
        this.attemptPath = backupRoot.resolve("last-backup-attempt.txt");
        this.cleanShutdownHandoffPath = backupRoot.resolve("pending-clean-shutdown.json");
        this.schedulerLogPath = backupRoot.resolve("backup-scheduler.log");
    }

    public void start() {
        if (!config.consistentBackupEnabled()) {
            plugin.getLogger().info("一致性备份调度未启用。");
            return;
        }

        try {
            Files.createDirectories(backupRoot);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法创建一致性备份目录，调度未启动: " + backupRoot, exception);
            return;
        }

        lastAttemptAt = readTimestamp(attemptPath);
        nextBackupAt = calculateNextBackupAt(Instant.now());
        logEvent("START next=" + nextBackupAt + " dailyTime=" + config.backupDailyTime()
                + " zone=" + BACKUP_ZONE);

        long autosaveTicks = Math.multiplyExact((long) config.autosaveIntervalMinutes(), TICKS_PER_MINUTE);
        autosaveTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> saveAll("periodic-autosave"),
                autosaveTicks,
                autosaveTicks);
        deadlineTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::checkDeadline,
                CHECK_PERIOD_TICKS,
                CHECK_PERIOD_TICKS);

        plugin.getLogger().info(
                "一致性备份已启用：每天北京时间 " + config.backupDailyTime()
                        + " 正常停服冷备；每 " + config.autosaveIntervalMinutes()
                        + " 分钟完整保存。下次备份约 " + formatLocal(nextBackupAt));
    }

    public void stop() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (deadlineTask != null) {
            deadlineTask.cancel();
        }
    }

    private Instant calculateNextBackupAt(Instant now) {
        Instant lastVerified = readVerifiedSnapshotTimestamp();
        return BackupDeadlineCalculator.nextBackupAt(
                now,
                config.backupDailyTime(),
                BACKUP_ZONE,
                lastVerified,
                lastAttemptAt,
                Duration.ofMinutes(config.backupRetryMinutes()));
    }

    private void checkDeadline() {
        if (shutdownRequested) {
            return;
        }

        Instant now = Instant.now();
        Duration remaining = Duration.between(now, nextBackupAt);
        long seconds = remaining.getSeconds();

        if (!warnedFiveMinutes && seconds <= 300 && seconds > 60) {
            warnedFiveMinutes = true;
            broadcast("§e[系统备份] 服务器将在约 5 分钟后进行完整保存和一致性备份，备份后会自动重新开放。");
        }
        if (!warnedOneMinute && seconds <= 60 && seconds > 0) {
            warnedOneMinute = true;
            broadcast("§6[系统备份] 服务器将在约 1 分钟后保存并重启，请暂时停止交易、整理容器和机器操作。");
        }
        if (!now.isBefore(nextBackupAt)) {
            requestConsistentBackup();
        }
    }

    private void requestConsistentBackup() {
        shutdownRequested = true;
        Instant attempt = Instant.now();

        try {
            writeTimestamp(attemptPath, attempt);
            lastAttemptAt = attempt;
            logEvent("ATTEMPT started=" + attempt);
        } catch (IOException exception) {
            shutdownRequested = false;
            warnedFiveMinutes = false;
            warnedOneMinute = false;
            nextBackupAt = attempt.plus(Duration.ofMinutes(config.backupRetryMinutes()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "无法写入备份尝试状态；为防止停服循环，本次不会关闭服务器。下次重试约 "
                            + formatLocal(nextBackupAt),
                    exception);
            return;
        }

        broadcast("§c[系统备份] 正在完整保存服务器，保存完成后将暂时断开并自动重新开放。");
        try {
            saveAll("consistent-backup");
            writeCleanShutdownHandoff(attempt);
            logEvent("KICK_FOR_BACKUP players=" + kickPlayersForDailyBackup());
            logEvent("SAVE_OK requestedStop=" + Instant.now());
            plugin.getLogger().info("一致性备份保存完成，正在正常关闭服务器以制作冷镜像。");
            Bukkit.shutdown();
        } catch (Exception exception) {
            shutdownRequested = false;
            warnedFiveMinutes = false;
            warnedOneMinute = false;
            nextBackupAt = attempt.plus(Duration.ofMinutes(config.backupRetryMinutes()));
            logEvent("SAVE_FAILED retry=" + nextBackupAt + " error=" + exception.getClass().getSimpleName());
            plugin.getLogger().log(
                    Level.SEVERE,
                    "一致性备份保存失败；服务器保持运行，下次重试约 " + formatLocal(nextBackupAt),
                    exception);
            broadcast("§c[系统备份] 保存失败，本次未关闭服务器；旧的有效备份不会被覆盖。");
        }
    }

    /**
     * Hands the stopped-server snapshot script a short-lived proof that the
     * current scheduled cycle completed {@code save-all flush} before asking
     * Paper to stop. The launcher removes any leftover handoff before every
     * new server process, so an unexpected exit can never reuse old proof.
     */
    private void writeCleanShutdownHandoff(Instant attempt) throws IOException {
        JsonObject handoff = new JsonObject();
        handoff.addProperty("formatVersion", 1);
        handoff.addProperty("serverRoot", canonicalServerRoot(Bukkit.getWorldContainer()));
        handoff.addProperty("attemptedAtUtc", attempt.toString());
        handoff.addProperty("saveCompletedAtUtc", Instant.now().toString());
        handoff.addProperty("nonce", UUID.randomUUID().toString());

        Files.createDirectories(backupRoot);
        Path temporary = backupRoot.resolve(
                cleanShutdownHandoffPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(
                    temporary,
                    handoff.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        cleanShutdownHandoffPath,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        cleanShutdownHandoffPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        Bukkit.getLogger().info("COLD_BACKUP_HANDOFF nonce=" + handoff.get("nonce").getAsString());
        logEvent("SHUTDOWN_AUTHORIZED handoff=" + cleanShutdownHandoffPath + " at=" + Instant.now());
    }

    private void saveAll(String reason) {
        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        if (!accepted) {
            throw new IllegalStateException("Paper rejected save-all flush");
        }
        logEvent("SAVE reason=" + reason + " players=" + Bukkit.getOnlinePlayers().size()
                + " worlds=" + Bukkit.getWorlds().size() + " at=" + Instant.now());
    }

    private static int kickPlayersForDailyBackup() {
        int kicked = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kickPlayer(DAILY_BACKUP_KICK_MESSAGE);
            kicked++;
        }
        return kicked;
    }

    static String dailyBackupKickMessage() {
        return DAILY_BACKUP_KICK_MESSAGE;
    }

    static String canonicalServerRoot(java.io.File worldContainer) throws IOException {
        return worldContainer.getCanonicalPath();
    }

    private Instant readVerifiedSnapshotTimestamp() {
        if (!Files.isRegularFile(completeMarkerPath) || !Files.isRegularFile(manifestPath)) {
            logEvent("NO_VERIFIED_SNAPSHOT current marker/manifest missing");
            return null;
        }
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            JsonObject manifest = JsonParser.parseReader(reader).getAsJsonObject();
            if (!manifest.has("HostTimestamp")) {
                return null;
            }
            return OffsetDateTime.parse(manifest.get("HostTimestamp").getAsString()).toInstant();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "无法读取当前冷镜像时间，将从本次启动后重新计时。", exception);
            return null;
        }
    }

    private static Instant readTimestamp(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return Instant.parse(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeTimestamp(Path path, Instant timestamp) throws IOException {
        Files.writeString(
                path,
                timestamp.toString() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private void logEvent(String text) {
        try {
            Files.createDirectories(backupRoot);
            Files.writeString(
                    schedulerLogPath,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()) + " " + text
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "无法写入一致性备份调度日志。", exception);
        }
    }

    private static String formatLocal(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
                .withZone(BACKUP_ZONE)
                .format(instant);
    }

    private static void broadcast(String message) {
        Bukkit.broadcastMessage(message);
    }
}
