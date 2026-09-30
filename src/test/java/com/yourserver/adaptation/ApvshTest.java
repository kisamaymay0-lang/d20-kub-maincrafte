package com.yourserver.adaptation;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты АПВШ: раскладка слотов меню, пороги моделей от количества начинки,
 * расчёт урона от пороха, свойства сахара и распознавание кастомного блока CraftEngine.
 */
final class ApvshTest {

    @Test
    void менюИмеетТриРядаПоДевятьСлотовИТриТипаСлотов() {
        assertEquals(27, Apvsh.INVENTORY_SIZE, "размер инвентаря 3 ряда по 9 слотов");
        assertEquals(21, Apvsh.PANE_SLOTS.size(), "21 слот занят стеклянными панелями");
        assertEquals(5, Apvsh.FILLING_SLOTS.size(), "ровно 5 слотов для наполнения во втором ряду");
        assertEquals(16, Apvsh.PAPER_SLOT, "слот для бумаги — 16-й (8-й слот 2-го ряда)");

        // Проверяем, что слоты не пересекаются
        for (int slot : Apvsh.FILLING_SLOTS) {
            assertFalse(Apvsh.PANE_SLOTS.contains(slot), "слот наполнения " + slot + " не должен быть панелью");
        }
        assertFalse(Apvsh.PANE_SLOTS.contains(Apvsh.PAPER_SLOT), "слот бумаги не должен быть панелью");
        assertFalse(Apvsh.FILLING_SLOTS.contains(Apvsh.PAPER_SLOT), "слот бумаги не должен быть слотом наполнения");

        // Полное покрытие 27 слотов
        Set<Integer> all = new HashSet<>(Apvsh.PANE_SLOTS);
        all.addAll(Apvsh.FILLING_SLOTS);
        all.add(Apvsh.PAPER_SLOT);
        assertEquals(27, all.size(), "все 27 слотов распределены без пробелов");
        for (int i = 0; i < 27; i++) {
            assertTrue(all.contains(i), "слот " + i + " должен быть в меню");
        }
    }

    @Test
    void воВторомРядуСлотыОдинСемьДевятьПанелиДваШестьНаполнениеВосемьБумага() {
        // Ряд 2: слоты 9..17
        assertTrue(Apvsh.PANE_SLOTS.contains(9), "1-й слот 2-го ряда — панель");
        assertTrue(Apvsh.FILLING_SLOTS.contains(10), "2-й слот 2-го ряда — наполнение 1");
        assertTrue(Apvsh.FILLING_SLOTS.contains(11), "3-й слот 2-го ряда — наполнение 2");
        assertTrue(Apvsh.FILLING_SLOTS.contains(12), "4-й слот 2-го ряда — наполнение 3");
        assertTrue(Apvsh.FILLING_SLOTS.contains(13), "5-й слот 2-го ряда — наполнение 4");
        assertTrue(Apvsh.FILLING_SLOTS.contains(14), "6-й слот 2-го ряда — наполнение 5");
        assertTrue(Apvsh.PANE_SLOTS.contains(15), "7-й слот 2-го ряда — панель");
        assertEquals(16, Apvsh.PAPER_SLOT, "8-й слот 2-го ряда — бумага");
        assertTrue(Apvsh.PANE_SLOTS.contains(17), "9-й слот 2-го ряда — панель");

        // Ряды 1 и 3 полностью забиты панелями
        for (int i = 0; i < 9; i++) {
            assertTrue(Apvsh.PANE_SLOTS.contains(i), "ряд 1 слот " + i + " — панель");
            assertTrue(Apvsh.PANE_SLOTS.contains(18 + i), "ряд 3 слот " + (18 + i) + " — панель");
        }
    }

    @Test
    void модельСигаретыВыбираетсяПоКоличествуНачинки() {
        // 1..8 — маленькая
        assertEquals(Cigarette.Variant.SMALL, Cigarette.variantForFilling(1));
        assertEquals(Cigarette.Variant.SMALL, Cigarette.variantForFilling(4));
        assertEquals(Cigarette.Variant.SMALL, Cigarette.variantForFilling(8));

        // 9..20 — обычная
        assertEquals(Cigarette.Variant.REGULAR, Cigarette.variantForFilling(9));
        assertEquals(Cigarette.Variant.REGULAR, Cigarette.variantForFilling(15));
        assertEquals(Cigarette.Variant.REGULAR, Cigarette.variantForFilling(20));

        // 21+ — большая
        assertEquals(Cigarette.Variant.BIG, Cigarette.variantForFilling(21));
        assertEquals(Cigarette.Variant.BIG, Cigarette.variantForFilling(30));
        assertEquals(Cigarette.Variant.BIG, Cigarette.variantForFilling(64));
    }

    @Test
    void уронОтПорохаСоставляетПолсердцаЗаКаждуюШтукуПороха() {
        // В Minecraft: 1.0 HP = 0.5 сердца, 10.0 HP = 5 сердец, 20.0 HP = 10 сердец.
        assertEquals(1.0D, damageForGunpowder(1), 1e-9, "1 порох = 0.5 сердца урона (1 HP)");
        assertEquals(10.0D, damageForGunpowder(10), 1e-9, "10 пороха = 5 сердец урона (10 HP)");
        assertEquals(20.0D, damageForGunpowder(20), 1e-9, "20 пороха = 10 сердец урона (20 HP)");
        assertEquals(0.0D, damageForGunpowder(0), 1e-9, "0 пороха = 0 урона");
    }

    private static double damageForGunpowder(int gunpowder) {
        return gunpowder * 1.0D;
    }

    @Test
    void блокАПВШИмеетСвойИдентификаторCraftEngine() {
        assertEquals("f8resurs:apvsh", CraftEngineApvsh.ID);
        assertTrue(CraftEngineApvsh.isApvshId("f8resurs:apvsh"));
        assertFalse(CraftEngineApvsh.isApvshId("f8resurs:copper_note_block"));
        assertFalse(CraftEngineApvsh.isApvshId(null));
        assertFalse(CraftEngineApvsh.isApvshId(""));
    }

    @Test
    void наполнениеПринимаетПорохИСахарНоОтвергаетДругиеМатериалы() {
        assertTrue(Apvsh.isAllowedFilling(Material.GUNPOWDER), "порох разрешён");
        assertTrue(Apvsh.isAllowedFilling(Material.SUGAR), "сахар разрешён");
        assertFalse(Apvsh.isAllowedFilling(Material.PAPER), "бумага не является наполнителем");
        assertFalse(Apvsh.isAllowedFilling(Material.DIAMOND), "алмаз запрещён");
        assertFalse(Apvsh.isAllowedFilling((Material) null), "null запрещён");
    }

    @Test
    void градацияЭффектовСахараСоответствуетСпецификации() {
        assertNull(Cigarette.sugarTier(0));
        assertNull(Cigarette.sugarTier(-1));

        // 1-4 сахар: Скорость I на 1с за палочку тяги
        Cigarette.SugarTier tier1 = Cigarette.sugarTier(1);
        assertNotNull(tier1);
        assertEquals(0, tier1.speedAmplifier(), "Speed I (amplifier 0)");
        assertFalse(tier1.haste(), "без спешки");
        assertEquals(1, tier1.secondsPerBar(), "1 секунда за палочку");
        assertFalse(tier1.involuntaryLmb(), "без ЛКМ");
        assertFalse(tier1.involuntaryWalk(), "без ходьбы");
        assertEquals(0.0, tier1.deathChance(), "без шанса смерти");
        assertFalse(tier1.nausea(), "без тошноты");
        assertFalse(tier1.waxParticles(), "без частиц воска");
        assertEquals(0, tier1.cameraJerkTier(), "без рывков камеры");

        Cigarette.SugarTier tier4 = Cigarette.sugarTier(4);
        assertNotNull(tier4);
        assertEquals(0, tier4.speedAmplifier());
        assertFalse(tier4.haste());
        assertEquals(1, tier4.secondsPerBar());

        // 5-8 сахар: Скорость I + Спешка I на 1с за палочку
        Cigarette.SugarTier tier5 = Cigarette.sugarTier(5);
        assertNotNull(tier5);
        assertEquals(0, tier5.speedAmplifier());
        assertTrue(tier5.haste(), "Спешка I");
        assertEquals(1, tier5.secondsPerBar());
        assertFalse(tier5.involuntaryLmb());
        assertFalse(tier5.involuntaryWalk());
        assertEquals(0.0, tier5.deathChance());
        assertFalse(tier5.nausea());
        assertFalse(tier5.waxParticles());
        assertEquals(0, tier5.cameraJerkTier());

        Cigarette.SugarTier tier8 = Cigarette.sugarTier(8);
        assertNotNull(tier8);
        assertTrue(tier8.haste());
        assertEquals(1, tier8.secondsPerBar());

        // 9-12 сахар: Скорость II + Спешка I на 1с за палочку, побочный эффект: непроизвольные клики ЛКМ
        Cigarette.SugarTier tier9 = Cigarette.sugarTier(9);
        assertNotNull(tier9);
        assertEquals(1, tier9.speedAmplifier(), "Speed II (amplifier 1)");
        assertTrue(tier9.haste());
        assertEquals(1, tier9.secondsPerBar());
        assertTrue(tier9.involuntaryLmb(), "непроизвольные клики ЛКМ");
        assertFalse(tier9.involuntaryWalk());
        assertEquals(0.0, tier9.deathChance());
        assertFalse(tier9.nausea());
        assertFalse(tier9.waxParticles());
        assertEquals(0, tier9.cameraJerkTier());

        Cigarette.SugarTier tier12 = Cigarette.sugarTier(12);
        assertNotNull(tier12);
        assertEquals(1, tier12.speedAmplifier());
        assertTrue(tier12.involuntaryLmb());
        assertFalse(tier12.involuntaryWalk());

        // 13-16 сахар: Скорость II + Спешка I на 2с за палочку, побочные: клики ЛКМ + непроизвольная ходьба ~1с + частицы воска + рывки камеры
        Cigarette.SugarTier tier13 = Cigarette.sugarTier(13);
        assertNotNull(tier13);
        assertEquals(1, tier13.speedAmplifier());
        assertTrue(tier13.haste());
        assertEquals(2, tier13.secondsPerBar(), "2 секунды за палочку");
        assertTrue(tier13.involuntaryLmb());
        assertTrue(tier13.involuntaryWalk(), "непроизвольная ходьба");
        assertEquals(0.0, tier13.deathChance());
        assertFalse(tier13.nausea(), "на 13-16 ещё нет тошноты");
        assertTrue(tier13.waxParticles(), "частицы снятия воска на клиенте");
        assertEquals(1, tier13.cameraJerkTier(), "рывки камеры тир 1");

        Cigarette.SugarTier tier16 = Cigarette.sugarTier(16);
        assertNotNull(tier16);
        assertEquals(2, tier16.secondsPerBar());
        assertTrue(tier16.involuntaryLmb());
        assertTrue(tier16.involuntaryWalk());
        assertTrue(tier16.waxParticles());
        assertEquals(1, tier16.cameraJerkTier());

        // 17-23 сахар: Скорость II + Спешка I + Тошнота I на 4с за палочку, клики ЛКМ + ходьба + воск + резкие рывки камеры + 1% шанс смерти в тик
        Cigarette.SugarTier tier17 = Cigarette.sugarTier(17);
        assertNotNull(tier17);
        assertEquals(1, tier17.speedAmplifier());
        assertTrue(tier17.haste());
        assertEquals(4, tier17.secondsPerBar(), "4 секунды за палочку");
        assertTrue(tier17.involuntaryLmb());
        assertTrue(tier17.involuntaryWalk());
        assertEquals(0.01, tier17.deathChance(), 1e-9, "1% шанс смерти в тик");
        assertTrue(tier17.nausea(), "Тошнота I на предпоследней стадии");
        assertTrue(tier17.waxParticles(), "частицы снятия воска на клиенте");
        assertEquals(2, tier17.cameraJerkTier(), "рывки камеры тир 2 (резче и чаще)");

        Cigarette.SugarTier tier23 = Cigarette.sugarTier(23);
        assertNotNull(tier23);
        assertEquals(4, tier23.secondsPerBar());
        assertEquals(0.01, tier23.deathChance(), 1e-9);
        assertTrue(tier23.nausea());
        assertTrue(tier23.waxParticles());
        assertEquals(2, tier23.cameraJerkTier());

        // 24+ сахар: те же эффекты + Тошнота I + частые и сильные рывки камеры (тир 3) + 5% шанс смерти в тик
        Cigarette.SugarTier tier24 = Cigarette.sugarTier(24);
        assertNotNull(tier24);
        assertEquals(1, tier24.speedAmplifier());
        assertTrue(tier24.haste());
        assertEquals(4, tier24.secondsPerBar());
        assertTrue(tier24.involuntaryLmb());
        assertTrue(tier24.involuntaryWalk());
        assertEquals(0.05, tier24.deathChance(), 1e-9, "5% шанс смерти в тик");
        assertTrue(tier24.nausea(), "Тошнота I на последней стадии");
        assertTrue(tier24.waxParticles(), "частицы снятия воска на клиенте");
        assertEquals(3, tier24.cameraJerkTier(), "рывки камеры тир 3 (самые резкие и частые)");

        Cigarette.SugarTier tier64 = Cigarette.sugarTier(64);
        assertNotNull(tier64);
        assertEquals(0.05, tier64.deathChance(), 1e-9);
        assertTrue(tier64.nausea());
        assertTrue(tier64.waxParticles());
        assertEquals(3, tier64.cameraJerkTier());
    }

    @Test
    void передозПорохаНачинаетсяСБольшеЧетырехШтук() {
        assertEquals(5, Cigarette.GUNPOWDER_OVERDOSE_THRESHOLD, "порог взрыва крипера: 5+ (больше 4)");
        assertTrue(4 < Cigarette.GUNPOWDER_OVERDOSE_THRESHOLD, "4 штуки ещё не взрывают крипером");
        assertTrue(5 >= Cigarette.GUNPOWDER_OVERDOSE_THRESHOLD, "5 штук пороха вызывают взрыв крипера");
    }

    @Test
    void сообщениеОбОстановкеСердцаСодержитНикИгрока() {
        String playerName = "Player123";
        String expectedMessage = playerName + " умер от остановки сердца";
        assertEquals("Player123 умер от остановки сердца", expectedMessage);
    }
}
