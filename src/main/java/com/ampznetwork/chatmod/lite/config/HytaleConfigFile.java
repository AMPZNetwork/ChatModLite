package com.ampznetwork.chatmod.lite.config;

import com.ampznetwork.chatmod.api.model.config.channel.Channel;
import com.ampznetwork.chatmod.lite.model.Config;
import com.ampznetwork.chatmod.lite.model.abstr.ChannelConfigProvider;
import com.ampznetwork.chatmod.lite.model.abstr.ChatModConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hypixel.hytale.server.core.util.io.BlockingDiskFile;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.comroid.api.net.Rabbit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

@Value
@EqualsAndHashCode(callSuper = true)
public class HytaleConfigFile extends BlockingDiskFile implements ChatModConfig, ChannelConfigProvider {
    ObjectMapper objectMapper;
    @NonFinal Config obj;
    @NonFinal Rabbit rabbit;

    public HytaleConfigFile() {
        super(Path.of(".", "config", "chatmod.json"));

        var dir = Path.of(".", "config").toFile();
        if (!dir.exists())
            if (!dir.mkdirs() && dir.exists())
                throw new RuntimeException("Could not create config dir");

        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getServerName() {
        return obj.server().name();
    }

    @Override
    public String getFormattingScheme() {
        return obj.formatting().scheme();
    }

    @Override
    public List<Channel> getChannels() {
        return obj.channels();
    }

    @Override
    protected void read(BufferedReader bufferedReader) throws IOException {
        var oldRabbit = rabbit;

        obj = objectMapper.readValue(bufferedReader, Config.class);

        refreshRabbit:
        {
            var newRabbitUri = obj.modules().rabbitMq().rabbitUri();
            if (oldRabbit != null && oldRabbit.getUri().toString().equals(newRabbitUri)) break refreshRabbit;
            rabbit = Rabbit.of(newRabbitUri).assertion();
        }
    }

    @Override
    protected void write(BufferedWriter bufferedWriter) throws IOException {
        objectMapper.writeValue(bufferedWriter, obj);
    }

    @Override
    protected void create(BufferedWriter bufferedWriter) throws IOException {
        try (var is = HytaleConfigFile.class.getResourceAsStream("/hytale.config.json")) {
            if (is == null) return;
            try (var isr = new InputStreamReader(is)) {
                isr.transferTo(bufferedWriter);
            }
        }
    }
}
