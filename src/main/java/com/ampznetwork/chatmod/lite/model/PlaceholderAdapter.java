package com.ampznetwork.chatmod.lite.model;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

public enum PlaceholderAdapter {
    Native {
        @Override
        public String applyPlaceholders(String serverName, String channelName, OfflinePlayer player, String text) {
            return text.replaceAll("%server_name%", serverName)
                    .replaceAll("%channel_name%", channelName)
                    .replaceAll("%player_name%", player.getName());
        }
    }, Hook {
        @Override
        public String applyPlaceholders(String serverName, String channelName, OfflinePlayer player, String text) {
            return PlaceholderAPI.setPlaceholders(player,
                    Native.applyPlaceholders(serverName, channelName, player, text));
        }
    };

    public abstract String applyPlaceholders(String serverName, String channelName, OfflinePlayer player, String text);
}
