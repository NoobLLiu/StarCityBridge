package com.starcity.bridge.backup;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConsistentBackupSchedulerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime DAILY_TIME = LocalTime.of(6, 0);
    private static final Duration RETRY = Duration.ofHours(1);

    @Test
    void schedulesTodayAtSixWhenStartingAtFive() {
        assertEquals(
                Instant.parse("2026-08-11T22:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        Instant.parse("2026-08-11T21:00:00Z"),
                        DAILY_TIME,
                        SHANGHAI,
                        null,
                        null,
                        RETRY));
    }

    @Test
    void schedulesTomorrowAtSixWhenStartingAfterSix() {
        assertEquals(
                Instant.parse("2026-08-12T22:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        Instant.parse("2026-08-11T22:00:01Z"),
                        DAILY_TIME,
                        SHANGHAI,
                        null,
                        null,
                        RETRY));
    }

    @Test
    void runsAtExactSixBoundary() {
        assertEquals(
                Instant.parse("2026-08-11T22:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        Instant.parse("2026-08-11T22:00:00Z"),
                        DAILY_TIME,
                        SHANGHAI,
                        null,
                        null,
                        RETRY));
    }

    @Test
    void failedAttemptRetriesOneHourLater() {
        assertEquals(
                Instant.parse("2026-08-11T23:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        Instant.parse("2026-08-11T22:05:00Z"),
                        DAILY_TIME,
                        SHANGHAI,
                        Instant.parse("2026-08-11T20:00:00Z"),
                        Instant.parse("2026-08-11T22:00:00Z"),
                        RETRY));
    }

    @Test
    void verifiedAttemptUsesNextDailyScheduleInsteadOfRetry() {
        assertEquals(
                Instant.parse("2026-08-12T22:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        Instant.parse("2026-08-11T22:05:00Z"),
                        DAILY_TIME,
                        SHANGHAI,
                        Instant.parse("2026-08-11T22:03:00Z"),
                        Instant.parse("2026-08-11T22:00:00Z"),
                        RETRY));
    }

    @Test
    void overdueRetryRunsNowWithoutCreatingRestartLoop() {
        Instant now = Instant.parse("2026-08-11T23:30:00Z");
        assertEquals(
                now,
                BackupDeadlineCalculator.nextBackupAt(
                        now,
                        DAILY_TIME,
                        SHANGHAI,
                        Instant.parse("2026-08-11T20:00:00Z"),
                        Instant.parse("2026-08-11T22:00:00Z"),
                        RETRY));
    }

    @Test
    void retryNeverRunsAfterTheNextDailyDeadline() {
        assertEquals(
                Instant.parse("2026-08-12T22:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        Instant.parse("2026-08-12T21:30:00Z"),
                        DAILY_TIME,
                        SHANGHAI,
                        Instant.parse("2026-08-11T20:00:00Z"),
                        Instant.parse("2026-08-12T21:15:00Z"),
                        RETRY));
    }

    @Test
    void dailyBackupKickMessageStatesTheExpectedMaintenanceWindow() {
        assertEquals(
                "服务器每日6:00进行停服备份，约30分钟后可进入",
                ConsistentBackupScheduler.dailyBackupKickMessage());
    }

    @Test
    void handoffUsesTheWorldContainerAsTheCanonicalServerRoot() throws Exception {
        assertEquals(
                new File("D:\\java-server\\StarCIty").getCanonicalPath(),
                ConsistentBackupScheduler.canonicalServerRoot(
                        new File("D:\\java-server\\StarCIty")));
    }

    @Test
    void datedSnapshotWrittenBesideColdRecoveryRootCountsAsVerified() throws Exception {
        Path parent = Files.createTempDirectory("starcity-backup");
        Path backupRoot = parent.resolve("StarCity-ColdRecovery");
        Path datedSnapshot = parent.resolve("2026-8-18");
        Files.createDirectories(backupRoot);
        Files.createDirectories(datedSnapshot);
        Files.writeString(
                datedSnapshot.resolve("SNAPSHOT_COMPLETE.txt"),
                "verified",
                StandardCharsets.UTF_8);
        Files.writeString(
                datedSnapshot.resolve("snapshot-manifest.json"),
                "{\"HostTimestamp\":\"2026-08-17T19:15:00Z\"}",
                StandardCharsets.UTF_8);

        assertEquals(
                Instant.parse("2026-08-17T19:15:00Z"),
                VerifiedSnapshotLocator.readLatest(backupRoot));
    }

    @Test
    void newestVerifiedSnapshotWinsAcrossCurrentAndDatedLayouts() throws Exception {
        Path parent = Files.createTempDirectory("starcity-backup");
        Path backupRoot = parent.resolve("StarCity-ColdRecovery");
        Path current = backupRoot.resolve("current");
        Path datedSnapshot = parent.resolve("2026-8-18");
        Files.createDirectories(current);
        Files.createDirectories(datedSnapshot);
        for (Path directory : new Path[]{current, datedSnapshot}) {
            Files.writeString(
                    directory.resolve("SNAPSHOT_COMPLETE.txt"),
                    "verified",
                    StandardCharsets.UTF_8);
        }
        Files.writeString(
                current.resolve("snapshot-manifest.json"),
                "{\"HostTimestamp\":\"2026-08-17T19:10:00Z\"}",
                StandardCharsets.UTF_8);
        Files.writeString(
                datedSnapshot.resolve("snapshot-manifest.json"),
                "{\"HostTimestamp\":\"2026-08-17T19:20:00Z\"}",
                StandardCharsets.UTF_8);

        assertEquals(
                Instant.parse("2026-08-17T19:20:00Z"),
                VerifiedSnapshotLocator.readLatest(backupRoot));
    }
}
