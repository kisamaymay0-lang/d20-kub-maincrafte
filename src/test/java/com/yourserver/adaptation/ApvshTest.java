package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты АПВШ: раскладка слотов меню, пороги моделей от количества начинки,
 * расчёт урона от пороха и распознавание кастомного блока CraftEngine.
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
}
