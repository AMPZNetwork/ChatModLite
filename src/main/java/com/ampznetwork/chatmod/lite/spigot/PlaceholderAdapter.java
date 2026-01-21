package com.ampznetwork.chatmod.lite.spigot;

import com.ampznetwork.libmod.api.entity.Player;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;

import java.util.Objects;

public enum PlaceholderAdapter {
    Native {
        @Override
        public String applyPlaceholders(String serverName, String channelName, Player player, String text) {
            return text.replaceAll("%server_name%",
                            serverName)
                    .replaceAll("%channel_name%", channelName)
                    .replaceAll("%player_name%",
                            Objects.requireNonNullElseGet(player.getName(),
                                    () -> Player.fetchUsername(player.getId()).join()));
        }
    }, Hook {
        @Override
        public String applyPlaceholders(String serverName, String channelName, Player player, String text) {
            var offlinePlayer = Bukkit.getServer()
                    .getOfflinePlayer(player.getId());
            return PlaceholderAPI.setPlaceholders(offlinePlayer,
                    Native.applyPlaceholders(serverName, channelName, player, text));
        }
    };

    public abstract String applyPlaceholders(String serverName, String channelName, Player player, String text);
}
