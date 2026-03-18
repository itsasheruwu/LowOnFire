package dev.ash.lowonfire;

import java.util.UUID;

@FunctionalInterface
public interface BedrockDetector {
    BedrockDetector NONE = uuid -> false;

    boolean isBedrockPlayer(UUID uuid);
}
