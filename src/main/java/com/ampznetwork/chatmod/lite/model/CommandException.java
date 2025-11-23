package com.ampznetwork.chatmod.lite.model;

import lombok.EqualsAndHashCode;
import lombok.Value;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;

@Value
@EqualsAndHashCode(callSuper = true)
public class CommandException extends RuntimeException {
    public static CommandException notEnoughArgs(String missingMessage) {
        return new CommandException("Not enough arguments; " + missingMessage);
    }

    public static CommandException noSuchChannel(String channelName) {
        return new CommandException("Channel not found: " + channelName);
    }

    public static CommandException unsuccessful(String message) {
        return new CommandException("Command unsuccessful; " + message);
    }

    String message;

    public Component toComponent() {
        return Component.text("Command failed: " + message, NamedTextColor.RED);
    }

    @Override
    public String toString() {
        return ChatColor.RED + "Command failed: " + message;
    }
}
