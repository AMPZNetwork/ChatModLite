package com.ampznetwork.chatmod.lite.model.abstr;

import com.ampznetwork.libmod.api.entity.Player;
import net.kyori.adventure.text.ComponentLike;
import org.jetbrains.annotations.NotNull;

public interface ChatDispatcher {
    void sendToPlayer(ComponentLike component, @NotNull Player player);
}
