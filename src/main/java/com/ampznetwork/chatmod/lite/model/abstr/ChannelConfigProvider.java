package com.ampznetwork.chatmod.lite.model.abstr;

import com.ampznetwork.chatmod.api.model.config.channel.Channel;

import java.util.List;

public interface ChannelConfigProvider {
    List<Channel> getChannels();
}
