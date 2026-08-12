package com.starcity.bridge.backup;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConsistentBackupSchedulerTest {

    private static final Duration INTERVAL = Duration.ofHours(6);
    private static final Duration RETRY = Duration.ofHours(1);
    private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");

    @Test
    void schedulesFromLatestVerifiedSnapshot() {
        assertEquals(
                Instant.parse("2026-08-12T05:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        NOW,
                        Instant.parse("2026-08-11T23:00:00Z"),
                        null,
                        INTERVAL,
                        RETRY));
    }

    @Test
    void runsImmediatelyWhenVerifiedSnapshotIsOverdue() {
        assertEquals(
                NOW,
                BackupDeadlineCalculator.nextBackupAt(
                        NOW,
                        Instant.parse("2026-08-11T18:00:00Z"),
                        null,
                        INTERVAL,
                        RETRY));
    }

    @Test
    void failedAttemptDelaysRetryAndPreventsRestartLoop() {
        assertEquals(
                Instant.parse("2026-08-12T01:30:00Z"),
                BackupDeadlineCalculator.nextBackupAt(
                        NOW,
                        Instant.parse("2026-08-11T18:00:00Z"),
                        Instant.parse("2026-08-12T00:30:00Z"),
                        INTERVAL,
                        RETRY));
    }

    @Test
    void startsAFullIntervalLaterWhenNoSnapshotExists() {
        Instant now = Instant.parse("2026-08-12T01:00:00Z");
        assertEquals(
                Instant.parse("2026-08-12T07:00:00Z"),
                BackupDeadlineCalculator.nextBackupAt(now, null, null, INTERVAL, RETRY));
    }
}
