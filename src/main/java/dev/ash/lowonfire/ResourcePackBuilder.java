package dev.ash.lowonfire;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

public final class ResourcePackBuilder {
    private static final int FRAME_SIZE = 16;
    private static final int ICON_SIZE = 64;
    private static final String JAVA_ANIMATION_META = "{\"animation\":{\"frametime\":2}}";
    private static final int WORLD_FIRE_UV_TOP = 8;
    private static final String JAVA_PACK_META = """
        {
          "pack": {
            "pack_format": 75,
            "min_format": 75,
            "max_format": 75,
            "description": "LowOnFire lowers the fire overlay."
          }
        }
        """;
    private static final String FIRE_SIDE_MODEL = """
        {
          "textures": {
            "particle": "#fire"
          },
          "ambientocclusion": false,
          "elements": [
            {
              "from": [0, 0, 0.01],
              "to": [16, 22.4, 0.01],
              "shade": false,
              "faces": {
                "south": { "uv": [0, %d, 16, 16], "texture": "#fire" },
                "north": { "uv": [0, %d, 16, 16], "texture": "#fire" }
              }
            }
          ]
        }
        """.formatted(WORLD_FIRE_UV_TOP, WORLD_FIRE_UV_TOP);
    private static final String FIRE_FLOOR_MODEL = """
        {
          "textures": {
            "particle": "#fire"
          },
          "ambientocclusion": false,
          "elements": [
            {
              "from": [0, 0, 8.8],
              "to": [16, 22.4, 8.8],
              "rotation": { "origin": [8, 8, 8], "axis": "x", "angle": -22.5, "rescale": true },
              "shade": false,
              "faces": { "south": { "uv": [0, %d, 16, 16], "texture": "#fire" } }
            },
            {
              "from": [0, 0, 7.2],
              "to": [16, 22.4, 7.2],
              "rotation": { "origin": [8, 8, 8], "axis": "x", "angle": 22.5, "rescale": true },
              "shade": false,
              "faces": { "north": { "uv": [0, %d, 16, 16], "texture": "#fire" } }
            },
            {
              "from": [8.8, 0, 0],
              "to": [8.8, 22.4, 16],
              "rotation": { "origin": [8, 8, 8], "axis": "z", "angle": -22.5, "rescale": true },
              "shade": false,
              "faces": { "west": { "uv": [0, %d, 16, 16], "texture": "#fire" } }
            },
            {
              "from": [7.2, 0, 0],
              "to": [7.2, 22.4, 16],
              "rotation": { "origin": [8, 8, 8], "axis": "z", "angle": 22.5, "rescale": true },
              "shade": false,
              "faces": { "east": { "uv": [0, %d, 16, 16], "texture": "#fire" } }
            }
          ]
        }
        """.formatted(WORLD_FIRE_UV_TOP, WORLD_FIRE_UV_TOP, WORLD_FIRE_UV_TOP, WORLD_FIRE_UV_TOP);
    private static final String FIRE_UP_MODEL = """
        {
          "textures": {
            "particle": "#fire"
          },
          "ambientocclusion": false,
          "elements": [
            {
              "from": [0, 16, 0],
              "to": [16, 16, 16],
              "rotation": { "origin": [16, 16, 8], "axis": "z", "angle": 22.5, "rescale": true },
              "shade": false,
              "faces": { "down": { "uv": [0, %d, 16, 16], "texture": "#fire", "rotation": 270 } }
            },
            {
              "from": [0, 16, 0],
              "to": [16, 16, 16],
              "rotation": { "origin": [0, 16, 8], "axis": "z", "angle": -22.5, "rescale": true },
              "shade": false,
              "faces": { "down": { "uv": [0, %d, 16, 16], "texture": "#fire", "rotation": 90 } }
            }
          ]
        }
        """.formatted(WORLD_FIRE_UV_TOP, WORLD_FIRE_UV_TOP);

    private ResourcePackBuilder() {
    }

    public static GeneratedPacks build(final Path dataDirectory, final double heightScale) throws IOException {
        Files.createDirectories(dataDirectory);
        final Path packDirectory = dataDirectory.resolve("generated-packs");
        Files.createDirectories(packDirectory);

        final Palette normalPalette = new Palette(
            new Color(255, 250, 188),
            new Color(255, 156, 63),
            new Color(166, 27, 18)
        );
        final Palette soulPalette = new Palette(
            new Color(213, 251, 255),
            new Color(79, 204, 255),
            new Color(14, 68, 161)
        );

        final byte[] javaFire0 = createAnimatedFireTexture(normalPalette, heightScale, 0);
        final byte[] javaFire1 = createAnimatedFireTexture(normalPalette, heightScale, 1);
        final byte[] javaSoul0 = createAnimatedFireTexture(soulPalette, heightScale, 2);
        final byte[] javaSoul1 = createAnimatedFireTexture(soulPalette, heightScale, 3);

        final byte[] bedrockFire0 = createStaticFireTexture(normalPalette, heightScale, 0);
        final byte[] bedrockFire1 = createStaticFireTexture(normalPalette, heightScale, 1);
        final byte[] bedrockSoul0 = createStaticFireTexture(soulPalette, heightScale, 2);
        final byte[] bedrockSoul1 = createStaticFireTexture(soulPalette, heightScale, 3);
        final byte[] icon = createPackIcon(normalPalette);

        final PackArtifact javaPack = writeJavaPack(packDirectory.resolve("low-on-fire-java.zip"), icon, javaFire0, javaFire1, javaSoul0, javaSoul1);
        final PackArtifact bedrockPack = writeBedrockPack(packDirectory.resolve("low-on-fire-bedrock.mcpack"), icon, bedrockFire0, bedrockFire1, bedrockSoul0, bedrockSoul1);

        return new GeneratedPacks(javaPack, bedrockPack);
    }

    private static PackArtifact writeJavaPack(
        final Path target,
        final byte[] icon,
        final byte[] fire0,
        final byte[] fire1,
        final byte[] soul0,
        final byte[] soul1
    ) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(target);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            writeZipEntry(zipOutputStream, "pack.mcmeta", JAVA_PACK_META.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "pack.png", icon);
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/fire_0.png", fire0);
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/fire_1.png", fire1);
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/soul_fire_0.png", soul0);
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/soul_fire_1.png", soul1);
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/fire_0.png.mcmeta", JAVA_ANIMATION_META.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/fire_1.png.mcmeta", JAVA_ANIMATION_META.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/soul_fire_0.png.mcmeta", JAVA_ANIMATION_META.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "assets/minecraft/textures/block/soul_fire_1.png.mcmeta", JAVA_ANIMATION_META.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "assets/minecraft/models/block/template_fire_side.json", FIRE_SIDE_MODEL.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "assets/minecraft/models/block/template_fire_floor.json", FIRE_FLOOR_MODEL.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "assets/minecraft/models/block/template_fire_up.json", FIRE_UP_MODEL.getBytes(StandardCharsets.UTF_8));
        }

        return artifact(target);
    }

    private static PackArtifact writeBedrockPack(
        final Path target,
        final byte[] icon,
        final byte[] fire0,
        final byte[] fire1,
        final byte[] soul0,
        final byte[] soul1
    ) throws IOException {
        final UUID headerUuid = UUID.nameUUIDFromBytes("LowOnFire-Bedrock".getBytes(StandardCharsets.UTF_8));
        final UUID moduleUuid = UUID.nameUUIDFromBytes("LowOnFire-Bedrock-Resources".getBytes(StandardCharsets.UTF_8));
        final String manifest = """
            {
              "format_version": 2,
              "header": {
                "name": "LowOnFire Bedrock",
                "description": "Lower fire overlay for Geyser players.",
                "uuid": "%s",
                "version": [1, 0, 0],
                "min_engine_version": [1, 21, 0]
              },
              "modules": [
                {
                  "type": "resources",
                  "uuid": "%s",
                  "version": [1, 0, 0],
                  "description": "LowOnFire generated Bedrock resources."
                }
              ]
            }
            """.formatted(headerUuid, moduleUuid);

        try (OutputStream outputStream = Files.newOutputStream(target);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            writeZipEntry(zipOutputStream, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zipOutputStream, "pack_icon.png", icon);
            writeZipEntry(zipOutputStream, "textures/blocks/fire_0.png", fire0);
            writeZipEntry(zipOutputStream, "textures/blocks/fire_1.png", fire1);
            writeZipEntry(zipOutputStream, "textures/blocks/soul_fire_0.png", soul0);
            writeZipEntry(zipOutputStream, "textures/blocks/soul_fire_1.png", soul1);
        }

        return artifact(target);
    }

    private static void writeZipEntry(final ZipOutputStream zipOutputStream, final String path, final byte[] content) throws IOException {
        final ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zipOutputStream.putNextEntry(entry);
        zipOutputStream.write(content);
        zipOutputStream.closeEntry();
    }

    private static PackArtifact artifact(final Path path) throws IOException {
        final byte[] bytes = Files.readAllBytes(path);
        return new PackArtifact(path, bytes, sha1(bytes));
    }

    private static byte[] createAnimatedFireTexture(final Palette palette, final double heightScale, final int seedOffset) throws IOException {
        final BufferedImage image = new BufferedImage(FRAME_SIZE, FRAME_SIZE * 2, BufferedImage.TYPE_INT_ARGB);
        drawFireFrame(image, 0, palette, heightScale, seedOffset * 17L + 5L);
        drawFireFrame(image, FRAME_SIZE, palette, heightScale, seedOffset * 17L + 13L);
        return encodePng(image);
    }

    private static byte[] createStaticFireTexture(final Palette palette, final double heightScale, final int seedOffset) throws IOException {
        final BufferedImage image = new BufferedImage(FRAME_SIZE, FRAME_SIZE, BufferedImage.TYPE_INT_ARGB);
        drawFireFrame(image, 0, palette, heightScale, seedOffset * 17L + 5L);
        return encodePng(image);
    }

    private static byte[] createPackIcon(final Palette palette) throws IOException {
        final BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(28, 18, 11));
        graphics.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 18, 18);
        graphics.setColor(palette.shadow());
        graphics.fillRoundRect(8, 18, 48, 34, 16, 16);
        graphics.setColor(palette.mid());
        graphics.fillOval(16, 14, 32, 32);
        graphics.setColor(palette.highlight());
        graphics.fillOval(22, 8, 18, 18);
        graphics.dispose();
        return encodePng(image);
    }

    private static void drawFireFrame(
        final BufferedImage image,
        final int yOffset,
        final Palette palette,
        final double heightScale,
        final long seed
    ) {
        final double clampedScale = Math.max(0.15D, Math.min(1.0D, heightScale));
        final double[] flameHeights = new double[FRAME_SIZE];

        for (int x = 0; x < FRAME_SIZE; x++) {
            final double wave = 1.2D * Math.sin((x + seed * 0.15D) * 0.9D);
            final double detail = 0.7D * Math.cos((x * 0.65D) + seed);
            final double height = (3.8D + wave + detail) * clampedScale + 2.2D;
            flameHeights[x] = Math.max(2.0D, Math.min(9.5D, height));
        }

        for (int x = 0; x < FRAME_SIZE; x++) {
            final double flameHeight = flameHeights[x];
            final double topY = FRAME_SIZE - flameHeight;
            for (int y = 0; y < FRAME_SIZE; y++) {
                final double normalizedY = (y - topY) / Math.max(1.0D, flameHeight);
                if (normalizedY < -0.18D) {
                    continue;
                }

                double alpha = normalizedY <= 0.0D ? 0.10D + (normalizedY + 0.18D) * 0.45D : 0.30D + normalizedY * 0.70D;
                alpha *= 1.0D - (Math.abs(x - 7.5D) / 11.5D);
                alpha = Math.max(0.0D, Math.min(1.0D, alpha));
                if (alpha <= 0.02D) {
                    continue;
                }

                final double colorMix = Math.max(0.0D, Math.min(1.0D, normalizedY * 0.85D));
                final Color color = blend(
                    blend(palette.highlight(), palette.mid(), Math.min(1.0D, colorMix * 1.35D)),
                    palette.shadow(),
                    Math.max(0.0D, colorMix - 0.45D) * 1.4D
                );
                image.setRGB(x, y + yOffset, withAlpha(color, alpha).getRGB());
            }
        }
    }

    private static Color blend(final Color start, final Color end, final double amount) {
        final double clamped = Math.max(0.0D, Math.min(1.0D, amount));
        final int red = (int) Math.round(start.getRed() + (end.getRed() - start.getRed()) * clamped);
        final int green = (int) Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * clamped);
        final int blue = (int) Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * clamped);
        return new Color(red, green, blue);
    }

    private static Color withAlpha(final Color color, final double alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) Math.round(Math.max(0.0D, Math.min(1.0D, alpha)) * 255.0D));
    }

    private static byte[] encodePng(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }

    private static String sha1(final byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-1 implementation", exception);
        }
    }

    public record PackArtifact(Path path, byte[] bytes, String sha1) {
        public PackArtifact {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(sha1, "sha1");
        }
    }

    public record GeneratedPacks(PackArtifact javaPack, PackArtifact bedrockPack) {
        public GeneratedPacks {
            Objects.requireNonNull(javaPack, "javaPack");
            Objects.requireNonNull(bedrockPack, "bedrockPack");
        }
    }

    private record Palette(Color highlight, Color mid, Color shadow) {
    }
}
