package com.ampznetwork.chatmod.lite.hytale;

import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;
import com.ampznetwork.chatmod.api.util.ChatMessageParser;
import com.ampznetwork.chatmod.lite.ChatModCore;
import com.ampznetwork.chatmod.lite.config.HytaleConfigFile;
import com.ampznetwork.chatmod.lite.hytale.command.ChannelCommand;
import com.ampznetwork.chatmod.lite.hytale.command.ShoutCommand;
import com.ampznetwork.chatmod.lite.model.abstr.ChatDispatcher;
import com.ampznetwork.chatmod.lite.model.abstr.PacketCaster;
import com.ampznetwork.chatmod.lite.model.abstr.PlayerAdapter;
import com.ampznetwork.libmod.api.entity.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.java.Log;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Log
@Value
@EqualsAndHashCode(callSuper = true)
public class ChatModLiteHytale extends JavaPlugin implements ChatDispatcher, PlayerAdapter, PacketCaster {
    HytaleConfigFile     config;
    ChatModCore          core;
    Map<UUID, PlayerRef> playerRefs = new ConcurrentHashMap<>();

    public ChatModLiteHytale(@NotNull JavaPluginInit init) {
        super(init);

        this.config = new HytaleConfigFile();
        config.syncLoad();

        this.core = new ChatModCore(config, this, this, new HytalePermissionAdapter(), this);
    }

    @Override
    protected void setup() {
        config.syncLoad();

        super.setup();

        var eventRegistry = getEventRegistry();
        eventRegistry.registerAsyncGlobal(PlayerChatEvent.class, event -> event.thenApply(this::handleChat));

        var commandRegistry = getCommandRegistry();
        commandRegistry.registerCommand(new ShoutCommand());
        commandRegistry.registerCommand(new ChannelCommand());
    }

    @Override
    public void sendToPlayer(ComponentLike component, @NotNull Player player) {

        var content = HytaleComponentSerializer.INSTANCE.serialize(((TextComponent) component.asComponent()));
        var message = new Message(content);
        // todo
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

        var       message   = packet.getMessage();
        var       sender    = message.getSender();
        Component formatted = message.getFullText();

        /*
        if (sender != null && sender instanceof OfflinePlayer player) {
            formatted = formatMessage(packet.getSource(), channel.getAlternateName(), player, formatted);
        }
         */

        core.localcast(channel, formatted);
    }

    @Override
    public Player getPlayer(UUID playerId) {
        return Player.basic(playerId, "Hytale User"); // todo
    }

    private PlayerChatEvent handleChat(PlayerChatEvent event) {
        var player  = touch(event.getSender());
        var channel = core.activeChannels(player).findAny().orElseGet(() -> core.getChannels().getFirst());

        var text = new ChatMessageParser().parse(event.getContent());
        core.localcast(channel, text);

        event.setCancelled(true);
        return event;
    }

    private Player touch(PlayerRef player) {
        var playerId = player.getUuid();
        playerRefs.compute(playerId, (k, v) -> player);
        return getPlayer(playerId);
    }

    Stream<PlayerRef> getPlayerRef(UUID uuid) {
        return playerRefs.entrySet()
                .stream()
                .filter(e -> e.getKey().equals(uuid))
                .map(Map.Entry::getValue)
                .filter(PlayerRef::isValid);
    }
}
