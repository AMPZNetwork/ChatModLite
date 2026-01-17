package com.ampznetwork.chatmod.lite.hytale.command;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class ChannelCommand extends AbstractCommand {
    public ChannelCommand() {
        super("channel", "Change the active channel");
    }

    @Override
    protected @Nullable CompletableFuture<Void> execute(@NotNull CommandContext commandContext) {
        return CompletableFuture.completedFuture(null); // todo
    }
}
