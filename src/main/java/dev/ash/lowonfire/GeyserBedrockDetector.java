package dev.ash.lowonfire;

import java.util.UUID;
import org.geysermc.geyser.api.GeyserApi;

public final class GeyserBedrockDetector implements BedrockDetector {
    @Override
    public boolean isBedrockPlayer(final UUID uuid) {
        final GeyserApi api = GeyserApi.api();
        return api != null && api.isBedrockPlayer(uuid);
    }
}
