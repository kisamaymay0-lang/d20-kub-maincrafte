package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Сигарета: палочки тяги набираются ровно по 0,2 секунды и не выходят за запас. */
final class CigaretteTest {

    @Test
    void палочкаНабиваетсяРазВДвеДесятыхСекунды() {
        assertEquals(0, Cigarette.barsFor(0, Cigarette.RESERVE));
        assertEquals(0, Cigarette.barsFor(3, Cigarette.RESERVE), "три тика — ещё ни одной палочки");
        assertEquals(1, Cigarette.barsFor(4, Cigarette.RESERVE), "палочка загорается через четыре тика");
        assertEquals(2, Cigarette.barsFor(8, Cigarette.RESERVE));
        assertEquals(8, Cigarette.barsFor(34, Cigarette.RESERVE));
    }

    @Test
    void выдохПриЛюбомНенулевомКликеНеКорочеПоловиныСекунды() {
        assertEquals(0, Cigarette.barsForRelease(0, Cigarette.RESERVE));
        assertEquals(1, Cigarette.barsForRelease(1, Cigarette.RESERVE));
        assertEquals(4, Cigarette.exhaleTicks(Cigarette.barsForRelease(1, Cigarette.RESERVE)),
                "даже короткий щелчок даёт одну палочку, то есть 0,2 секунды дыма");
        assertEquals(0, Cigarette.barsForRelease(1, 0));
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
            int filled = Cigarette.barsFor(Cigarette.BARS_PER_PUFF * 4, left);
            assertEquals(Cigarette.BARS_PER_PUFF, filled, "тяга №" + (i + 1) + " должна быть полной");
            left -= filled;
        }
        assertEquals(0, left, "после четырёх тяг запас кончился");
        assertEquals(0, Cigarette.barsFor(100, left), "пятая тяга уже невозможна");
    }

    @Test
    void каждаяПалочкаДаетРовноДвеДесятыхСекундыДыма() {
        assertEquals(0, Cigarette.exhaleTicks(0), "без палочек выдоха нет");
        assertEquals(4, Cigarette.exhaleTicks(1), "одна палочка — 0,2 секунды");
        assertEquals(40, Cigarette.exhaleTicks(10), "десять палочек — 2 секунды");
        assertEquals(64, Cigarette.exhaleTicks(Cigarette.BARS_PER_PUFF), "полная тяга — 3,2 секунды");
        assertEquals(64, Cigarette.exhaleTicks(Cigarette.BARS_PER_PUFF + 50), "лишние палочки ограничены 16");
        assertEquals(64, Cigarette.exhaleTicks(Cigarette.RESERVE), "не более 16 палочек в одной тяге");
        assertEquals(0, Cigarette.exhaleTicks(-5), "отрицательных палочек не бывает");
        assertEquals(8, Cigarette.MAX_STACK_SIZE, "в стаке не больше восьми сигарет");
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
     * Цвета палочек по порядку. Считаем по символу «|» в собственном тексте
     * каждого листа: так не важно, склеились ли соседние палочки одного цвета
     * в один лист или остались порознь.
     */
    private static void collectBars(Component component, List<TextColor> found) {
        String own = component instanceof TextComponent text ? text.content() : "";
        for (int i = 0; i < own.length(); i++) {
            if (own.charAt(i) == '|') {
                found.add(component.color());
            }
        }
        for (Component child : component.children()) {
            collectBars(child, found);
        }
    }
}
