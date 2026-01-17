package com.ampznetwork.chatmod.lite.model.abstr;

import com.ampznetwork.libmod.api.entity.Player;

import java.util.UUID;

public interface PlayerAdapter {
    Player getPlayer(UUID playerId);
}
