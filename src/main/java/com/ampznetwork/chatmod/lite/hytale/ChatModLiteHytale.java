package com.ampznetwork.chatmod.lite.hytale;

import com.ampznetwork.chatmod.api.model.protocol.ChatMessage;
import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;
import com.ampznetwork.chatmod.api.model.protocol.internal.ChatMessagePacketImpl;
import com.ampznetwork.chatmod.api.model.protocol.internal.PacketType;
import com.ampznetwork.chatmod.api.util.ChatMessageParser;
import com.ampznetwork.chatmod.lite.ChatModCore;
import com.ampznetwork.chatmod.lite.config.HytaleConfigFile;
import com.ampznetwork.chatmod.lite.hytale.command.ChannelCommand;
import com.ampznetwork.chatmod.lite.hytale.command.ShoutCommand;
import com.ampznetwork.chatmod.lite.model.abstr.ChatDispatcher;
import com.ampznetwork.chatmod.lite.model.abstr.PacketCaster;
import com.ampznetwork.chatmod.lite.model.abstr.PlayerAdapter;
import com.ampznetwork.chatmod.lite.spigot.PlaceholderAdapter;
import com.ampznetwork.libmod.api.entity.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.java.Log;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

@Log
@Value
@EqualsAndHashCode(callSuper = true)
public class ChatModLiteHytale extends JavaPlugin implements ChatDispatcher, PlayerAdapter, PacketCaster {
    HytaleConfigFile config;
    ChatModCore      core;

    public ChatModLiteHytale(@NotNull JavaPluginInit init) {
        super(init);

        this.config = new HytaleConfigFile();
        config.syncLoad();

        this.core = new ChatModCore(config, this, this, new HytalePermissionAdapter(), this, config);
    }

    @Override
    protected void setup() {
        config.syncLoad();
        core.loadMqChannels();

        super.setup();

        var eventRegistry = getEventRegistry();
        eventRegistry.registerAsyncGlobal(PlayerChatEvent.class, event -> event.thenApply(this::handleChat));
        eventRegistry.registerGlobal(PlayerConnectEvent.class, this::handleJoin);
        eventRegistry.registerGlobal(PlayerDisconnectEvent.class, this::handleLeave);

        var commandRegistry = getCommandRegistry();
        commandRegistry.registerCommand(new ShoutCommand());
        commandRegistry.registerCommand(new ChannelCommand());
    }

    private void handleJoin(PlayerConnectEvent event) {
        var player = getPlayer(event.getPlayerRef().getUuid());

        core.playerJoin(player);
    }

    private void handleLeave(PlayerDisconnectEvent event) {
        var player = getPlayer(event.getPlayerRef().getUuid());
        var first  = core.getChannels().getFirst();

        core.playerLeave(player, first);
    }

    @Override
    public void sendToPlayer(ComponentLike component, @NotNull Player player) {
        var message = HytaleComponentSerializer.INSTANCE.serialize(((TextComponent) component.asComponent()));

        getPlayerRef(player.getId()).ifPresent(ref -> ref.sendMessage(message));
    }

    @Override
    public void localcastPacket(ChatMessagePacket packet) {
        if (packet.getRoute().contains(config.getServerName())) return;

        var channelOpt = core.channel(packet.getChannel());
        if (channelOpt.isEmpty()) {
            log.warning("Received message for nonexistent channel: " + packet.getChannel());
            return;
        }
        var channel = channelOpt.get();

        var message = packet.getMessage();
        Component fullText = message.getFullText();

        var player = getPlayer(message.getSender().getId());

        var split = config.getFormattingScheme().split("%message%");

        var prefix = PlaceholderAdapter.Native.applyPlaceholders(config.getServerName(),
                channel.getAlternateName(),
                player,
                split[0]);
        var suffix = split.length < 2 ? "" : PlaceholderAdapter.Native.applyPlaceholders(config.getServerName(),
                channel.getAlternateName(),
                player,
                split[1]);

        var prefixComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
        var suffixComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(suffix);

        core.localcast(channel,
                Component.text().append(prefixComponent).append(fullText).append(suffixComponent).build());
    }

    @Override
    public Player getPlayer(UUID playerId) {
        var ref = Universe.get().getPlayer(playerId);
        return Player.basic(playerId, ref != null ? ref.getUsername() : "Hytale User");
    }

    private PlayerChatEvent handleChat(PlayerChatEvent event) {
        var player = getPlayer(event.getSender().getUuid());
        var channel = core.activeChannels(player).findAny().orElseGet(() -> core.getChannels().getFirst());

        var text    = new ChatMessageParser().parse(event.getContent());
        var message = new ChatMessage(player, event.getSender().getUsername(), event.getContent(), text);
        var packet  = new ChatMessagePacketImpl(PacketType.CHAT, config.getServerName(), channel.getName(), message);

        core.outbound(channel, packet);

        event.setCancelled(true);
        return event;
    }

    Optional<PlayerRef> getPlayerRef(UUID uuid) {
        return Optional.ofNullable(Universe.get().getPlayer(uuid));
    }
}
