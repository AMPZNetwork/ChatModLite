package com.ampznetwork.chatmod.lite.model;

import com.ampznetwork.chatmod.api.model.config.channel.Channel;

import java.util.List;

public record Config(
        Server server,
        Modules modules,
        List<Channel> channels,
        Formatting formatting,
        Compatibility compatibility
) {
    public record Server(String name) {}

    public record Modules(RabbitMq rabbitMq) {
        public record RabbitMq(String rabbitUri) {}
    }

    public record Formatting(String scheme) {}

    public record Compatibility(boolean listeners) {}
}
