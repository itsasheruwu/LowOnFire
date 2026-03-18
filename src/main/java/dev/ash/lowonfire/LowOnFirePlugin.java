package dev.ash.lowonfire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class LowOnFirePlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private BedrockDetector bedrockDetector = BedrockDetector.NONE;
    private PackHttpServer packHttpServer;
    private ResourcePackBuilder.GeneratedPacks generatedPacks;
    private PackOffer packOffer;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        final PluginCommand command = this.getCommand("lowonfire");
        if (command != null) {
            command.setExecutor(this);
        }

        this.getServer().getPluginManager().registerEvents(this, this);
        reloadRuntime(true);

        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(this::offerJavaPackIfEligible));
    }

    @Override
    public void onDisable() {
        if (this.packHttpServer != null) {
            this.packHttpServer.stop();
            this.packHttpServer = null;
        }
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        if (!this.getConfig().getBoolean("java-pack.send-on-join", true)) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> offerJavaPackIfEligible(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onResourcePackStatus(final PlayerResourcePackStatusEvent event) {
        if (this.packOffer == null || !this.packOffer.id().equals(event.getID())) {
            return;
        }

        switch (event.getStatus()) {
            case DECLINED -> this.getLogger().info(event.getPlayer().getName() + " declined the LowOnFire resource pack.");
            case FAILED_DOWNLOAD -> this.getLogger().warning(
                event.getPlayer().getName()
                    + " failed to download the LowOnFire resource pack from "
                    + this.packOffer.url()
                    + ". Check java-pack.public-url, firewall/NAT rules for port "
                    + this.getConfig().getInt("java-pack.port", 25589)
                    + ", and whether the URL is reachable from the client."
            );
            default -> {
            }
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage(Component.text("LowOnFire", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Java pack: " + (this.packOffer == null ? "not sendable" : this.packOffer.url()), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Generated Java zip: " + pathOrMissing(this.generatedPacks == null ? null : this.generatedPacks.javaPack().path()), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Generated Bedrock mcpack: " + pathOrMissing(this.generatedPacks == null ? null : this.generatedPacks.bedrockPack().path()), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Bedrock detection: " + this.bedrockDetector.getClass().getSimpleName(), NamedTextColor.GRAY));
            return true;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            reloadRuntime(false);
            sender.sendMessage(Component.text("LowOnFire reloaded.", NamedTextColor.GREEN));
            return true;
        }

        if ("apply".equalsIgnoreCase(args[0])) {
            final Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
                    return true;
                }
            } else if (sender instanceof Player player) {
                target = player;
            } else {
                sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
                return true;
            }

            if (this.bedrockDetector.isBedrockPlayer(target.getUniqueId())) {
                sender.sendMessage(Component.text(target.getName() + " is a Bedrock player; LowOnFire skips the Java pack for Bedrock clients.", NamedTextColor.YELLOW));
                return true;
            }

            if (this.packOffer == null) {
                sender.sendMessage(Component.text("The Java pack is not sendable yet. Set java-pack.public-url or server-ip.", NamedTextColor.RED));
                return true;
            }

            sendJavaPack(target);
            sender.sendMessage(Component.text("Sent the LowOnFire Java pack to " + target.getName() + ".", NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("Usage: /" + label + " <reload|apply|status> [player]", NamedTextColor.RED));
        return true;
    }

    private void reloadRuntime(final boolean firstLoad) {
        if (!firstLoad) {
            this.reloadConfig();
        }

        if (this.packHttpServer != null) {
            this.packHttpServer.stop();
            this.packHttpServer = null;
        }

        this.bedrockDetector = resolveBedrockDetector();

        try {
            this.generatedPacks = ResourcePackBuilder.build(
                this.getDataFolder().toPath(),
                this.getConfig().getDouble("appearance.height-scale", 0.38D)
            );
            configureJavaPackOffer();
            installBedrockPackIfPossible();
        } catch (final IOException exception) {
            this.packOffer = null;
            throw new IllegalStateException("Unable to generate LowOnFire packs", exception);
        }
    }

    private void configureJavaPackOffer() throws IOException {
        if (!this.getConfig().getBoolean("java-pack.enabled", true)) {
            this.packOffer = null;
            return;
        }

        final String bindAddress = this.getConfig().getString("java-pack.bind-address", "0.0.0.0");
        final int port = this.getConfig().getInt("java-pack.port", 25589);
        final String path = this.getConfig().getString("java-pack.path", "/low-on-fire.zip");

        this.packHttpServer = new PackHttpServer(bindAddress, port, path, this.generatedPacks.javaPack().bytes());
        this.packHttpServer.start();

        final String publicUrl = resolvePublicUrl(this.packHttpServer.path(), port);
        if (publicUrl == null) {
            this.packOffer = null;
            this.getLogger().warning("LowOnFire could not determine a public Java pack URL. Set java-pack.public-url or server-ip.");
            return;
        }

        final UUID packId = UUID.nameUUIDFromBytes(("LowOnFire:" + this.generatedPacks.javaPack().sha1()).getBytes(StandardCharsets.UTF_8));
        this.packOffer = new PackOffer(packId, publicUrl, this.generatedPacks.javaPack().sha1(), deserializePrompt(this.getConfig().getString("java-pack.prompt")));
        this.getLogger().info("Java pack ready at " + publicUrl);
    }

    private void installBedrockPackIfPossible() throws IOException {
        if (!this.getConfig().getBoolean("bedrock.auto-install-geyser-pack", true)) {
            return;
        }

        final PluginManager pluginManager = this.getServer().getPluginManager();
        if (!pluginManager.isPluginEnabled("Geyser-Spigot")) {
            return;
        }

        final String fileName = this.getConfig().getString("bedrock.pack-file-name", "low-on-fire-bedrock.mcpack");
        final Path geyserPackDirectory = Objects.requireNonNull(this.getDataFolder().toPath().getParent(), "plugins directory")
            .resolve("Geyser-Spigot")
            .resolve("packs");
        Files.createDirectories(geyserPackDirectory);

        final Path target = geyserPackDirectory.resolve(fileName);
        final boolean changed = !Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), this.generatedPacks.bedrockPack().bytes());
        Files.write(target, this.generatedPacks.bedrockPack().bytes());

        if (changed) {
            this.getLogger().info("Installed Bedrock pack at " + target + ". Restart the server or reload Geyser so Bedrock clients receive it.");
        } else {
            this.getLogger().info("Bedrock pack already up to date at " + target + ".");
        }
    }

    private void offerJavaPackIfEligible(final Player player) {
        if (this.packOffer == null || !player.isOnline()) {
            return;
        }

        if (this.bedrockDetector.isBedrockPlayer(player.getUniqueId())) {
            return;
        }

        sendJavaPack(player);
    }

    private void sendJavaPack(final Player player) {
        player.setResourcePack(
            this.packOffer.id(),
            this.packOffer.url(),
            this.packOffer.sha1(),
            this.packOffer.prompt(),
            this.getConfig().getBoolean("java-pack.required", false)
        );
    }

    private BedrockDetector resolveBedrockDetector() {
        final PluginManager pluginManager = this.getServer().getPluginManager();
        if (pluginManager.isPluginEnabled("floodgate")) {
            try {
                this.getLogger().info("Using Floodgate for Bedrock detection.");
                return new FloodgateBedrockDetector();
            } catch (final Throwable throwable) {
                this.getLogger().warning("Floodgate is present but its API could not be used: " + throwable.getMessage());
            }
        }

        if (pluginManager.isPluginEnabled("Geyser-Spigot")) {
            try {
                this.getLogger().info("Using Geyser for Bedrock detection.");
                return new GeyserBedrockDetector();
            } catch (final Throwable throwable) {
                this.getLogger().warning("Geyser is present but its API could not be used: " + throwable.getMessage());
            }
        }

        this.getLogger().info("No Bedrock integration detected; LowOnFire will treat all players as Java clients.");
        return BedrockDetector.NONE;
    }

    private String resolvePublicUrl(final String path, final int port) {
        final String configured = this.getConfig().getString("java-pack.public-url", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }

        final String serverIp = Bukkit.getIp();
        if (serverIp == null || serverIp.isBlank()) {
            return null;
        }

        this.getLogger().warning(
            "java-pack.public-url is empty; falling back to server-ip " + serverIp + ". "
                + "This only works if players can reach that address directly."
        );
        return "http://" + serverIp + ":" + port + path;
    }

    private static String pathOrMissing(final Path path) {
        return path == null ? "missing" : path.toAbsolutePath().toString();
    }

    private static Component deserializePrompt(final String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        return LEGACY_SERIALIZER.deserialize(prompt);
    }

    private record PackOffer(UUID id, String url, String sha1, Component prompt) {
    }
}
