package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MedalPresentationTest {
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    @ParameterizedTest
    @EnumSource(ProfileMedal.Metal.class)
    void everyTitleIsBoldAndRunsFromWhiteToItsMetalColor(ProfileMedal.Metal metal) {
        MedalSettings settings = MedalSettings.defaults();
        Component title = settings.title("Астрономия!", metal);
        assertEquals("Астрономия!", plain(title));
        assertEquals("Астрономия!".codePointCount(0, "Астрономия!".length()), title.children().size());
        assertEquals(0xFFFFFF, title.children().getFirst().color().value());
        assertEquals(settings.style(metal).end().value(), title.children().getLast().color().value());
        for (Component character : title.children()) {
            assertEquals(TextDecoration.State.TRUE, character.decoration(TextDecoration.BOLD));
            assertEquals(TextDecoration.State.FALSE, character.decoration(TextDecoration.ITALIC));
        }
    }

    @Test
    void silverHasVisibleSteelContrastAndUnicodeIsNotSplit() {
        MedalSettings settings = MedalSettings.defaults();
        assertTrue(settings.style(ProfileMedal.Metal.SILVER).end().red() < 180);
        Component text = settings.title("A🚀B", ProfileMedal.Metal.SILVER);
        assertEquals(3, text.children().size());
        assertEquals("🚀", ((TextComponent) text.children().get(1)).content());
    }

    @Test
    void loreStartsWithGrayTypeUsesEmDashesAndEndsWithGrayDate() {
        ProfileMedal medal = new ProfileMedal(UUID.randomUUID(), ProfileMedal.Metal.COPPER, "Астрономия!",
                List.of("Собрано созвездие", "- Вторая заслуга"), 1_788_600_000_000L, "");
        var date = DateTimeFormatter.ofPattern("dd.MM.uuuu").withZone(ZoneId.of("UTC"));
        List<Component> lore = MedalPresentation.lore(medal, MedalSettings.defaults(), date, List.of("Разместить"));
        assertEquals("Медная медаль", plain(lore.getFirst()));
        assertEquals(NamedTextColor.GRAY, lore.getFirst().color());
        List<String> lines = lore.stream().map(MedalPresentationTest::plain).toList();
        assertTrue(lines.contains("— Собрано созвездие"));
        assertTrue(lines.contains("— Вторая заслуга"));
        assertTrue(lines.getLast().startsWith("Получена: "));
        assertEquals(NamedTextColor.GRAY, lore.getLast().color());
    }

    @Test
    void announcementPrecedesExactlyOnePrivateMessageAndRecipientSound() {
        MedalSettings settings = MedalSettings.defaults();
        Component all = settings.message("public", "Steve", "Медную медаль", "Астрономия!", 1, "");
        Component own = settings.message("personal", "Steve", "Медную медаль", "Астрономия!", 1, "");
        assertEquals("Steve получил [Медную медаль]!", plain(all));
        assertEquals("Вы получили новую медаль! Подробнее /profile.", plain(own));
        List<String> log = new ArrayList<>();
        MedalDelivery.send(all, own, text -> log.add("public:" + plain(text)), text -> log.add("private:" + plain(text)), () -> log.add("sound"));
        assertEquals(3, log.size());
        assertTrue(log.get(0).startsWith("public:"));
        assertTrue(log.get(1).startsWith("private:"));
        assertEquals("sound", log.get(2));
    }

    @Test
    void emptyMessagesAreDisabledAndDynamicValuesAreNotParsedAsMarkup() {
        List<String> calls = new ArrayList<>();
        MedalDelivery.send(Component.empty(), Component.empty(), text -> calls.add("public"), text -> calls.add("private"), () -> calls.add("sound"));
        assertEquals(List.of("sound"), calls);
        Component message = MedalSettings.defaults().message("public", "<red>Steve", "Медную медаль", "", 1, "");
        assertTrue(plain(message).startsWith("<red>Steve"));
    }
}
