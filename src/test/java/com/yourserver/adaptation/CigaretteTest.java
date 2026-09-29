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
        assertEquals(8, Cigarette.barsFor(34, Cigarette.RESERVE, Cigarette.BARS_PER_PUFF));
        assertEquals(16, Cigarette.barsFor(1000, Cigarette.RESERVE, Cigarette.BARS_PER_PUFF),
                "обычная и маленькая сигареты ограничены 16 палочками");
        assertEquals(32, Cigarette.barsFor(128, Cigarette.RESERVE),
                "по умолчанию большая сигарета набирает до 32 палочек");
        assertEquals(32, Cigarette.barsFor(128, Cigarette.RESERVE, Cigarette.BIG_BARS_PER_PUFF),
                "большая сигарета набирает до 32 палочек за раз");
        assertEquals(32, Cigarette.barsFor(1000, Cigarette.RESERVE, Cigarette.BIG_BARS_PER_PUFF),
                "долгое удержание всё равно ограничено 32 палочками");
    }

    @Test
    void выдохПриЛюбомНенулевомКликеНеКорочеПоловиныСекунды() {
        assertEquals(0, Cigarette.barsForRelease(0, Cigarette.RESERVE));
        assertEquals(1, Cigarette.barsForRelease(1, Cigarette.RESERVE));
        assertEquals(4, Cigarette.exhaleTicks(Cigarette.barsForRelease(1, Cigarette.RESERVE)),
                "даже короткий щелчок даёт одну палочку, то есть 0,2 секунды дыма");
        assertEquals(0, Cigarette.barsForRelease(1, 0));
        assertEquals(32, Cigarette.barsForRelease(128, Cigarette.RESERVE),
                "большая тяга отпускает все набранные 32 палочки");
        assertEquals(32, Cigarette.barsForRelease(128, Cigarette.RESERVE, Cigarette.BIG_BARS_PER_PUFF));
    }

    @Test
    void большаяТягаНеДлиннееТридцатиДвухПалочекИНеБольшеЗапаса() {
        assertEquals(Cigarette.BIG_BARS_PER_PUFF, Cigarette.barsFor(3600, Cigarette.RESERVE),
                "держи ПКМ хоть минуту — у большой сигареты максимум 32 палочки");
        assertEquals(3, Cigarette.barsFor(3600, 3), "запаса хватит только на три палочки");
        assertEquals(0, Cigarette.barsFor(100, 0), "пустой запас — пустая тяга");
        assertEquals(0, Cigarette.barsFor(-5, Cigarette.RESERVE), "отрицательной тяги не бывает");
    }

    @Test
    void тошнотаВыдаётсяПриШестидесятиЧетырёхПалочкахЗаСкользящуюМинуту() {
        Cigarette.SmokingWindow window = new Cigarette.SmokingWindow();
        assertEquals(false, window.add(0, 32));
        assertEquals(false, window.add(Cigarette.SMOKING_WINDOW_TICKS - 1, 31));
        assertEquals(true, window.add(Cigarette.SMOKING_WINDOW_TICKS - 1, 1));
        assertEquals(0, window.barsWithinWindow(), "после порога окно сбрасывается");
        assertEquals(Cigarette.NAUSEA_THRESHOLD_BARS, 64);
        assertEquals(Cigarette.NAUSEA_DURATION_TICKS, 300, "эффект длится 15 секунд");
    }

    @Test
    void стараяТягаНеУчитываетсяЗаПределамиОднойМинуты() {
        Cigarette.SmokingWindow window = new Cigarette.SmokingWindow();
        assertEquals(false, window.add(0, 32));
        assertEquals(false, window.add(Cigarette.SMOKING_WINDOW_TICKS, 32),
                "ровно через минуту первая тяга уже не учитывается");
        assertEquals(32, window.barsWithinWindow());
        assertEquals(true, window.add(Cigarette.SMOKING_WINDOW_TICKS + 1, 32),
                "вторая тяга остаётся внутри скользящего окна");
    }

    @Test
    void ломкаПроходитЧетыреПоследовательныхЭтапаПоИгровымТикам() {
        assertEquals(0, Cigarette.withdrawalStageForTicks(Cigarette.FIRST_WITHDRAWAL_TICKS - 1));
        assertEquals(1, Cigarette.withdrawalStageForTicks(Cigarette.FIRST_WITHDRAWAL_TICKS));
        assertEquals(2, Cigarette.withdrawalStageForTicks(
                Cigarette.FIRST_WITHDRAWAL_TICKS + Cigarette.WITHDRAWAL_STAGE_TICKS));
        assertEquals(3, Cigarette.withdrawalStageForTicks(
                Cigarette.FIRST_WITHDRAWAL_TICKS + 2 * Cigarette.WITHDRAWAL_STAGE_TICKS));
        assertEquals(4, Cigarette.withdrawalStageForTicks(
                Cigarette.FIRST_WITHDRAWAL_TICKS + 3 * Cigarette.WITHDRAWAL_STAGE_TICKS));
        assertEquals(38_400, Cigarette.FIRST_WITHDRAWAL_TICKS + 3 * Cigarette.WITHDRAWAL_STAGE_TICKS,
                "полное восстановление — через 1 игровой день и 12 минут");
    }

    @Test
    void уРазныхМоделейОтдельныйЗапас() {
        assertEquals(64, Cigarette.RESERVE, "большая сигарета");
        assertEquals(16, Cigarette.SMALL_RESERVE, "маленькая sigareta-small");
        assertEquals(32, Cigarette.REGULAR_RESERVE, "обычная sigareta");
    }

    @Test
    void запасБольшойСигаретыХватаетРовноНаДвеПолныеТяги() {
        assertEquals(64, Cigarette.RESERVE);
        assertEquals(16, Cigarette.BARS_PER_PUFF);
        assertEquals(32, Cigarette.BIG_BARS_PER_PUFF);
        int left = Cigarette.RESERVE;
        for (int i = 0; i < 2; i++) {
            int filled = Cigarette.barsFor(Cigarette.BIG_BARS_PER_PUFF * 4, left,
                    Cigarette.BIG_BARS_PER_PUFF);
            assertEquals(Cigarette.BIG_BARS_PER_PUFF, filled, "тяга №" + (i + 1) + " должна быть полной");
            left -= filled;
        }
        assertEquals(0, left, "после двух больших тяг запас кончился");
        assertEquals(0, Cigarette.barsFor(100, left, Cigarette.BIG_BARS_PER_PUFF), "третья тяга уже невозможна");
    }

    @Test
    void каждаяПалочкаДаетРовноДвеДесятыхСекундыДыма() {
        assertEquals(0, Cigarette.exhaleTicks(0), "без палочек выдоха нет");
        assertEquals(4, Cigarette.exhaleTicks(1), "одна палочка — 0,2 секунды");
        assertEquals(40, Cigarette.exhaleTicks(10), "десять палочек — 2 секунды");
        assertEquals(64, Cigarette.exhaleTicks(Cigarette.BARS_PER_PUFF), "обычная полная тяга — 3,2 секунды");
        assertEquals(128, Cigarette.exhaleTicks(Cigarette.BIG_BARS_PER_PUFF), "большая полная тяга — 6,4 секунды");
        assertEquals(128, Cigarette.exhaleTicks(Cigarette.BIG_BARS_PER_PUFF + 50), "не более 32 палочек");
        assertEquals(128, Cigarette.exhaleTicks(Cigarette.RESERVE), "запас не меняет предел одной большой тяги");
        assertEquals(6, Cigarette.smokeParticlesPerTick(Cigarette.BARS_PER_PUFF),
                "обычная полная тяга даёт шесть частиц за тик");
        assertEquals(10, Cigarette.smokeParticlesPerTick(Cigarette.BIG_BARS_PER_PUFF),
                "большая полная тяга даёт десять частиц за тик");
        assertEquals(1280, Cigarette.exhaleTicks(Cigarette.BIG_BARS_PER_PUFF)
                * Cigarette.smokeParticlesPerTick(Cigarette.BIG_BARS_PER_PUFF),
                "полный выдох большой сигареты даёт 1280 частиц дыма");
        assertEquals(0, Cigarette.exhaleTicks(-5), "отрицательных палочек не бывает");
        assertEquals(8, Cigarette.MAX_STACK_SIZE, "в стаке не больше восьми сигарет");
    }

    @Test
    void шкалаОтображаетТридцатьДвеПалочкиБольшойСигареты() {
        assertEquals(gaugeText(Cigarette.BIG_BARS_PER_PUFF),
                PlainTextComponentSerializer.plainText().serialize(Cigarette.gauge(0)));
        assertEquals(gaugeText(Cigarette.BARS_PER_PUFF),
                PlainTextComponentSerializer.plainText().serialize(Cigarette.gauge(0, Cigarette.BARS_PER_PUFF)));
        assertEquals(gaugeText(Cigarette.BIG_BARS_PER_PUFF),
                PlainTextComponentSerializer.plainText().serialize(Cigarette.gauge(16)));
        assertEquals(gaugeText(Cigarette.BIG_BARS_PER_PUFF),
                PlainTextComponentSerializer.plainText().serialize(Cigarette.gauge(0, Cigarette.BIG_BARS_PER_PUFF)));
    }

    @Test
    void набитыеПалочкиЖёлтыеПустыеТёмные() {
        Component gauge = Cigarette.gauge(5);
        assertEquals(gaugeText(Cigarette.BIG_BARS_PER_PUFF),
                PlainTextComponentSerializer.plainText().serialize(gauge));
        List<TextColor> bars = new ArrayList<>();
        collectBars(gauge, bars);
        assertEquals(Cigarette.BIG_BARS_PER_PUFF, bars.size(), "в шкале большой сигареты ровно 32 палочки");
        for (int i = 0; i < bars.size(); i++) {
            // Палочки наливаются слева направо: первые пять жёлтые, остальные тёмные.
            assertEquals(i < 5 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY, bars.get(i),
                    "палочка №" + (i + 1));
        }
    }

    private static String gaugeText(int bars) {
        return "[ " + "|".repeat(bars) + " ]";
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
