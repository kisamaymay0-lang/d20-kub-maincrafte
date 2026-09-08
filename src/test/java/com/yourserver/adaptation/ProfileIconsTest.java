package com.yourserver.adaptation;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileIconsTest {
    @Test
    void openProfileLinkIsWhiteAndUnderlined() {
        var link = ProfileIcons.openProfile();
        assertEquals("Открыть профиль", PlainTextComponentSerializer.plainText().serialize(link));
        assertEquals(NamedTextColor.WHITE, link.color());
        assertEquals(TextDecoration.State.TRUE, link.decoration(TextDecoration.UNDERLINED));
    }

    @Test
    void votesContainCountsAndTwoImageGlyphsInsteadOfWords() {
        var row = ProfileIcons.votes(6, 0);
        assertEquals("6 \uE101  |  0 \uE102", PlainTextComponentSerializer.plainText().serialize(row));
        assertEquals(2, row.children().stream().filter(child -> ProfileIcons.FONT.equals(child.font())).count());
        for (ProfileMedal.Metal metal : ProfileMedal.Metal.values()) {
            assertEquals(ProfileIcons.FONT, ProfileIcons.medal(metal).font());
        }
    }

    @Test
    void prefixIconTakesGlyphFromItsFileAndColoredNameFollowsPrefixColor() {
        var prefix = new PrefixCatalog.Prefix("pref1", "Морозный", TextColor.color(0x8FE3F5), "pref1", 1);
        var icon = ProfileIcons.prefixIcon(prefix);
        assertEquals("\uE106", PlainTextComponentSerializer.plainText().serialize(icon));
        assertEquals(ProfileIcons.FONT, icon.font(), "Иконка рисуется шрифтом профиля");
        var row = ProfileIcons.prefixedName(prefix, "Steve");
        assertEquals("\uE106 Steve", PlainTextComponentSerializer.plainText().serialize(row));
        var namePart = row.children().get(1).children().get(1);
        assertEquals(TextColor.color(0x8FE3F5), namePart.color());
        // Не prefN-файлы иконки не имеют; без префикса ник белый.
        var custom = new PrefixCatalog.Prefix("pref11", "Самодельный", TextColor.color(0x123456), "my_icon", 11);
        assertEquals("", PlainTextComponentSerializer.plainText().serialize(ProfileIcons.prefixIcon(custom)));
        assertEquals(NamedTextColor.WHITE, ProfileIcons.prefixedName(null, "Steve").color());
    }

    @Test
    void foodCatalogIncludesBothFishCaviarsAndSandwiches() {
        assertTrue(F8Command.ITEM_CATALOG.containsAll(List.of("empty_cod", "empty_salmon", "red_caviar", "black_caviar",
                "caviar_sandwich_red", "caviar_sandwich_black", "water_flask", "poison_flask")));
        assertTrue(F8Command.ITEM_CATALOG.containsAll(List.of("icy_rime", "rime", "depleted_rime", "ice_caviar", "ice_caviar_sandwich")));
        assertEquals(13, F8Command.ITEM_CATALOG.stream().distinct().count());
    }
}
