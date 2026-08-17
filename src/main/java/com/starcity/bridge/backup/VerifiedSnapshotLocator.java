package com.starcity.bridge.backup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locates the newest verified cold snapshot written by the launcher.
 *
 * <p>The launcher keeps dated snapshots for retention. Older deployments also
 * used a {@code current} directory, so both layouts remain readable during the
 * path cutover.</p>
 */
final class VerifiedSnapshotLocator {

    private static final Pattern DATE_DIRECTORY =
            Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");

    private VerifiedSnapshotLocator() {
    }

    static Instant readLatest(Path backupRoot) {
        Set<Path> roots = new TreeSet<>(Comparator.comparing(Path::toString));
        roots.add(backupRoot);
        Path parent = backupRoot.getParent();
        if (parent != null) {
            roots.add(parent);
        }

        Instant latest = null;
        for (Path root : roots) {
            latest = newer(latest, readManifestTimestamp(root.resolve("current")));
            try (Stream<Path> children = Files.list(root)) {
                for (Path child : children
                        .filter(Files::isDirectory)
                        .filter(path -> DATE_DIRECTORY.matcher(path.getFileName().toString()).matches())
                        .toList()) {
                    latest = newer(latest, readManifestTimestamp(child));
                }
            } catch (IOException ignored) {
                // A missing/unreadable retention root is not a verified snapshot.
            }
        }
        return latest;
    }

    private static Instant readManifestTimestamp(Path snapshotDirectory) {
        Path marker = snapshotDirectory.resolve("SNAPSHOT_COMPLETE.txt");
        Path manifest = snapshotDirectory.resolve("snapshot-manifest.json");
        if (!Files.isRegularFile(marker) || !Files.isRegularFile(manifest)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            if (!object.has("HostTimestamp")) {
                return null;
            }
            return OffsetDateTime.parse(object.get("HostTimestamp").getAsString()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant newer(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
