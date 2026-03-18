package dev.ash.lowonfire;

import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;

public final class FloodgateBedrockDetector implements BedrockDetector {
    @Override
    public boolean isBedrockPlayer(final UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }
}
