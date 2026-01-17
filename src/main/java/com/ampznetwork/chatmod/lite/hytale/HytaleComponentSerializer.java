package com.ampznetwork.chatmod.lite.hytale;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.MaybeBool;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Value;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import org.comroid.annotations.Instance;
import org.comroid.api.func.util.Streams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/// todo: message params, params, link, markup, messageId?
@Value
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HytaleComponentSerializer implements ComponentSerializer<TextComponent, TextComponent, FormattedMessage> {
    @Instance public static final HytaleComponentSerializer INSTANCE = new HytaleComponentSerializer();

    @Override
    public @NotNull TextComponent deserialize(@NotNull FormattedMessage input) {
        var text = convertSingleComponent(input);

        if (input.children != null) for (var child : input.children) {
            var converted = convertSingleComponent(child);
            text.append(converted);
        }

        return text.build();
    }

    @Override
    public @NotNull FormattedMessage serialize(@NotNull TextComponent component) {
        var msg = convertSingleComponent(component);

        msg.children = component.children()
                .stream()
                .flatMap(Streams.cast(TextComponent.class))
                .map(this::convertSingleComponent)
                .toArray(FormattedMessage[]::new);

        return msg;
    }

    private @NotNull TextComponent.Builder convertSingleComponent(@NotNull FormattedMessage input) {
        var text = Component.text();

        text.content(Objects.requireNonNullElse(input.rawText, input.messageId));

        if (input.color != null) text.color(parseHytaleColor(input.color));

        text.decoration(TextDecoration.BOLD, input.bold == MaybeBool.True);
        text.decoration(TextDecoration.ITALIC, input.italic == MaybeBool.True);
        text.decoration(TextDecoration.UNDERLINED, input.underlined == MaybeBool.True);

        // todo: monospaced font

        return text;
    }

    private FormattedMessage convertSingleComponent(@NotNull TextComponent component) {
        var content = component.content();

        var color = component.color();

        var bold = component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
                   ? MaybeBool.True
                   : MaybeBool.False;
        var italic = component.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE
                     ? MaybeBool.True
                     : MaybeBool.False;
        var underlined = component.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE
                         ? MaybeBool.True
                         : MaybeBool.False;

        // todo: monospaced font

        return new FormattedMessage(content,
                null,
                null,
                null,
                null,
                color == null ? null : color.asHexString(),
                bold,
                italic,
                MaybeBool.Null,
                underlined,
                null,
                false);
    }

    private @Nullable TextColor parseHytaleColor(String $color) {
        // todo: gradients
        final var color = $color.split(":")[0];

        if (color.matches("\\d([.,]\\d+)?")) {
            // 0-1 greyscale
            var rate = Double.parseDouble(color);
            var x    = (int) (255d * (1d - rate));
            return TextColor.color(x, x, x);
        } else if (color.matches("#?[0-9a-fA-F]{3,6}")) {
            // rgba hex
            var hex = Integer.parseInt(color, 16);
            return TextColor.color(hex);
        } else // named
            return NamedTextColor.NAMES.keyToValue()
                    .entrySet()
                    .stream()
                    .filter(e -> e.getKey().toLowerCase().contains(color.toLowerCase()))
                    .findAny()
                    .map(Map.Entry::getValue)
                    .orElse(null);
    }
}
