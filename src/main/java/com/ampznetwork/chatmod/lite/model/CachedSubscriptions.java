package com.ampznetwork.chatmod.lite.model;

import java.util.List;
import java.util.UUID;

public record CachedSubscriptions(List<ChannelEntry> entries) {
    public record ChannelEntry(String name, List<UUID> spies) {}
}
