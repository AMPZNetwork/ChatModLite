package com.ampznetwork.chatmod.lite.model;

import com.ampznetwork.chatmod.api.model.protocol.ChatMessagePacket;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.Value;
import org.comroid.api.ByteConverter;

@Value
public class JacksonPacketConverter implements ByteConverter<ChatMessagePacket> {
    ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public byte[] toBytes(ChatMessagePacket it) {
        return objectMapper.writeValueAsBytes(it);
    }

    @Override
    @SneakyThrows
    public ChatMessagePacket fromBytes(byte[] bytes) {
        return objectMapper.readValue(bytes, ChatMessagePacket.class);
    }
}
