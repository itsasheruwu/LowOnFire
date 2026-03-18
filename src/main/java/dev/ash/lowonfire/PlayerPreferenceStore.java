package dev.ash.lowonfire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PlayerPreferenceStore {
    private final Path disabledPlayersFile;
    private final Set<UUID> disabledPlayers = new HashSet<>();

    public PlayerPreferenceStore(final Path dataDirectory) {
        this.disabledPlayersFile = dataDirectory.resolve("disabled-java-pack.txt");
    }

    public void load() throws IOException {
        this.disabledPlayers.clear();
        if (!Files.exists(this.disabledPlayersFile)) {
            return;
        }

        for (final String line : Files.readAllLines(this.disabledPlayersFile)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                this.disabledPlayers.add(UUID.fromString(trimmed));
            } catch (final IllegalArgumentException ignored) {
            }
        }
    }

    public boolean isEnabled(final UUID uuid) {
        return !this.disabledPlayers.contains(uuid);
    }

    public boolean setEnabled(final UUID uuid, final boolean enabled) throws IOException {
        final boolean changed;
        if (enabled) {
            changed = this.disabledPlayers.remove(uuid);
        } else {
            changed = this.disabledPlayers.add(uuid);
        }

        if (changed) {
            save();
        }
        return changed;
    }

    private void save() throws IOException {
        Files.createDirectories(this.disabledPlayersFile.getParent());
        final List<String> lines = this.disabledPlayers.stream()
            .map(UUID::toString)
            .sorted()
            .toList();
        Files.write(this.disabledPlayersFile, lines);
    }
}
