package com.starcity.bridge.backup;

import java.time.Duration;
import java.time.Instant;

final class BackupDeadlineCalculator {

    private BackupDeadlineCalculator() {
    }

    static Instant nextBackupAt(
            Instant now,
            Instant lastVerified,
            Instant lastAttempt,
            Duration interval,
            Duration retry) {
        Instant dueFromVerified = lastVerified == null ? now.plus(interval) : lastVerified.plus(interval);
        Instant dueFromAttempt = lastAttempt == null ? dueFromVerified : lastAttempt.plus(retry);
        Instant due = dueFromVerified.isAfter(dueFromAttempt) ? dueFromVerified : dueFromAttempt;
        return due.isAfter(now) ? due : now;
    }
}
