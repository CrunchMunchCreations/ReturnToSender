package xyz.bluspring.returntosender;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

@Plugin(
        id = "returntosender",
        name = "ReturnToSender",
        version = BuildConstants.VERSION,
        description = "Sends players back to the main server after it is restarted.",
        authors = {"BluSpring"}
)
public class ReturnToSender {
    public static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<Player, String> previousServers = new HashMap<>();
    private final Logger logger;
    private final ProxyServer proxy;

    private final Path dataDir;

    @Inject
    public ReturnToSender(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.logger = logger;
        this.proxy = proxy;
        this.dataDir = dataDir;
    }

    private String mainServer;
    private String limboServer;

    private boolean shouldReconnect = true;

    private final List<Player> connectingPlayers = new LinkedList<>();
    private final Timer reconnectTimer = new Timer();

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        load();

        this.proxy.getCommandManager().register(new BrigadierCommand(
                LiteralArgumentBuilder.<CommandSource>literal("togglereconnect")
                        .requires(source -> source.hasPermission("returntosender.admin"))
                        .executes((ctx) -> {
                            this.shouldReconnect = !this.shouldReconnect;
                            ctx.getSource().sendMessage(Component.text(String.format("Toggled regular server reconnection to %s", this.shouldReconnect)));

                            return Command.SINGLE_SUCCESS;
                        })
        ));

        var playersToRemove = new LinkedList<Player>();

        this.reconnectTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    var server = proxy.getServer(mainServer).orElse(null);

                    if (server == null)
                        return;

                    for (Player player : connectingPlayers) {
                        if (!player.isActive()) {
                            playersToRemove.add(player);
                            continue;
                        }

                        var playerServerId = previousServers.get(player);
                        var playerServer = proxy.getServer(playerServerId).orElse(server);

                        if (shouldReconnect || player.hasPermission("returntosender.bypass")) {
                            player.sendActionBar(Component.text("Attempting reconnection to server..."));

                            player.createConnectionRequest(playerServer)
                                .connect()
                                .thenAccept((result) -> {
                                    if (result.isSuccessful()) {
                                        playersToRemove.add(player);
                                        previousServers.remove(player);
                                    }
                                });
                        }
                    }

                    connectingPlayers.removeAll(playersToRemove);
                    playersToRemove.clear();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0L, 10_000L);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.reconnectTimer.cancel();
    }

    @Subscribe
    public void onServerConnect(ServerPostConnectEvent event) {
        var previous = event.getPreviousServer();
        var serverInfo = event.getPlayer().getCurrentServer().orElseThrow().getServerInfo();

        if (serverInfo.getName().equals(limboServer) && !this.connectingPlayers.contains(event.getPlayer())) {
            this.connectingPlayers.add(event.getPlayer());

            if (previous != null)
                this.previousServers.put(event.getPlayer(), previous.getServerInfo().getName());
        } else if ((serverInfo.getName().equals(mainServer) || (this.previousServers.containsKey(event.getPlayer()) && this.previousServers.get(event.getPlayer()).equals(serverInfo.getName())))) {
            this.connectingPlayers.remove(event.getPlayer());
            this.previousServers.remove(event.getPlayer());
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        var player = event.getPlayer();

        if (player == null)
            return;

        this.connectingPlayers.remove(player);
        this.previousServers.remove(player);
    }

    private void load() {
        var configPath = this.dataDir.resolve("config.json");

        if (!this.dataDir.toFile().exists()) {
            this.dataDir.toFile().mkdirs();
        }

        try {
            if (!configPath.toFile().exists()) {
                configPath.toFile().createNewFile();

                var empty = new JsonObject();
                empty.addProperty("main_server", "");
                empty.addProperty("limbo_server", "");

                Files.writeString(configPath, gson.toJson(empty), StandardOpenOption.WRITE);
                this.logger.warn("Config not set, proxy will not return players to servers!");

                return;
            }

            var json = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();

            this.mainServer = json.get("main_server").getAsString();
            this.limboServer = json.get("limbo_server").getAsString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
