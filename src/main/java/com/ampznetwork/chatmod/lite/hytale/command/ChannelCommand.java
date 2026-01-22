package com.ampznetwork.chatmod.lite.hytale.command;

import com.ampznetwork.chatmod.generated.PluginYml;
import com.ampznetwork.chatmod.lite.hytale.HytaleComponentSerializer;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import lombok.Value;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.comroid.api.attr.Named;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.CompletableFuture;

import static com.ampznetwork.chatmod.lite.ChatModCore.*;

public class ChannelCommand extends AbstractCommand {
    private final DefaultArg<ChannelAction> argAction;
    private final OptionalArg<String>       argChannel;

    public ChannelCommand() {
        super("channel", "Change the active channel");

        requirePermission(PluginYml.Command.CHANNEL.getRequiredPermission());

        argAction  = withDefaultArg("action",
                "Action to perform on channels",
                ArgTypes.forEnum("action", ChannelAction.class),
                ChannelAction.info,
                "Shows current channel information");
        argChannel = withOptionalArg("channel",
                "The channel to perform the action on",
                ArgTypes.STRING).suggest(new ChannelNameSuggester());
    }

    @Override
    protected @Nullable CompletableFuture<Void> execute(@NotNull CommandContext commandContext) {
        return CompletableFuture.supplyAsync(() -> {
            final var core   = get();
            var       action = commandContext.get(argAction);
            var       player = core.getPlayerAdapter().getPlayer(commandContext.sender().getUuid());

            var channelName = commandContext.get(argChannel);
            if (channelName == null) channelName = core.getChannels().getFirst().getName();

            Component response = Component.text("Internal Error", NamedTextColor.RED);
            switch (action) {
                case info -> response = core.info(player, channelName);
                case list -> response = core.list(player).asComponent();
                case join -> response = core.join(player, channelName);
                case leave -> response = core.leave(player);
                case spy -> response = core.spy(player, channelName);
            }

            var respond = HytaleComponentSerializer.INSTANCE.serialize((TextComponent) response);
            commandContext.sendMessage(respond);
            return null;
        });
    }

    public enum ChannelAction implements Named {
        info, list, join, leave, spy
    }

    @Value
    private static class ChannelNameSuggester implements SuggestionProvider {
        @Override
        public void suggest(
                @NonNull CommandSender commandSender, @NonNull String s, int i,
                @NonNull SuggestionResult suggestionResult
        ) {
            get().getChannels()
                    .stream()
                    .filter(channel -> channel.getPermission() == null || commandSender.hasPermission(channel.getPermission()))
                    .map(Named::getName)
                    .filter(name -> name.startsWith(s.substring(0, i)))
                    .forEach(suggestionResult::suggest);
        }
    }
}
