package com.ampznetwork.chatmod.lite;

import com.ampznetwork.chatmod.api.model.config.ChatModules;
import com.ampznetwork.chatmod.api.model.config.channel.Channel;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessage;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;
import com.ampznetwork.chatmod.api.model.protocol.internal.ChatMessagePacketImpl;
import com.ampznetwork.chatmod.api.model.protocol.internal.PacketType;
import com.ampznetwork.chatmod.api.util.ChatMessageParser;
import com.ampznetwork.chatmod.lite.lang.Words;
import com.ampznetwork.chatmod.lite.model.CommandException;
import com.ampznetwork.chatmod.lite.model.JacksonPacketConverter;
import com.ampznetwork.chatmod.lite.model.PermissionException;
import com.ampznetwork.chatmod.lite.model.PlaceholderAdapter;
import com.ampznetwork.libmod.api.util.Util;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.comroid.api.java.SoftDepend;
import org.comroid.api.net.Rabbit;
import org.comroid.commands.model.permission.MinecraftPermissionAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.*;
import static net.kyori.adventure.text.Component.*;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@Slf4j
@Getter
@NoArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class ChatModLite extends JavaPlugin implements Listener {
    ObjectMapper                                           objectMapper      = new ObjectMapper();
    MinecraftPermissionAdapter                             permissionAdapter = MinecraftPermissionAdapter.spigot();
    Map<UUID, com.ampznetwork.libmod.api.entity.Player> players = new ConcurrentHashMap<>();
    List<Channel>                                          channels          = new ArrayList<>();
    Map<Channel, Rabbit.Exchange.Route<ChatMessagePacket>> mqChannels        = new ConcurrentHashMap<>();
    @NonFinal String  serverName;
    @NonFinal Rabbit  rabbit;
    @NonFinal String formattingScheme;
    @NonFinal boolean compatibilityMode;

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args
    ) {
        String channelName;
        try {
            switch (args.length) {
                case 0:
                    switch (label) {
                        case "reload":
                            requireAnyPermission(sender, "chatmod.reload");
                            execAndRespond(sender, this::reload);
                            break;
                        case "status":
                            execAndRespond(sender, () -> status(sender));
                            break;
                    }
                    break;
                case 1:
                    switch (label) {
                        case "channel", "ch":
                            switch (args[0]) {
                                case "list":
                                    execAndRespond(sender, () -> list(sender));
                                    break;
                                case "leave":
                                    execAndRespond(sender, () -> leave(sender));
                                    break;
                                case "info", "join", "spy":
                                    throw CommandException.notEnoughArgs("missing channel name");
                                default:
                                    var channelOpt = channel(args[0]);
                                    if (channelOpt.isEmpty()) throw CommandException.noSuchChannel(args[0]);
                                    var player = requirePlayer(sender);
                                    activeChannels(player).forEach(chl -> chl.getPlayerIDs()
                                            .remove(player.getUniqueId()));
                                    channelOpt.get().getPlayerIDs().add(player.getUniqueId());
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
                                    execAndRespond(sender, () -> info(sender, channelName));
                                    break;
                                case "join":
                                    execAndRespond(sender, () -> join(sender, channelName));
                                    break;
                                case "spy":
                                    execAndRespond(sender, () -> spy(sender, channelName));
                                    break;
                            }
                            break;
                        case "shout", "sh":
                            channelName = args[0];
                            var msg = args[1];
                            execAndRespond(sender, () -> shout(sender, channelName, msg));
                    }
                    break;
                default:
                    switch (label) {
                        case "shout", "sh":
                            channelName = args[0];
                            var msg = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                            execAndRespond(sender, () -> shout(sender, channelName, msg));
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
        return switch (args.length) {
            case 0 -> List.of("reload", "status", "channel", "shout");
            case 1 -> switch (alias) {
                case "channel" -> List.of("list", "info", "join", "leave", "spy");
                case "shout" -> availableChannels(sender).map(ChatModules.NamedBaseConfig::getName).toList();
                default -> emptyList();
            };
            case 2 -> switch (alias) {
                case "channel" -> switch (args[0]) {
                    case "join", "spy" -> availableChannels(sender).map(ChatModules.NamedBaseConfig::getName).toList();
                    default -> emptyList();
                };
                case "shout" -> availableChannels(sender).map(ChatModules.NamedBaseConfig::getName)
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
        channels.clear();

        // remove rabbit references
        mqChannels.clear();
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
        getLogger().info("Loaded %d channels".formatted(channels.size()));

        var exchange = rabbit.exchange("minecraft", "topic");
        for (var channel : channels) {
            if (!channel.isPublish()) continue;
            var route = exchange.route(Util.Kyori.sanitizePlain(serverName + ".chat." + channel.getName())
                    .toLowerCase(), "chat." + channel.getName(), new JacksonPacketConverter(objectMapper));
            route.subscribeData(this::localcastPacket);
            mqChannels.put(channel, route);
        }
        getLogger().info("Created %d RabbitMQ bindings".formatted(mqChannels.size()));

        formattingScheme = cfg.getString("formatting.scheme",
                "&7[%server_name%&7] #&6%channel_name%&7 <%player_name%&7> &r%message%");
        compatibilityMode = cfg.getBoolean("compatibility.listeners", false);

        getServer().getPluginManager().registerEvents(this, this);
    }

    private Component formatMessage(String source, String channelName, OfflinePlayer sender, Component content) {
        var raw   = LegacyComponentSerializer.legacyAmpersand().serialize(content);
        var fixes = formattingScheme.split("%message%");
        var adapter = SoftDepend.type("me.clip.placeholderapi.PlaceholderAPI")
                .map($ -> PlaceholderAdapter.Hook)
                .orElse(PlaceholderAdapter.Native);
        var prefix = adapter.applyPlaceholders(source, channelName, sender, fixes[0]);
        var suffix = fixes.length > 1 ? adapter.applyPlaceholders(serverName, channelName, sender, fixes[1]) : "";
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + raw + suffix);
    }

    private void localcastPacket(ChatMessagePacket packet) {
        var channelOpt = channel(packet.getChannel());
        if (channelOpt.isEmpty()) {
            getLogger().warning("Received message for nonexistent channel: " + packet.getChannel());
            return;
        }
        var channel = channelOpt.get();

        var       message   = packet.getMessage();
        var       sender    = message.getSender();
        Component formatted = message.getFullText();

        if (sender != null) {
            var player = Bukkit.getOfflinePlayer(sender.getId());
            formatted = formatMessage(packet.getSource(), channel.getAlternateName(), player, formatted);
        }

        localcast(channel, formatted);
    }

    private void localcast(Channel channel, Component component) {
        final var bungeeComponent = BungeeComponentSerializer.get().serialize(component);
        final var server = getServer();
        channel.allPlayerIDs()
                .map(server::getPlayer)
                .distinct()
                .filter(Objects::nonNull)
                .filter(player -> hasAccess(player.getUniqueId(), channel))
                .map(Player::spigot)
                .forEach(player -> player.sendMessage(bungeeComponent));
    }

    private com.ampznetwork.libmod.api.entity.Player getOrCreatePlayer(Player player) {
        return players.computeIfAbsent(player.getUniqueId(),
                k -> com.ampznetwork.libmod.api.entity.Player.basic(k, player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void dispatch(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var id     = player.getUniqueId();

        if (channels.isEmpty() || channels.stream().anyMatch(chl -> chl.getPlayerIDs().contains(id))) return;

        channels.getFirst().getPlayerIDs().add(id);
        channels.stream()
                .filter(channel -> permissionAdapter.checkPermissionOrOp(id, "chat.autospy." + channel.getName(), true))
                .peek(channel -> {
                    if (!hasAccess(id,
                            channel)) getLogger().warning(("Player %s has auto-join permission for channel %s but does not have access to the " + "channel").formatted(
                            player.getName(),
                            channel.getName()));
                })
                .forEach(channel -> channel.getSpyIDs().add(id));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void dispatch(AsyncPlayerChatEvent event) {
        try {
            if (event.isCancelled()) return;

            var msg        = new ChatMessageParser().parse(event.getMessage());
            var bukkitPlayer = event.getPlayer();
            var player       = getOrCreatePlayer(bukkitPlayer);
            var playerName = Util.Kyori.sanitizePlain(bukkitPlayer.getDisplayName());
            var message    = new ChatMessage(player, playerName, event.getMessage(), msg);
            var channel = channels.stream()
                    .filter(chl -> chl.getPlayerIDs().contains(bukkitPlayer.getUniqueId()))
                    .findAny()
                    .orElseGet(channels::getFirst);

            if (hasAccess(player.getId(), channel)) {
                var packet = new ChatMessagePacketImpl(PacketType.CHAT,
                        serverName,
                        channel.getName(),
                        message,
                        new ArrayList<>());
                outbound(channel, packet);
            } else {
                getLogger().warning("Player %s has no access to channel %s".formatted(playerName, channel.getName()));
                sendToPlayer(text("Sorry, you don't have access to your current channel!", RED), bukkitPlayer);
            }
            event.setCancelled(true);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Error in event handler", t);
        }
    }

    /*
        @EventHandler(priority = EventPriority.MONITOR)
        public void dispatch(PlayerJoinEvent event) {
            try {
                playerJoin(event.getPlayer().getUniqueId(), createEventDelegate(event, DELEGATE_PROPERTY_JOIN));
            } catch (Throwable t) {
                mod.getLogger().log(Level.WARNING, "Error in event handler", t);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void dispatch(PlayerQuitEvent event) {
            try {
                playerLeave(event.getPlayer().getUniqueId(), createEventDelegate(event, DELEGATE_PROPERTY_QUIT));
            } catch (Throwable t) {
                mod.getLogger().log(Level.WARNING, "Error in event handler", t);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void dispatch(PlayerKickEvent event) {
            try {
                playerLeave(event.getPlayer().getUniqueId(), createEventDelegate(event, DELEGATE_PROPERTY_KICK));
            } catch (Throwable t) {
                mod.getLogger().log(Level.WARNING, "Error in event handler", t);
            }
        }
     */
    private void execAndRespond(@NotNull CommandSender sender, Supplier<ComponentLike> exec) {
        try {
            ComponentLike component;
            try {
                component = exec.get();
            } catch (CommandException cex) {
                component = cex.toComponent();
            }
            if (sender instanceof Player player && component != null) sendToPlayer(component, player);
        } catch (Throwable t) {
            sender.sendMessage(ChatColor.RED + "An internal error occurred");
            getLogger().log(Level.WARNING, "Could not execute command", t);
        }
    }

    private void sendToPlayer(ComponentLike component, @NotNull Player player) {
        var serialized = BungeeComponentSerializer.get().serialize(component.asComponent());
        player.spigot().sendMessage(serialized);
    }

    private void outbound(Channel channel, ChatMessagePacketImpl packet) {
        if (channel.isPublish()) send(packet);
        else localcastPacket(packet);
    }

    private void send(ChatMessagePacket packet) {
        var channel = packet.getChannel();
        var mq = mqChannels.entrySet()
                .stream()
                .filter(e -> e.getKey().getName().equals(channel))
                .findAny()
                .map(Map.Entry::getValue)
                .orElse(null);

        if (mq == null) {
            getLogger().warning("No MQ binding found for channel " + channel);
            return;
        }

        mq.send(packet);
    }

    private void requireAnyPermission(@NotNull CommandSender sender, @NotNull String perm) {
        if (!(sender instanceof Player player))
            // not player; thus console or fakeplayer; allow
            return;
        var result = permissionAdapter.checkPermissionOrOp(player.getUniqueId(), perm, true);
        if (!result) throw new PermissionException(perm);
    }

    private Player requirePlayer(@NotNull CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new CommandException("Only players can execute this command");
    }

    private Optional<Channel> channel(String named) {
        return channels.stream()
                .filter(chl -> chl.getName().equalsIgnoreCase(named) || (chl.getAlias() != null && chl.getAlias()
                        .equalsIgnoreCase(named)))
                .findAny();
    }

    private Stream<Channel> availableChannels(CommandSender sender) {
        var stream = channels.stream();
        if (sender instanceof Player player) stream = stream.filter(channel -> hasAccess(player.getUniqueId(),
                channel));
        return stream;
    }

    private Stream<Channel> activeChannels(CommandSender sender) {
        if (sender instanceof Player player) {
            var playerId = player.getUniqueId();
            return Stream.concat(channels.stream().filter(channel -> channel.getPlayerIDs().contains(playerId)),
                            channels.stream().filter(channel -> channel.getSpyIDs().contains(playerId)))
                    .filter(channel -> hasAccess(playerId, channel));
        }
        return Stream.empty();
    }

    private Component reload() {
        onDisable();
        reloadConfig();
        onEnable();

        return text("Reload complete!", GREEN);
    }

    private Component status(@NotNull CommandSender sender) {
        requireAnyPermission(sender, "chatmod.status");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        return text("Current channels:\n", BLUE).append(activeChannels(player).sorted(Comparator.comparingInt(channel ->
                channel.getSpyIDs().contains(playerId)
                ? 1
                : 0)).map(channel -> channel.toComponent(playerId)).collect(Util.Kyori.collector(text("\n"))));
    }

    private ComponentLike list(@NotNull CommandSender sender) {
        requireAnyPermission(sender, "chatmod.channel.list");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        return text("Available channels:\n", BLUE).append(availableChannels(player).map(channel -> channelInfoComponent(
                channel,
                playerId)).collect(Util.Kyori.collector(text("\n"))));
    }

    private Component info(@NotNull CommandSender sender, String channelName) {
        requireAnyPermission(sender, "chatmod.channel.info");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var channel = channels.stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        return channelInfoComponent(channel, playerId);
    }

    private Component join(@NotNull CommandSender sender, String channelName) {
        requireAnyPermission(sender, "chatmod.channel.join");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var channel = channels.stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        if (channel.getPlayerIDs().contains(playerId)) throw new CommandException("You already joined this channel");
        var previous = activeChannels(player).filter(it -> it.getPlayerIDs().remove(playerId)).count();
        getLogger().fine("Removed player %s from %d previously joined channels".formatted(player, previous));
        channel.getPlayerIDs().add(playerId);
        return text("Joined channel ", BLUE).append(channel.toComponent());
    }

    private Component leave(@NotNull CommandSender sender) {
        requireAnyPermission(sender, "chatmod.channel.leave");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var count = activeChannels(player).filter(channel -> channel.getPlayerIDs().remove(playerId)).count();
        return text("You were removed from ", BLUE).append(text(count, GREEN))
                .append(text(" " + Words.CHANNEL.apply(count), BLUE));
    }

    private Component spy(@NotNull CommandSender sender, String channelName) {
        requireAnyPermission(sender, "chatmod.channel.spy");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var channel = channels.stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        var state = channel.getSpyIDs().contains(playerId);
        if (state) {
            if (channel.getSpyIDs().remove(playerId))
                return text("You stopped spying on ", GOLD).append(channel.toComponent(playerId));
            else throw CommandException.unsuccessful("could not unspy on channel " + channelName);
        } else {
            if (channel.getSpyIDs().add(playerId))
                return text("You are spying on ", GOLD).append(channel.toComponent(playerId));
            else throw CommandException.unsuccessful("could not spy on channel " + channelName);
        }
    }

    private Component shout(@NotNull CommandSender sender, String channelName, String msg) {
        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var channel = channels.stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        var content     = new ChatMessageParser().parse(msg);
        var basicPlayer = com.ampznetwork.libmod.api.entity.Player.basic(playerId, player.getName());
        var message     = new ChatMessage(basicPlayer, player.getDisplayName(), msg, content);
        var packet = new ChatMessagePacketImpl(PacketType.CHAT,
                serverName,
                channel.getName(),
                message,
                new ArrayList<>());
        outbound(channel, packet);
        return text("Message shouted: ").append(content);
    }

    private Component channelInfoComponent(Channel channel, UUID playerId) {
        var alias = channel.getAlias();
        var text = text("Channel: ", AQUA).append(channel.toComponent())
                .append(text("\n - Alias: ", AQUA))
                .append(alias == null ? text("(none)", GRAY) : text(channel.getAlias(), YELLOW));
        if (hasAccess(playerId, channel) && channel.getPermission() instanceof String permission) text = text.append(
                text("\n - Permission: ", AQUA)).append(text(permission, YELLOW));
        var state = channel.getState(playerId);
        return text.append(text("\n - Status: ")).append(state.toComponent());
    }

    private boolean hasAccess(UUID id, Channel channel) {
        return channel.getPermission() == null || permissionAdapter.checkPermissionOrOp(id,
                "chat.channel." + channel.getName());
    }
}
