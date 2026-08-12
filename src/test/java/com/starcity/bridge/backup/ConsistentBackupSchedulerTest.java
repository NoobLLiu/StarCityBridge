package com.starcity.bridge.backup;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

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
}
