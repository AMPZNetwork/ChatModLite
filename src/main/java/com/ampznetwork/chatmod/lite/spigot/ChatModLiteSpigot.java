package com.ampznetwork.chatmod.lite.spigot;

import com.ampznetwork.chatmod.api.model.config.ChatModules;
import com.ampznetwork.chatmod.api.model.config.channel.Channel;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessage;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;
import com.ampznetwork.chatmod.api.model.protocol.PacketType;
import com.ampznetwork.chatmod.api.parse.ChatMessageParser;
import com.ampznetwork.chatmod.lite.ChatModCore;
import com.ampznetwork.chatmod.lite.model.abstr.ChannelConfigProvider;
import com.ampznetwork.chatmod.lite.model.abstr.ChatDispatcher;
import com.ampznetwork.chatmod.lite.model.abstr.ChatModConfig;
import com.ampznetwork.chatmod.lite.model.abstr.PacketCaster;
import com.ampznetwork.chatmod.lite.model.abstr.PlayerAdapter;
import com.ampznetwork.chatmod.lite.model.exception.CommandException;
import com.ampznetwork.chatmod.lite.model.exception.PermissionException;
import com.ampznetwork.libmod.api.util.Util;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.comroid.api.java.SoftDepend;
import org.comroid.api.net.Rabbit;
import org.comroid.commands.model.permission.PermissionAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static java.util.Collections.*;
import static net.kyori.adventure.text.Component.*;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@Slf4j
@Getter
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class ChatModLiteSpigot extends JavaPlugin
        implements ChatModConfig, ChatDispatcher, ChannelConfigProvider, PlayerAdapter, PacketCaster, Listener {
    ChatModCore                                         core;
    List<Channel>                                       channels = new ArrayList<>();
    Map<UUID, com.ampznetwork.libmod.api.entity.Player> players  = new ConcurrentHashMap<>();
    @NonFinal String  serverName;
    @NonFinal Rabbit  rabbit;
    @NonFinal String formattingScheme;
    @NonFinal boolean compatibilityMode;

    public ChatModLiteSpigot() {
        this.core = new ChatModCore(this, this, this, PermissionAdapter.spigot(), this, this);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args
    ) {
        String channelName;
        var player = requirePlayer(sender);
        try {
            switch (args.length) {
                case 0:
                    switch (label) {
                        case "reload":
                            core.requireAnyPermission(player, "chatmod.reload");
                            core.execAndRespond(player, fallbackResponder(sender), ChatModLiteSpigot.this::reload);
                            break;
                        case "status":
                            core.execAndRespond(player, fallbackResponder(sender), () -> core.status(player));
                            break;
                    }
                    break;
                case 1:
                    switch (label) {
                        case "channel", "ch":
                            switch (args[0]) {
                                case "list":
                                    core.execAndRespond(player, fallbackResponder(sender), () -> core.list(player));
                                    break;
                                case "leave":
                                    core.execAndRespond(player, fallbackResponder(sender), () -> core.leave(player));
                                    break;
                                case "info", "join", "spy":
                                    throw CommandException.notEnoughArgs("missing channel name");
                                default:
                                    var channelOpt = core.channel(args[0]);
                                    if (channelOpt.isEmpty()) throw CommandException.noSuchChannel(args[0]);
                                    core.activeChannels(player)
                                            .forEach(chl -> chl.getPlayerIDs().remove(player.getId()));
                                    channelOpt.get().getPlayerIDs().add(player.getId());
                            }
                            break;
                        case "shout", "sh":
                            throw CommandException.notEnoughArgs("missing message");
                    }
                    break;
                case 2:
                    switch (label) {
                        case "channel", "ch":
                            channelName = args[1];
                            switch (args[0]) {
                                case "info":
                                    core.execAndRespond(player,
                                            fallbackResponder(sender),
                                            () -> core.info(player, channelName));
                                    break;
                                case "join":
                                    core.execAndRespond(player,
                                            fallbackResponder(sender),
                                            () -> core.join(player, channelName));
                                    break;
                                case "spy":
                                    core.execAndRespond(player,
                                            fallbackResponder(sender),
                                            () -> core.spy(player, channelName));
                                    break;
                            }
                            break;
                        case "shout", "sh":
                            channelName = args[0];
                            var msg = args[1];
                            core.execAndRespond(player,
                                    fallbackResponder(sender),
                                    () -> core.shout(player, channelName, msg));
                    }
                    break;
                default:
                    switch (label) {
                        case "shout", "sh":
                            channelName = args[0];
                            var msg = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                            core.execAndRespond(player,
                                    fallbackResponder(sender),
                                    () -> core.shout(player, channelName, msg));
                            break;
                    }
                    break;
            }
            return true;
        } catch (CommandException cex) {
            var component  = cex.toComponent();
            var serialized = BungeeComponentSerializer.get().serialize(component);
            sender.spigot().sendMessage(serialized);
            return false;
        } catch (PermissionException ex) {
            sender.sendMessage(ex.toString());
            return false;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args
    ) {
        var player = requirePlayer(sender);
        return switch (args.length) {
            case 0 -> List.of("reload", "status", "channel", "shout");
            case 1 -> switch (alias) {
                case "channel" -> List.of("list", "info", "join", "leave", "spy");
                case "shout" -> core.availableChannels(player).map(ChatModules.NamedBaseConfig::getName).toList();
                default -> emptyList();
            };
            case 2 -> switch (alias) {
                case "channel" -> switch (args[0]) {
                    case "join", "spy" -> core.availableChannels(player)
                            .map(ChatModules.NamedBaseConfig::getName)
                            .toList();
                    default -> emptyList();
                };
                case "shout" -> core.availableChannels(player)
                                        .map(ChatModules.NamedBaseConfig::getName)
                                        .anyMatch(args[0]::equals) ? List.of("<message>") : emptyList();
                default -> emptyList();
            };
            default -> "shout".equals(alias) ? List.of("<message>") : emptyList();
        };
    }

    @Override
    @SneakyThrows
    public void onDisable() {
        // stop accepting events
        core.getChannels().clear();

        // remove rabbit references
        core.getMqChannels().clear();
        // close down rabbit
        rabbit.getConnection().close(5000);
    }

    @Override
    public void onEnable() {
        SoftDepend.run("com.ampznetwork.libmod.core.LibModCommands").ifPresent($ -> {
            throw new IllegalStateException("ChatModLite is not compatible with LibMod sustained plugins");
        });

        saveDefaultConfig();

        var cfg = getConfig();
        serverName = cfg.getString("server.name", "&aMC");
        rabbit     = Rabbit.of(cfg.getString("modules.rabbitmq.rabbitUri", "amqp://guest:guest@localhost:5672"))
                .assertion();

        var section = cfg.getList("channels");
        if (section != null) for (var channelInfo : section) {
            //noinspection unchecked
            var map         = (Map<String, Map<String, Object>>) channelInfo;
            var channelName = map.keySet().stream().findAny().orElseThrow();
            var channelData = map.get(channelName);
            channels.add(new Channel(true,
                    channelName,
                    (String) channelData.getOrDefault("alias", null),
                    (String) channelData.getOrDefault("display", null),
                    (String) channelData.getOrDefault("permission", null),
                    null,
                    (Boolean) channelData.getOrDefault("publish", Boolean.TRUE)));
        }
        getLogger().info("Loaded %d channels".formatted(core.getChannels().size()));

        core.loadMqChannels();

        formattingScheme = cfg.getString("formatting.scheme",
                "&7[%server_name%&7] #&6%channel_name%&7 <%player_name%&7> &r%message%");
        compatibilityMode = cfg.getBoolean("compatibility.listeners", false);

        getServer().getPluginManager().registerEvents(this, this);
    }

    private Consumer<ComponentLike> fallbackResponder(@NotNull CommandSender sender) {
        return component -> {
            var bungee = BungeeComponentSerializer.get().serialize(component.asComponent());
            sender.spigot().sendMessage(bungee);
        };
    }

    private com.ampznetwork.libmod.api.entity.Player getOrCreatePlayer(Player player) {
        return players.computeIfAbsent(player.getUniqueId(),
                k -> com.ampznetwork.libmod.api.entity.Player.basic(k, player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void dispatch(PlayerJoinEvent event) {
        var player = basicPlayer(event.getPlayer());

        core.playerJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void dispatch(PlayerQuitEvent event) {
        var player = basicPlayer(event.getPlayer());
        var first  = core.getChannels().getFirst();

        core.playerLeave(player, first);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void dispatch(PlayerKickEvent event) {
        var player = basicPlayer(event.getPlayer());
        var first  = core.getChannels().getFirst();

        core.playerLeave(player, first);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void dispatch(PlayerAdvancementDoneEvent event) {
        var advancement = event.getAdvancement();
        var display = advancement.getDisplay();
        if (display == null || display.isHidden()) return;

        var player = basicPlayer(event.getPlayer());
        var first  = core.getChannels().getFirst();

        // send leave message
        var message = core.createAdvancementMessage(player, advancement);
        var packet = new ChatMessagePacket(PacketType.OTHER,
                serverName,
                first.getName(),
                message,
                List.of(serverName));
        core.outbound(first, packet);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void dispatch(PlayerDeathEvent event) {
        var player = basicPlayer(event.getEntity());
        var first  = core.getChannels().getFirst();

        // send leave message
        var message = core.createDeathMessage(player, event.getDeathMessage());
        var packet = new ChatMessagePacket(PacketType.OTHER,
                serverName,
                first.getName(),
                message,
                List.of(serverName));
        core.outbound(first, packet);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void dispatch(AsyncPlayerChatEvent event) {
        try {
            if (event.isCancelled()) return;

            var plaintext = event.getMessage();

            String quickShoutChannelName = null;
            if (plaintext.startsWith("@#")) {
                var endOfName = plaintext.indexOf(' ');
                quickShoutChannelName = plaintext.substring(2, endOfName);
                plaintext             = plaintext.substring(endOfName + 1);
            }
            var bukkitPlayer = event.getPlayer();
            var channel = (quickShoutChannelName != null
                           ? core.channel(quickShoutChannelName)
                           : core.getChannels()
                                   .stream()
                                   .filter(chl -> chl.getPlayerIDs().contains(bukkitPlayer.getUniqueId()))
                                   .findAny()).orElseGet(core.getChannels()::getFirst);

            var player     = getOrCreatePlayer(bukkitPlayer);
            var playerName = Util.Kyori.sanitizePlain(bukkitPlayer.getDisplayName());

            var bundle  = ChatMessageParser.parse(plaintext, this, channel, player, playerName);
            var message = new ChatMessage(player, playerName, bundle);

            if (core.hasAccess(player.getId(), channel)) {
                var packet = new ChatMessagePacket(PacketType.CHAT,
                        serverName,
                        channel.getName(),
                        message,
                        List.of());
                core.outbound(channel, packet);
            } else {
                getLogger().warning("Player %s has no access to channel %s".formatted(playerName, channel.getName()));
                sendToPlayer(text("Sorry, you don't have access to your current channel!", RED), player);
            }
            event.setCancelled(true);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Error in event handler", t);
        }
    }

    @Override
    public void sendToPlayer(ComponentLike component, @NotNull com.ampznetwork.libmod.api.entity.Player player) {
        var serialized   = BungeeComponentSerializer.get().serialize(component.asComponent());
        var bukkitPlayer = getServer().getPlayer(player.getId());
        if (bukkitPlayer == null) return;
        bukkitPlayer.spigot().sendMessage(serialized);
    }

    public Component reload() {
        onDisable();
        reloadConfig();
        onEnable();

        return text("Reload complete!", GREEN);
    }

    private com.ampznetwork.libmod.api.entity.Player requirePlayer(@NotNull CommandSender sender) {
        if (sender instanceof Player player) return basicPlayer(player);
        throw new CommandException("Only players can execute this command");
    }

    @Override
    public com.ampznetwork.libmod.api.entity.Player getPlayer(UUID playerId) {
        var bukkitPlayer = getServer().getPlayer(playerId);
        return basicPlayer(Objects.requireNonNull(bukkitPlayer, "bukkitPlayer"));
    }

    private Component formatMessage(String source, String channelName, OfflinePlayer player, Component content) {
        var basic = basicPlayer(player);
        var raw   = LegacyComponentSerializer.legacyAmpersand().serialize(content);
        var fixes = formattingScheme.split("%message%");
        var adapter = SoftDepend.type("me.clip.placeholderapi.PlaceholderAPI")
                .map($ -> com.ampznetwork.chatmod.lite.model.abstr.PlaceholderAdapter.Hook)
                .orElse(com.ampznetwork.chatmod.lite.model.abstr.PlaceholderAdapter.Native);
        var prefix = adapter.applyPlaceholders(source, channelName, basic.getName(), basic, fixes[0]);
        var suffix = fixes.length > 1 ? adapter.applyPlaceholders(serverName,
                channelName,
                basic.getName(),
                basic,
                fixes[1]) : "";
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + raw + suffix);
    }

    @Override
    public void localcastPacket(ChatMessagePacket packet) {
        if (packet.getRoute().contains(serverName)) return;

        var channelOpt = core.channel(packet.getChannel());
        if (channelOpt.isEmpty()) {
            getLogger().warning("Received message for nonexistent channel: " + packet.getChannel());
            return;
        }

        var channel   = channelOpt.get();
        var message   = packet.getMessage();
        var formatted = message.getFullText();

        core.localcast(channel, formatted);
    }

    private com.ampznetwork.libmod.api.entity.Player basicPlayer(OfflinePlayer player) {
        return com.ampznetwork.libmod.api.entity.Player.basic(player.getUniqueId(), player.getName());
    }
}
