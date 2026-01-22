package com.ampznetwork.chatmod.lite.hytale.command;

import com.ampznetwork.chatmod.generated.PluginYml;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class ShoutCommand extends AbstractCommand {
    private final RequiredArg<String> argChannel;
    private final OptionalArg<String> argMessage;

    public ShoutCommand() {
        super("shout", "Shout into a channel");

        requirePermission(PluginYml.Command.SHOUT.getRequiredPermission());

        argChannel = withRequiredArg("channelName", "Channel to shout into", ArgTypes.STRING);
        argMessage = withOptionalArg("message", "Message to shout", ArgTypes.STRING);
    }

    @Override
    protected @Nullable CompletableFuture<Void> execute(@NotNull CommandContext commandContext) {
        //commandContext.sendMessage();
        return CompletableFuture.completedFuture(null); // todo
    }
}
