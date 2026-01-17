package com.ampznetwork.chatmod.lite.model.abstr;

import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;

public interface PacketCaster {
    void localcastPacket(ChatMessagePacket packet);
}
