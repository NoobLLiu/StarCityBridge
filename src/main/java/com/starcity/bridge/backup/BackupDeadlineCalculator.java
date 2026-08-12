package com.starcity.bridge.backup;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class BackupDeadlineCalculator {

    private BackupDeadlineCalculator() {
    }

    static Instant nextBackupAt(
            Instant now,
            LocalTime dailyTime,
            ZoneId zone,
            Instant lastVerified,
            Instant lastAttempt,
            Duration retry) {
        ZonedDateTime localNow = now.atZone(zone);
        Instant scheduled = localNow.toLocalDate().atTime(dailyTime).atZone(zone).toInstant();
        if (scheduled.isBefore(now)) {
            scheduled = localNow.toLocalDate().plusDays(1).atTime(dailyTime).atZone(zone).toInstant();
        }

        boolean unverifiedAttempt = lastAttempt != null
                && (lastVerified == null || lastVerified.isBefore(lastAttempt));
        if (!unverifiedAttempt) {
            return scheduled;
        }

        Instant retryAt = lastAttempt.plus(retry);
        if (retryAt.isBefore(now)) {
            retryAt = now;
        }
        return retryAt.isBefore(scheduled) ? retryAt : scheduled;
    }
}
