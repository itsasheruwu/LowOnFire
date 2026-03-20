package dev.ash.lowonfire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BedrockLowFirePreferenceStore {
    private final Path enabledPlayersFile;
    private final Set<UUID> enabledPlayers = new HashSet<>();

    public BedrockLowFirePreferenceStore(final Path dataDirectory) {
        this.enabledPlayersFile = dataDirectory.resolve("enabled-bedrock-low-fire.txt");
    }

    public void load() throws IOException {
        this.enabledPlayers.clear();
        if (!Files.exists(this.enabledPlayersFile)) {
            return;
        }

        for (final String line : Files.readAllLines(this.enabledPlayersFile)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                this.enabledPlayers.add(UUID.fromString(trimmed));
            } catch (final IllegalArgumentException ignored) {
            }
        }
    }

    public boolean isEnabled(final UUID uuid) {
        return this.enabledPlayers.contains(uuid);
    }

    public boolean setEnabled(final UUID uuid, final boolean enabled) throws IOException {
        final boolean changed;
        if (enabled) {
            changed = this.enabledPlayers.add(uuid);
        } else {
            changed = this.enabledPlayers.remove(uuid);
        }

        if (changed) {
            save();
        }
        return changed;
    }

    private void save() throws IOException {
        Files.createDirectories(this.enabledPlayersFile.getParent());
        final List<String> lines = this.enabledPlayers.stream()
            .map(UUID::toString)
            .sorted()
            .toList();
        Files.write(this.enabledPlayersFile, lines);
    }
}
