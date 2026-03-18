package dev.ash.lowonfire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    private PlayerPreferenceStore preferenceStore;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.preferenceStore = new PlayerPreferenceStore(this.getDataFolder().toPath());
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
            if (this.isVerboseDebug()) {
                this.getLogger().info(
                    "Ignoring resource pack status from "
                        + event.getPlayer().getName()
                        + " for pack id "
                        + event.getID()
                        + " because it does not match the current pack offer."
                );
            }
            return;
        }

        final String playerName = event.getPlayer().getName();
        final String playerAddress = playerAddress(event.getPlayer());
        final String expectedHash = this.packOffer.sha1();

        switch (event.getStatus()) {
            case ACCEPTED, DOWNLOADED -> this.getLogger().info(
                "LowOnFire pack "
                    + event.getStatus()
                    + " for "
                    + playerName
                    + " at "
                    + playerAddress
                    + " id="
                    + event.getID()
                    + " expectedHash="
                    + expectedHash
            );
            case SUCCESSFULLY_LOADED -> this.getLogger().info(
                "LowOnFire pack loaded successfully for "
                    + playerName
                    + " at "
                    + playerAddress
                    + " id="
                    + event.getID()
                    + " expectedHash="
                    + expectedHash
            );
            case DECLINED -> this.getLogger().warning(
                playerName
                    + " declined the LowOnFire resource pack at "
                    + playerAddress
                    + " id="
                    + event.getID()
                    + " expectedHash="
                    + expectedHash
            );
            case FAILED_DOWNLOAD -> this.getLogger().warning(
                playerName
                    + " failed to download the LowOnFire resource pack from "
                    + this.packOffer.url()
                    + " at "
                    + playerAddress
                    + " id="
                    + event.getID()
                    + " expectedHash="
                    + expectedHash
                    + ". Check java-pack.public-url, firewall/NAT rules for the embedded HTTP port "
                    + (this.packHttpServer == null ? "<stopped>" : this.packHttpServer.port())
                    + ", and whether the URL is reachable from the client."
            );
            case INVALID_URL -> this.getLogger().warning(
                "LowOnFire sent an invalid resource pack URL to "
                    + playerName
                    + ": "
                    + this.packOffer.url()
                    + " id="
                    + event.getID()
                    + " expectedHash="
                    + expectedHash
            );
            case FAILED_RELOAD -> this.getLogger().warning(
                "LowOnFire pack reload failed for "
                    + playerName
                    + " at "
                    + playerAddress
                    + " id="
                    + event.getID()
                    + " expectedHash="
                    + expectedHash
            );
            case DISCARDED -> this.getLogger().info(
                "LowOnFire pack was discarded for "
                    + playerName
                    + " at "
                    + playerAddress
                    + " id="
                    + event.getID()
                    + " expectedHash="
                    + expectedHash
            );
            default -> {
            }
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length > 0 && requiresAdminAccess(args[0]) && !sender.hasPermission("lowonfire.command")) {
            sender.sendMessage(Component.text("You do not have permission to use that subcommand.", NamedTextColor.RED));
            return true;
        }

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

        if ("debug".equalsIgnoreCase(args[0])) {
            sendDebugSummary(sender);
            return true;
        }

        if ("on".equalsIgnoreCase(args[0]) || "enable".equalsIgnoreCase(args[0])) {
            return setPackEnabled(sender, true);
        }

        if ("off".equalsIgnoreCase(args[0]) || "disable".equalsIgnoreCase(args[0])) {
            return setPackEnabled(sender, false);
        }

        if ("toggle".equalsIgnoreCase(args[0])) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can toggle their pack preference.", NamedTextColor.RED));
                return true;
            }
            return setPackEnabled(sender, !isPackEnabledFor(player));
        }

        if ("probe".equalsIgnoreCase(args[0])) {
            probePackUrl(sender);
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

        sender.sendMessage(Component.text("Usage: /" + label + " <reload|apply|status|debug|probe|on|off|toggle> [player]", NamedTextColor.RED));
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
            this.preferenceStore.load();
            this.generatedPacks = ResourcePackBuilder.build(
                this.getDataFolder().toPath(),
                this.getConfig().getDouble("appearance.height-scale", 0.38D)
            );
            if (this.isVerboseDebug()) {
                this.getLogger().info(
                    "Generated packs: java="
                        + this.generatedPacks.javaPack().path().toAbsolutePath()
                        + " size="
                        + this.generatedPacks.javaPack().bytes().length
                        + " sha1="
                        + this.generatedPacks.javaPack().sha1()
                        + ", bedrock="
                        + this.generatedPacks.bedrockPack().path().toAbsolutePath()
                        + " size="
                        + this.generatedPacks.bedrockPack().bytes().length
                );
            }
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
        final String path = this.getConfig().getString("java-pack.path", "/low-on-fire.zip");

        this.packHttpServer = new PackHttpServer(bindAddress, path, this.generatedPacks.javaPack().bytes());
        this.packHttpServer.start();

        final int actualPort = this.packHttpServer.port();
        final String publicUrl = resolvePublicUrl(this.packHttpServer.path(), actualPort);
        if (publicUrl == null) {
            this.packOffer = null;
            this.getLogger().warning("LowOnFire could not determine a public Java pack URL. Set java-pack.public-url or server-ip.");
            return;
        }

        final UUID packId = UUID.nameUUIDFromBytes(("LowOnFire:" + this.generatedPacks.javaPack().sha1()).getBytes(StandardCharsets.UTF_8));
        final byte[] hashBytes = HexFormat.of().parseHex(this.generatedPacks.javaPack().sha1());
        this.packOffer = new PackOffer(packId, publicUrl, hashBytes, this.generatedPacks.javaPack().sha1(), deserializePrompt(this.getConfig().getString("java-pack.prompt")));
        this.getLogger().info("Java pack ready at " + publicUrl);
        this.getLogger().info("LowOnFire embedded HTTP server bound to " + bindAddress + ":" + actualPort + " for path " + this.packHttpServer.path());
        if (this.isVerboseDebug()) {
            this.getLogger().info(
                "Pack offer configured: id="
                    + packId
                    + " sha1="
                    + this.packOffer.sha1()
                    + " bytes="
                    + this.packOffer.hashBytes().length
                    + " required="
                    + this.getConfig().getBoolean("java-pack.required", false)
                    + " localPort="
                    + actualPort
            );
        }
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

        if (!isPackEnabledFor(player)) {
            if (this.isVerboseDebug()) {
                this.getLogger().info("Skipping Java pack for " + player.getName() + " because they disabled LowOnFire.");
            }
            return;
        }

        final boolean bedrock = this.bedrockDetector.isBedrockPlayer(player.getUniqueId());
        if (bedrock) {
            if (this.isVerboseDebug()) {
                this.getLogger().info(
                    "Skipping Java pack for Bedrock player "
                        + player.getName()
                        + " at "
                        + playerAddress(player)
                        + " via "
                        + this.bedrockDetector.getClass().getSimpleName()
                );
            }
            return;
        }

        if (this.isVerboseDebug()) {
            this.getLogger().info(
                "Offering Java pack to "
                    + player.getName()
                    + " at "
                    + playerAddress(player)
                    + " id="
                    + this.packOffer.id()
                    + " sha1="
                    + this.packOffer.sha1()
                    + " required="
                    + this.getConfig().getBoolean("java-pack.required", false)
            );
        }
        sendJavaPack(player);
    }

    private void sendJavaPack(final Player player) {
        if (this.isVerboseDebug()) {
            this.getLogger().info(
                "Sending Java pack to "
                    + player.getName()
                    + " at "
                    + playerAddress(player)
                    + " url="
                    + this.packOffer.url()
                    + " id="
                    + this.packOffer.id()
                    + " sha1="
                    + this.packOffer.sha1()
                    + " required="
                    + this.getConfig().getBoolean("java-pack.required", false)
            );
        }
        player.setResourcePack(
            this.packOffer.id(),
            this.packOffer.url(),
            this.packOffer.hashBytes(),
            this.packOffer.prompt(),
            this.getConfig().getBoolean("java-pack.required", false)
        );
    }

    private void sendDebugSummary(final CommandSender sender) {
        sender.sendMessage(Component.text("LowOnFire Debug", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Verbose logging: " + this.isVerboseDebug(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Bedrock detector: " + this.bedrockDetector.getClass().getSimpleName(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Floodgate enabled: " + this.getServer().getPluginManager().isPluginEnabled("floodgate"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Geyser enabled: " + this.getServer().getPluginManager().isPluginEnabled("Geyser-Spigot"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Java pack enabled: " + this.getConfig().getBoolean("java-pack.enabled", true), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Java pack URL: " + (this.packOffer == null ? "<none>" : this.packOffer.url()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Java pack SHA-1: " + (this.packOffer == null ? "<none>" : this.packOffer.sha1()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Java pack ID: " + (this.packOffer == null ? "<none>" : this.packOffer.id()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Java pack bytes: " + (this.generatedPacks == null ? "<none>" : this.generatedPacks.javaPack().bytes().length), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Bedrock pack path: " + pathOrMissing(this.generatedPacks == null ? null : this.generatedPacks.bedrockPack().path()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Java pack path: " + pathOrMissing(this.generatedPacks == null ? null : this.generatedPacks.javaPack().path()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Join send enabled: " + this.getConfig().getBoolean("java-pack.send-on-join", true), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Pack required: " + this.getConfig().getBoolean("java-pack.required", false), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Host bind: " + this.getConfig().getString("java-pack.bind-address", "0.0.0.0") + ":" + (this.packHttpServer == null ? "<stopped>" : this.packHttpServer.port()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Host path: " + this.getConfig().getString("java-pack.path", "/low-on-fire.zip"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Public URL: " + this.getConfig().getString("java-pack.public-url", ""), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Online players: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.GRAY));
        if (sender instanceof Player player) {
            sender.sendMessage(Component.text("Your pack preference: " + (isPackEnabledFor(player) ? "enabled" : "disabled"), NamedTextColor.GRAY));
        }
    }

    private void probePackUrl(final CommandSender sender) {
        if (this.packOffer == null) {
            sender.sendMessage(Component.text("The Java pack is not configured yet.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("Probing " + this.packOffer.url() + " from the server. Check console for the detailed result.", NamedTextColor.YELLOW));
        final String targetUrl = this.packOffer.url();
        final String expectedSha1 = this.packOffer.sha1();
        final int expectedBytes = this.generatedPacks.javaPack().bytes().length;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                final HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
                final HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .header("User-Agent", "LowOnFire-Debug/1.0")
                    .build();

                final HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                final byte[] body = response.body();
                final String actualSha1 = sha1(body);
                final int redirects = countRedirects(response);
                final String summary =
                    "Pack probe result: status="
                        + response.statusCode()
                        + ", redirects="
                        + redirects
                        + ", finalUri="
                        + response.uri()
                        + ", contentType="
                        + response.headers().firstValue("content-type").orElse("<missing>")
                        + ", contentLengthHeader="
                        + response.headers().firstValue("content-length").orElse("<missing>")
                        + ", bodyBytes="
                        + body.length
                        + ", expectedBytes="
                        + expectedBytes
                        + ", sha1="
                        + actualSha1
                        + ", expectedSha1="
                        + expectedSha1
                        + ", hashMatch="
                        + actualSha1.equalsIgnoreCase(expectedSha1);
                this.getLogger().info(summary);

                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(Component.text(summary, NamedTextColor.GREEN)));
            } catch (final Exception exception) {
                this.getLogger().warning("Pack probe failed for " + targetUrl + ": " + exception.getMessage());
                this.getLogger().log(java.util.logging.Level.FINE, "Pack probe exception", exception);
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(Component.text("Pack probe failed: " + exception.getMessage(), NamedTextColor.RED)));
            }
        });
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
                + "This only works if players can reach that address directly. The embedded server chose port " + port + "."
        );
        return "http://" + serverIp + ":" + port + path;
    }

    private boolean isVerboseDebug() {
        return this.getConfig().getBoolean("debug.verbose-logging", true);
    }

    private boolean requiresAdminAccess(final String subcommand) {
        return "reload".equalsIgnoreCase(subcommand)
            || "apply".equalsIgnoreCase(subcommand)
            || "debug".equalsIgnoreCase(subcommand)
            || "probe".equalsIgnoreCase(subcommand);
    }

    private boolean setPackEnabled(final CommandSender sender, final boolean enabled) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can change their pack preference.", NamedTextColor.RED));
            return true;
        }

        try {
            final boolean changed = this.preferenceStore.setEnabled(player.getUniqueId(), enabled);
            if (enabled) {
                sender.sendMessage(Component.text(changed ? "LowOnFire is now enabled for you." : "LowOnFire was already enabled for you.", NamedTextColor.GREEN));
                if (this.packOffer != null) {
                    sendJavaPack(player);
                    sender.sendMessage(Component.text("Resent the LowOnFire pack.", NamedTextColor.GRAY));
                }
            } else {
                sender.sendMessage(Component.text(changed ? "LowOnFire is now disabled for you." : "LowOnFire was already disabled for you.", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Use /lowonfire on to re-enable it later.", NamedTextColor.GRAY));
            }
        } catch (final IOException exception) {
            this.getLogger().warning("Failed to update LowOnFire preference for " + player.getName() + ": " + exception.getMessage());
            sender.sendMessage(Component.text("Could not save your LowOnFire preference right now.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean isPackEnabledFor(final Player player) {
        return this.preferenceStore == null || this.preferenceStore.isEnabled(player.getUniqueId());
    }

    private static String pathOrMissing(final Path path) {
        return path == null ? "missing" : path.toAbsolutePath().toString();
    }

    private static String playerAddress(final Player player) {
        return player.getAddress() == null ? "<unknown>" : player.getAddress().toString();
    }

    private static String sha1(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (final java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-1 implementation", exception);
        }
    }

    private static int countRedirects(final HttpResponse<?> response) {
        int redirects = 0;
        HttpResponse<?> current = response;
        while (current.previousResponse().isPresent()) {
            redirects++;
            current = current.previousResponse().get();
        }
        return redirects;
    }

    private static Component deserializePrompt(final String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        return LEGACY_SERIALIZER.deserialize(prompt);
    }

    private record PackOffer(UUID id, String url, byte[] hashBytes, String sha1, Component prompt) {
    }
}
