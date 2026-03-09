package com.ampznetwork.chatmod.lite.spigot;

import com.ampznetwork.chatmod.lite.model.RecipientSerializer;
import com.ampznetwork.libmod.api.entity.Player;
import lombok.Value;
import org.comroid.api.data.RegExpUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Value
public class SpigotRecipientSerializer implements RecipientSerializer {
    ChatModLiteSpigot plugin;

    @Override
    public @Nullable Player deserializeRecipient(String recipient) {
        return recipient.matches(RegExpUtil.UUID4_PATTERN)
               ? plugin.getPlayer(UUID.fromString(recipient))
               : Arrays.stream(plugin.getServer().getOfflinePlayers())
                       .filter(player -> Objects.equals(player.getName(), recipient))
                       .findAny()
                       .map(player -> Player.basic(player.getUniqueId(), player.getName()))
                       .orElse(null);
    }
}
