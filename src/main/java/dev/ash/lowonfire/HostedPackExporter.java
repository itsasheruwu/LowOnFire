package dev.ash.lowonfire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HostedPackExporter {
    private HostedPackExporter() {
    }

    public static void main(final String[] args) throws IOException {
        final Path outputDirectory = args.length == 0
            ? Path.of("hosted")
            : Path.of(args[0]);

        Files.createDirectories(outputDirectory);

        final ResourcePackBuilder.GeneratedPacks packs = ResourcePackBuilder.build(outputDirectory.resolve(".staging"), 0.38D);
        Files.copy(packs.javaPack().path(), outputDirectory.resolve("low-on-fire.zip"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(packs.bedrockPack().path(), outputDirectory.resolve("low-on-fire-bedrock.mcpack"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        deleteDirectory(packs.javaPack().path().getParent());
    }

    private static void deleteDirectory(final Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (final IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        } catch (final RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }
}
