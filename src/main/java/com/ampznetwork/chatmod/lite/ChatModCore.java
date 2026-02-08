package com.ampznetwork.chatmod.lite;

import com.ampznetwork.chatmod.api.model.config.channel.Channel;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessage;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;
import com.ampznetwork.chatmod.api.model.protocol.PacketType;
import com.ampznetwork.chatmod.api.parse.ChatMessageParser;
import com.ampznetwork.chatmod.lite.lang.Words;
import com.ampznetwork.chatmod.lite.model.JacksonPacketConverter;
import com.ampznetwork.chatmod.lite.model.abstr.ChannelConfigProvider;
import com.ampznetwork.chatmod.lite.model.abstr.ChatDispatcher;
import com.ampznetwork.chatmod.lite.model.abstr.ChatModConfig;
import com.ampznetwork.chatmod.lite.model.abstr.PacketCaster;
import com.ampznetwork.chatmod.lite.model.abstr.PlayerAdapter;
import com.ampznetwork.chatmod.lite.model.exception.CommandException;
import com.ampznetwork.chatmod.lite.model.exception.PermissionException;
import com.ampznetwork.libmod.api.entity.Player;
import com.ampznetwork.libmod.api.util.Util;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import lombok.extern.java.Log;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.advancement.Advancement;
import org.comroid.api.net.Rabbit;
import org.comroid.commands.model.permission.PermissionAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;

import static net.kyori.adventure.text.Component.*;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@Log
@Value
public class ChatModCore implements ChannelConfigProvider {
    private static ChatModCore instance;

    public static ChatModCore get() {
        return instance;
    }

    ChatModConfig                                          config;
    ChatDispatcher                                         dispatcher;
    PlayerAdapter                                          playerAdapter;
    PermissionAdapter                                      permissionAdapter;
    PacketCaster                                           packetCaster;
    ChannelConfigProvider channelProvider;
    ObjectMapper                                           objectMapper = new ObjectMapper();
    Map<Channel, Rabbit.Exchange.Route<ChatMessagePacket>> mqChannels   = new ConcurrentHashMap<>();

    {
        instance = this;
    }

    @Override
    public List<Channel> getChannels() {
        return channelProvider.getChannels();
    }

    public void playerJoin(Player player) {
        var id = player.getId();

        // join init channel
        var first = getChannels().getFirst();
        first.getPlayerIDs().add(id);
        first.getSpyIDs().add(id);

        // auto-spy channels
        getChannels().stream()
                .filter(channel -> getPermissionAdapter().checkPermissionOrOp(id,
                        "chat.autospy." + channel.getName(),
                        true))
                .peek(channel -> {
                    if (!hasAccess(id,
                            channel)) log.warning(("Player %s has auto-join permission for channel %s but does not " + "have access to the " + "channel").formatted(
                            player.getName(),
                            channel.getName()));
                })
                .forEach(channel -> channel.getSpyIDs().add(id));

        // send join message
        var message = createJoinMessage(player);
        var packet = new ChatMessagePacket(PacketType.JOIN,
                config.getServerName(),
                first.getName(),
                message,
                List.of(config.getServerName()));
        outbound(first, packet);
    }

    public void playerLeave(Player player, Channel channel) {
        var id = player.getId();
        for (var each : channelProvider.getChannels()) each.getPlayerIDs().remove(id);

        // send leave message
        var message = createLeaveMessage(player);
        var packet = new ChatMessagePacket(PacketType.LEAVE,
                config.getServerName(),
                channel.getName(),
                message,
                List.of(config.getServerName()));
        outbound(channel, packet);
    }

    public void execAndRespond(
            @NotNull com.ampznetwork.libmod.api.entity.Player player,
            Consumer<ComponentLike> fallbackResponder, Supplier<ComponentLike> exec
    ) {
        try {
            ComponentLike component;
            try {
                component = exec.get();
            } catch (CommandException cex) {
                component = cex.toComponent();
            }
            if (component != null) dispatcher.sendToPlayer(component, player);
        } catch (Throwable t) {
            fallbackResponder.accept(text("An internal error occurred", RED));
            log.log(Level.WARNING, "Could not execute command", t);
        }
    }

    public void outbound(Channel channel, ChatMessagePacket packet) {
        if (channel.isPublish()) send(packet);
        else packetCaster.localcastPacket(packet);
    }

    public void send(ChatMessagePacket packet) {
        var channel = packet.getChannel();
        var mq = mqChannels.entrySet()
                .stream()
                .filter(e -> e.getKey().getName().equals(channel))
                .findAny()
                .map(Map.Entry::getValue)
                .orElse(null);

        if (mq == null) {
            log.warning("No MQ binding found for channel " + channel);
            return;
        }

        mq.send(packet);
    }

    public void localcast(Channel channel, Component component) {
        channel.allPlayerIDs()
                .map(playerAdapter::getPlayer)
                .distinct()
                .filter(Objects::nonNull)
                .filter(player -> hasAccess(player.getId(), channel))
                .forEach(player -> dispatcher.sendToPlayer(component, player));
    }

    public void requireAnyPermission(@Nullable Player player, @NotNull String perm) {
        if (player == null)
            // not player; thus console or fakeplayer; allow
            return;
        var result = permissionAdapter.checkPermissionOrOp(player.getId(), perm, true);
        if (!result) throw new PermissionException(perm);
    }

    public Optional<Channel> channel(String named) {
        return channelProvider.getChannels()
                .stream()
                .filter(chl -> chl.getName().equalsIgnoreCase(named) || (chl.getAlias() != null && chl.getAlias()
                        .equalsIgnoreCase(named)))
                .findAny();
    }

    public Stream<Channel> availableChannels(Player player) {
        return channelProvider.getChannels().stream().filter(channel -> hasAccess(player.getId(), channel));
    }

    public Stream<Channel> activeChannels(Player player) {
        var playerId = player.getId();
        return Stream.concat(channelProvider.getChannels()
                                .stream()
                                .filter(channel -> channel.getPlayerIDs().contains(playerId)),
                        channelProvider.getChannels().stream().filter(channel -> channel.getSpyIDs().contains(playerId)))
                .filter(channel -> hasAccess(playerId, channel));
    }

    public Component status(@NotNull Player player) {
        requireAnyPermission(player, "chatmod.status");

        var playerId = player.getId();
        return text("Current channelProvider.getChannels():\n",
                BLUE).append(activeChannels(player).sorted(Comparator.comparingInt(channel -> channel.getSpyIDs()
                                                                                                      .contains(playerId)
                                                                                              ? 1
                                                                                              : 0))
                .map(channel -> channel.toComponent(playerId))
                .collect(Util.Kyori.collector(text("\n"))));
    }

    public ComponentLike list(@NotNull Player player) {
        requireAnyPermission(player, "chatmod.channel.list");

        var playerId = player.getId();
        return text("Available channelProvider.getChannels():\n",
                BLUE).append(availableChannels(player).map(channel -> channelInfoComponent(channel, playerId))
                .collect(Util.Kyori.collector(text("\n"))));
    }

    public Component info(@NotNull Player player, String channelName) {
        requireAnyPermission(player, "chatmod.channel.info");

        var playerId = player.getId();
        var channel = channelProvider.getChannels()
                .stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        return channelInfoComponent(channel, playerId);
    }

    public Component join(@NotNull Player player, String channelName) {
        requireAnyPermission(player, "chatmod.channel.join");

        var playerId = player.getId();
        var channel = channelProvider.getChannels()
                .stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        if (channel.getPlayerIDs().contains(playerId)) throw new CommandException("You already joined this channel");
        var previous = activeChannels(player).filter(it -> it.getPlayerIDs().remove(playerId)).count();
        log.fine("Removed player %s from %d previously joined channelProvider.getChannels()".formatted(player,
                previous));
        channel.getPlayerIDs().add(playerId);
        return text("Joined channel ", BLUE).append(channel.toComponent());
    }

    public Component leave(@NotNull Player player) {
        requireAnyPermission(player, "chatmod.channel.leave");

        var playerId = player.getId();
        var count    = activeChannels(player).filter(channel -> channel.getPlayerIDs().remove(playerId)).count();
        return text("You were removed from ", BLUE).append(text(count, GREEN))
                .append(text(" " + Words.CHANNEL.apply(count), BLUE));
    }

    public Component spy(@NotNull Player player, String channelName) {
        requireAnyPermission(player, "chatmod.channel.spy");

        var playerId = player.getId();
        var channel = channelProvider.getChannels()
                .stream()
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

    public Component shout(@NotNull Player player, String channelName, String msg) {
        var playerId = player.getId();
        var channel = channelProvider.getChannels()
                .stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        var playerName  = player.getName();
        var basicPlayer = com.ampznetwork.libmod.api.entity.Player.basic(playerId, playerName);
        var bundle      = ChatMessageParser.parse(msg, config, channel, basicPlayer, playerName);
        var message     = new ChatMessage(basicPlayer, player.getBestName(), bundle);
        var packet = new ChatMessagePacket(PacketType.CHAT,
                config.getServerName(),
                channel.getName(),
                message,
                List.of());
        outbound(channel, packet);
        return text("Message shouted", GREEN);
    }

    public Component channelInfoComponent(Channel channel, UUID playerId) {
        var alias = channel.getAlias();
        var text = text("Channel: ", AQUA).append(channel.toComponent())
                .append(text("\n - Alias: ", AQUA))
                .append(alias == null ? text("(none)", GRAY) : text(channel.getAlias(), YELLOW));
        if (hasAccess(playerId, channel) && channel.getPermission() instanceof String permission) text = text.append(
                text("\n - Permission: ", AQUA)).append(text(permission, YELLOW));
        var state = channel.getState(playerId);
        return text.append(text("\n - Status: ")).append(state.toComponent());
    }

    public ChatMessage createJoinMessage(Player player) {
        var playerName = player.getName();
        return new ChatMessage(com.ampznetwork.libmod.api.entity.Player.basic(player.getId(), playerName),
                playerName,
                new ChatMessageParser.MessageBundle(text(player.getName() + " ", GREEN),
                        text("> ▶️ Joined the game", YELLOW),
                        null));
    }

    public ChatMessage createLeaveMessage(Player player) {
        var playerName = player.getName();
        return new ChatMessage(com.ampznetwork.libmod.api.entity.Player.basic(player.getId(), playerName),
                playerName,
                new ChatMessageParser.MessageBundle(text(player.getName() + " ", GREEN),
                        text("> ▶️ Left the game", YELLOW),
                        null));
    }

    public ChatMessage createAdvancementMessage(Player player, Advancement advancement) {
        var playerName = player.getName();
        var display    = advancement.getDisplay();
        assert display != null : "should be unreachable";
        var displayText = "> \uD83C\uDFC6 Completed the advancement \"" + display.getTitle() + "\"\n> `" + display.getDescription() + "`";
        return new ChatMessage(com.ampznetwork.libmod.api.entity.Player.basic(player.getId(), playerName),
                playerName,
                new ChatMessageParser.MessageBundle(text(player.getName() + " ", GREEN), text(displayText), null));
    }

    public ChatMessage createDeathMessage(Player player, String deathMessage) {
        var playerName = player.getName();
        return new ChatMessage(com.ampznetwork.libmod.api.entity.Player.basic(player.getId(), playerName),
                playerName,
                new ChatMessageParser.MessageBundle(text(player.getName() + " ", GREEN),
                        text("> ☠️ " + deathMessage),
                        null));
    }

    public boolean hasAccess(UUID id, Channel channel) {
        return channel.getPermission() == null || permissionAdapter.checkPermissionOrOp(id,
                "chat.channel." + channel.getName());
    }

    public void loadMqChannels() {
        var exchange = config.getRabbit().exchange("minecraft", "topic");
        for (var channel : getChannels()) {
            if (!channel.isPublish()) continue;
            var route = exchange.route(Util.Kyori.sanitizePlain(config.getServerName() + ".chat." + channel.getName())
                    .toLowerCase(), "chat." + channel.getName(), new JacksonPacketConverter(getObjectMapper()));
            route.subscribeData(this::directToLocalChannel);
            getMqChannels().put(channel, route);
        }
        log.info("Created %d RabbitMQ bindings".formatted(getMqChannels().size()));
    }

    private void directToLocalChannel(ChatMessagePacket packet) {
        var channelName = packet.getChannel();

        if (!channelName.startsWith("@")) {
            packetCaster.localcastPacket(packet);
            return;
        }

        var uuid      = UUID.fromString(channelName.substring(1));
        var recipient = playerAdapter.getPlayer(uuid);
        dispatcher.sendToPlayer(packet.getMessage().getFullText(), recipient);
    }
}
