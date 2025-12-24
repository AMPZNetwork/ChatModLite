package com.ampznetwork.chatmod.lite.model;

import com.ampznetwork.libmod.api.entity.Player;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

import java.util.Objects;

public enum PlaceholderAdapter {
    Native {
        @Override
        public String applyPlaceholders(String serverName, String channelName, OfflinePlayer player, String text) {
            return text.replaceAll("%server_name%", serverName)
                    .replaceAll("%channel_name%", channelName)
                    .replaceAll("%player_name%",
                            Objects.requireNonNullElseGet(player.getName(),
                                    () -> Player.fetchUsername(player.getUniqueId()).join()));
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
