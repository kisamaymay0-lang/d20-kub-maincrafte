package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Сигарета: палочки тяги набираются ровно по полсекунды и не выходят за запас. */
final class CigaretteTest {

    @Test
    void палочкаНабиваетсяРазВПолсекунды() {
        assertEquals(0, Cigarette.barsFor(0, Cigarette.RESERVE));
        assertEquals(0, Cigarette.barsFor(9, Cigarette.RESERVE), "девять тиков — ещё ни одной палочки");
        assertEquals(1, Cigarette.barsFor(10, Cigarette.RESERVE));
        assertEquals(2, Cigarette.barsFor(20, Cigarette.RESERVE));
        assertEquals(8, Cigarette.barsFor(85, Cigarette.RESERVE));
    }

    @Test
    void тягаНеДлиннееШестнадцатиПалочекИНеБольшеЗапаса() {
        assertEquals(Cigarette.BARS_PER_PUFF, Cigarette.barsFor(3600, Cigarette.RESERVE),
                "держи ПКМ хоть минуту — палочек всё равно шестнадцать");
        assertEquals(3, Cigarette.barsFor(3600, 3), "запаса хватит только на три палочки");
        assertEquals(0, Cigarette.barsFor(100, 0), "пустой запас — пустая тяга");
        assertEquals(0, Cigarette.barsFor(-5, Cigarette.RESERVE), "отрицательной тяги не бывает");
    }

    @Test
    void запасаХватаетРовноНаЧетыреПолныеТяги() {
        assertEquals(64, Cigarette.RESERVE);
        assertEquals(16, Cigarette.BARS_PER_PUFF);
        assertEquals(4, Cigarette.FULL_PUFFS);
        int left = Cigarette.RESERVE;
        for (int i = 0; i < Cigarette.FULL_PUFFS; i++) {
            int filled = Cigarette.barsFor(Cigarette.BARS_PER_PUFF * 10, left);
            assertEquals(Cigarette.BARS_PER_PUFF, filled, "тяга №" + (i + 1) + " должна быть полной");
            left -= filled;
        }
        assertEquals(0, left, "после четырёх тяг запас кончился");
        assertEquals(0, Cigarette.barsFor(100, left), "пятая тяга уже невозможна");
    }

    @Test
    void шкалаРовноШестнадцатьПалочекВСкобках() {
        assertEquals("[ |||||||||||||||| ]",
                PlainTextComponentSerializer.plainText().serialize(Cigarette.gauge(0)));
        assertEquals("[ |||||||||||||||| ]",
                PlainTextComponentSerializer.plainText().serialize(Cigarette.gauge(16)));
    }

    @Test
    void набитыеПалочкиЖёлтыеПустыеТёмные() {
        Component gauge = Cigarette.gauge(5);
        assertEquals("[ |||||||||||||||| ]",
                PlainTextComponentSerializer.plainText().serialize(gauge));
        List<TextColor> bars = new ArrayList<>();
        collectBars(gauge, bars);
        assertEquals(Cigarette.BARS_PER_PUFF, bars.size(), "в шкале ровно шестнадцать палочек");
        for (int i = 0; i < bars.size(); i++) {
            // Палочки наливаются слева направо: первые пять жёлтые, остальные тёмные.
            assertEquals(i < 5 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY, bars.get(i),
                    "палочка №" + (i + 1));
        }
    }

    /**
     * Цвета палочек по порядку. Считаем по символу «|» в каждом листе: так не
     * важно, склеились ли соседние палочки одного цвета в один лист или нет.
     */
    private static void collectBars(Component component, List<TextColor> found) {
        String text = PlainTextComponentSerializer.plainText().serialize(component);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '|') {
                found.add(component.color());
            }
        }
        for (Component child : component.children()) {
            collectBars(child, found);
        }
    }
}
