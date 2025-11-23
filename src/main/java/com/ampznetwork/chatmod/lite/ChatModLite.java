package com.ampznetwork.chatmod.lite;

import com.ampznetwork.chatmod.api.model.config.ChatModules;
import com.ampznetwork.chatmod.api.model.config.channel.Channel;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessage;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;
import com.ampznetwork.chatmod.api.model.protocol.internal.ChatMessagePacketImpl;
import com.ampznetwork.chatmod.api.model.protocol.internal.PacketType;
import com.ampznetwork.chatmod.lite.lang.Words;
import com.ampznetwork.chatmod.lite.model.CommandException;
import com.ampznetwork.chatmod.lite.model.JacksonPacketConverter;
import com.ampznetwork.chatmod.lite.model.PermissionException;
import com.ampznetwork.libmod.api.util.Util;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.NonFinal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.*;
import static net.kyori.adventure.text.Component.*;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@Value
@EqualsAndHashCode(callSuper = true)
public class ChatModLite extends JavaPlugin implements Listener {
    ObjectMapper                                           objectMapper      = new ObjectMapper();
    MinecraftPermissionAdapter                             permissionAdapter = MinecraftPermissionAdapter.spigot();
    List<Channel>                                          channels          = new ArrayList<>();
    Map<Channel, Rabbit.Exchange.Route<ChatMessagePacket>> mqChannels        = new ConcurrentHashMap<>();
    @NonFinal String  serverName;
    @NonFinal Rabbit  rabbit;
    @NonFinal boolean compatibilityMode;

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args
    ) {
        String channelName;
        try {
            switch (args.length) {
                case 1:
                    switch (args[0]) {
                        case "reload":
                            requireAnyPermission(sender, "chatmod.reload");
                            execAndRespond(sender, () -> reload());
                            break;
                        case "status":
                            requireAnyPermission(sender, "chatmod.status");
                            execAndRespond(sender, () -> status(sender));
                            break;
                    }
                    break;
                case 2:
                    switch (args[0]) {
                        case "channel":
                            switch (args[1]) {
                                case "list":
                                    requireAnyPermission(sender, "chatmod.channel.list");
                                    execAndRespond(sender, () -> list(sender));
                                    break;
                                case "leave":
                                    requireAnyPermission(sender, "chatmod.channel.leave");
                                    execAndRespond(sender, () -> leave(sender));
                                    break;
                            }
                            break;
                        case "shout":
                            throw CommandException.notEnoughArgs("missing message");
                    }
                    break;
                case 3:
                    switch (args[0]) {
                        case "channel":
                            channelName = args[2];
                            switch (args[1]) {
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
                        case "shout":
                            channelName = args[1];
                            var msg = args[2];
                            execAndRespond(sender, () -> shout(sender, channelName, msg));
                    }
                    break;
                default:
                    switch (args[0]) {
                        case "shout":
                            channelName = args[1];
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
            case 1 -> switch (args[0]) {
                case "channel" -> List.of("list", "info", "join", "leave", "spy");
                case "shout" -> channelNames(sender);
                default -> emptyList();
            };
            case 2 -> switch (args[0]) {
                case "channel" -> switch (args[1]) {
                    case "join", "spy" -> channelNames(sender);
                    default -> emptyList();
                };
                case "shout" -> channelNames(sender).contains(args[1]) ? List.of("<message>") : emptyList();
                default -> emptyList();
            };
            default -> "shout".equals(args[0]) ? List.of("<message>") : emptyList();
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

        var section = cfg.getConfigurationSection("channels");
        if (section != null) for (var channelName : section.getKeys(false))
            channels.add(new Channel(true,
                    channelName,
                    section.getString("alias", null),
                    section.getString("permission", null),
                    null,
                    section.getBoolean("publish", true)));

        for (var channel : channels)
            mqChannels.put(channel,
                    rabbit.bind(null,
                            "minecraft.chat",
                            "fanout",
                            channel.getName(),
                            new JacksonPacketConverter(objectMapper)));
    }

    private void execAndRespond(@NotNull CommandSender sender, Supplier<ComponentLike> exec) {
        try {
            ComponentLike component;
            try {
                component = exec.get();
            } catch (CommandException cex) {
                component = cex.toComponent();
            }
            var serialized = BungeeComponentSerializer.get().serialize(component.asComponent());
            sender.spigot().sendMessage(serialized);
        } catch (Throwable t) {
            sender.sendMessage(ChatColor.RED + "An internal error occurred");
            getLogger().log(Level.WARNING, "Could not execute command", t);
        }
    }

    private List<String> channelNames(CommandSender sender) {
        if (sender instanceof Player player) return channels(player).map(ChatModules.NamedBaseConfig::getName).toList();
        return emptyList();
    }

    private void send(Channel channel, PacketType type, ChatMessage message) {
        var packet = new ChatMessagePacketImpl(type, serverName, channel.getName(), message, new ArrayList<>());
    }

    private void requireAnyPermission(@NotNull CommandSender sender, String perm) {
        if (!(sender instanceof Player player))
            // not player; thus console or fakeplayer; allow
            return;
        var result = permissionAdapter.checkPermission(player.getUniqueId(), perm, true);
        if (!result.toBooleanOrElse(false)) throw new PermissionException(perm);
    }

    private Player requirePlayer(@NotNull CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new CommandException("Only players can execute this command");
    }

    private Stream<Channel> channels(Player player) {
        var playerId = player.getUniqueId();
        return Stream.concat(channels.stream().filter(channel -> channel.getPlayerIDs().contains(playerId)),
                        channels.stream().filter(channel -> channel.getSpyIDs().contains(playerId)))
                .filter(channel -> channel.getPermission() == null || permissionAdapter.checkPermission(playerId,
                        channel.getPermission()).toBooleanOrElse(false));
    }

    private Component reload() {
        onDisable();
        reloadConfig();
        onEnable();
        return text("Reload complete!", GREEN);
    }

    private Component status(@NotNull CommandSender sender) {
        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        return text("Current channels:\n", BLUE).append(channels(player).sorted(Comparator.comparingInt(channel ->
                channel.getSpyIDs().contains(playerId)
                ? 1
                : 0)).map(channel -> channel.toComponent(playerId)).collect(Util.Kyori.collector(text("\n"))));
    }

    private ComponentLike list(@NotNull CommandSender sender) {
        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        return text("Available channels:\n", BLUE).append(channels(player).map(channel -> channel.toComponent(playerId))
                .collect(Util.Kyori.collector(text("\n"))));
    }

    private Component info(@NotNull CommandSender sender, String channelName) {
        requireAnyPermission(sender, "chatmod.channel.info");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var channel = channels.stream()
                .filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        var alias = channel.getAlias();
        var text = text("Channel: ", BLUE).append(text(channelName, AQUA))
                .append(text("\n - Alias: ", BLUE))
                .append(alias == null ? text("(none)", GRAY) : text(channel.getAlias(), AQUA));
        if (permissionAdapter.checkOpLevel(playerId) && channel.getPermission() instanceof String perm) text = text.append(
                text("\n - Permission: ")).append(text(perm, AQUA));
        var state = channel.getState(playerId);
        text = text.append(text("\n - Status: ")).append(state.toComponent());
        return text;
    }

    private Component join(@NotNull CommandSender sender, String channelName) {
        requireAnyPermission(sender, "chatmod.channel.join");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var channel = channels(player).filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
        if (channel.getPlayerIDs().contains(playerId)) throw new CommandException("You already joined this channel");
        var previous = channels(player).filter(it -> it.getPlayerIDs().remove(playerId)).count();
        getLogger().fine("Removed player %s from %d previously joined channels".formatted(player, previous));
        channel.getPlayerIDs().add(playerId);
        return text("Joined channel ", BLUE).append(channel.toComponent());
    }

    private Component leave(@NotNull CommandSender sender) {
        requireAnyPermission(sender, "chatmod.channel.leave");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var count    = channels(player).filter(channel -> channel.getPlayerIDs().remove(playerId)).count();
        return text("You were removed from ", BLUE).append(text(count, GREEN))
                .append(text(" " + Words.CHANNEL.apply(count), BLUE));
    }

    private Component spy(@NotNull CommandSender sender, String channelName) {
        requireAnyPermission(sender, "chatmod.channel.spy");

        var player   = requirePlayer(sender);
        var playerId = player.getUniqueId();
        var channel = channels(player).filter(it -> channelName.equals(it.getName()))
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
        var channel = channels(player).filter(it -> channelName.equals(it.getName()))
                .findAny()
                .orElseThrow(() -> CommandException.noSuchChannel(channelName));
    }
}
