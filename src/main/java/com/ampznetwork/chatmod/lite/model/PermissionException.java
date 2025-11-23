package com.ampznetwork.chatmod.lite.model;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.bukkit.ChatColor;

@Value
@EqualsAndHashCode(callSuper = true)
public class PermissionException extends RuntimeException {
    String permission;

    @Override
    public String toString() {
        return ChatColor.RED + "You are missing the required permission " + ChatColor.YELLOW + permission;
    }
}
