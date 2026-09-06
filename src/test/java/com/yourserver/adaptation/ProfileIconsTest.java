package com.yourserver.adaptation;

import net.kyori.adventure.text.format.NamedTextColor;
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
    void foodCatalogIncludesBothFishCaviarsAndSandwiches() {
        assertTrue(F8Command.ITEM_CATALOG.containsAll(List.of("empty_cod", "empty_salmon", "red_caviar", "black_caviar",
                "caviar_sandwich_red", "caviar_sandwich_black", "water_flask", "poison_flask")));
        assertEquals(8, F8Command.ITEM_CATALOG.stream().distinct().count());
    }
}
