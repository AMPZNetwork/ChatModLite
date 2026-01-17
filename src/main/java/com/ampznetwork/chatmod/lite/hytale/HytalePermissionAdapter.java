package com.ampznetwork.chatmod.lite.hytale;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import lombok.Value;
import net.kyori.adventure.util.TriState;
import org.comroid.commands.model.permission.PermissionAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

@Value
public class HytalePermissionAdapter implements PermissionAdapter {
    @Override
    public boolean checkOpLevel(UUID playerId, int minimum) {
        return PermissionsModule.get().getGroupsForUser(playerId).contains("OP");
    }

    @Override
    public TriState checkPermission(UUID playerId, @NotNull String key, boolean explicit) {
        return TriState.byBoolean(PermissionsModule.get().hasPermission(playerId, key, false));
    }
}
