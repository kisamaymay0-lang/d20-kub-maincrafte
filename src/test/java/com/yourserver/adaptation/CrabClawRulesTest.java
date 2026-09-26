package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Клешня краба: семейства инструментов, уровни и время копания.
 *
 * Числа сверены с вики: камень — дерево 1.15 с, каменная кирка 0.6 с, железная 0.4 с, алмазная
 * 0.3 с, незеритовая 0.25 с, рукой 7.5 с; обсидиан — деревянной киркой 125 с, рукой 250 с,
 * незеритовой 8.35 с; железная руда — деревянной киркой 7.5 с, каменной 1.15 с.
 */
class CrabClawRulesTest {

    @Test
    void familiesComeFromMaterialNames() {
        assertEquals(CrabClawRules.Family.PICKAXE, CrabClawRules.family("DIAMOND_PICKAXE"));
        assertEquals(CrabClawRules.Family.PICKAXE, CrabClawRules.family("WOODEN_PICKAXE"));
        assertEquals(CrabClawRules.Family.AXE, CrabClawRules.family("NETHERITE_AXE"));
        assertEquals(CrabClawRules.Family.SHOVEL, CrabClawRules.family("IRON_SHOVEL"));
        assertEquals(CrabClawRules.Family.HOE, CrabClawRules.family("GOLDEN_HOE"));
        assertEquals(CrabClawRules.Family.SWORD, CrabClawRules.family("STONE_SWORD"));
        assertEquals(CrabClawRules.Family.SHEARS, CrabClawRules.family("SHEARS"));
        assertEquals(CrabClawRules.Family.NONE, CrabClawRules.family("STICK"));
        assertEquals(CrabClawRules.Family.NONE, CrabClawRules.family(null));
        // Кирка проверяется раньше топора: у «_PICKAXE» и «_AXE» общий хвост.
        assertNotEquals(CrabClawRules.Family.AXE, CrabClawRules.family("DIAMOND_PICKAXE"));
    }

    @Test
    void onlyItsOwnFamilyFitsTheBlock() {
        assertTrue(CrabClawRules.fits(CrabClawRules.Family.PICKAXE, CrabClawRules.Family.PICKAXE));
        assertFalse(CrabClawRules.fits(CrabClawRules.Family.AXE, CrabClawRules.Family.PICKAXE));
        assertFalse(CrabClawRules.fits(CrabClawRules.Family.NONE, CrabClawRules.Family.PICKAXE));
        assertFalse(CrabClawRules.fits(CrabClawRules.Family.PICKAXE, CrabClawRules.Family.NONE));
    }

    @Test
    void tiersMatchVanillaTools() {
        assertEquals(2.0, CrabClawRules.tierSpeed("WOODEN_PICKAXE"), 1e-9);
        assertEquals(4.0, CrabClawRules.tierSpeed("STONE_PICKAXE"), 1e-9);
        assertEquals(5.0, CrabClawRules.tierSpeed("COPPER_PICKAXE"), 1e-9);
        assertEquals(6.0, CrabClawRules.tierSpeed("IRON_PICKAXE"), 1e-9);
        assertEquals(8.0, CrabClawRules.tierSpeed("DIAMOND_PICKAXE"), 1e-9);
        assertEquals(9.0, CrabClawRules.tierSpeed("NETHERITE_PICKAXE"), 1e-9);
        assertEquals(12.0, CrabClawRules.tierSpeed("GOLDEN_PICKAXE"), 1e-9);
        assertEquals(1.0, CrabClawRules.tierSpeed("STICK"), 1e-9);        // не инструмент — как рука
        assertEquals(1.0, CrabClawRules.tierSpeed(null), 1e-9);
        // Мечи и ножницы считает не таблица уровней, а сам слушатель: у них своя скорость.
        assertEquals(1.0, CrabClawRules.tierSpeed("SHEARS"), 1e-9);
        assertEquals(1.0, CrabClawRules.tierSpeed("DIAMOND_SWORD"), 1e-9);
    }

    @Test
    void toolTiersAndBlockLevelsMatchVanilla() {
        assertEquals(0, CrabClawRules.toolTier("WOODEN_PICKAXE"));
        assertEquals(0, CrabClawRules.toolTier("GOLDEN_SHOVEL"));
        assertEquals(1, CrabClawRules.toolTier("STONE_PICKAXE"));
        assertEquals(1, CrabClawRules.toolTier("COPPER_PICKAXE"));
        assertEquals(2, CrabClawRules.toolTier("IRON_PICKAXE"));
        assertEquals(3, CrabClawRules.toolTier("DIAMOND_PICKAXE"));
        assertEquals(3, CrabClawRules.toolTier("NETHERITE_PICKAXE"));
        assertEquals(0, CrabClawRules.toolTier("SHEARS"));                // ножницы не бывают «не того уровня»
        assertEquals(0, CrabClawRules.toolTier("IRON_SWORD"));
        assertEquals(-1, CrabClawRules.toolTier("STICK"));
        assertEquals(-1, CrabClawRules.toolTier(null));

        assertEquals(0, CrabClawRules.requiredTier(false, false, false));    // камень, дерево, земля
        assertEquals(1, CrabClawRules.requiredTier(true, false, false));     // железная и медная руда
        assertEquals(2, CrabClawRules.requiredTier(true, true, false));      // золотая и алмазная руда
        assertEquals(3, CrabClawRules.requiredTier(true, true, true));       // обсидиан, древние обломки
    }

    @Test
    void efficiencyAndEffectsSpeedUpOnlyRealTools() {
        assertEquals(8.0, CrabClawRules.withEfficiency(8.0, 0), 1e-9);
        assertEquals(10.0, CrabClawRules.withEfficiency(8.0, 1), 1e-9);   // +1*1+1
        assertEquals(13.0, CrabClawRules.withEfficiency(8.0, 2), 1e-9);   // +2*2+1
        assertEquals(18.0, CrabClawRules.withEfficiency(8.0, 3), 1e-9);   // +3*3+1
        assertEquals(1.0, CrabClawRules.withEfficiency(1.0, 3), 1e-9);    // голой руке «Эффективность» не помогает
        assertEquals(1.0, CrabClawRules.effectMultiplier(0, 0), 1e-9);
        assertEquals(1.4, CrabClawRules.effectMultiplier(2, 0), 1e-9);    // Спешка II
        assertEquals(1.2, CrabClawRules.effectMultiplier(0, 1), 1e-9);    // Проводник I
        assertEquals(1.68, CrabClawRules.effectMultiplier(2, 1), 1e-9);
        assertEquals(1.0, CrabClawRules.fatigueMultiplier(-1), 1e-9);     // нет усталости
        assertEquals(0.3, CrabClawRules.fatigueMultiplier(0), 1e-9);
        assertEquals(0.09, CrabClawRules.fatigueMultiplier(1), 1e-9);
    }

    @Test
    void stoneMatchesTheWikiDurations() {
        // Камень (прочность 1.5): 20 тиков = 1 с, инструмент подходящий — делитель 30.
        assertEquals(150, CrabClawRules.breakTicks(1.0, 1.5, false, 1.0));  // рука: 7.5 с
        assertEquals(23, CrabClawRules.breakTicks(2.0, 1.5, true, 1.0));    // дерево: 1.15 с
        assertEquals(12, CrabClawRules.breakTicks(4.0, 1.5, true, 1.0));    // камень: 0.6 с
        assertEquals(8, CrabClawRules.breakTicks(6.0, 1.5, true, 1.0));     // железо: 0.4 с
        assertEquals(6, CrabClawRules.breakTicks(8.0, 1.5, true, 1.0));     // алмаз: 0.3 с
        assertEquals(5, CrabClawRules.breakTicks(9.0, 1.5, true, 1.0));     // незерит: 0.25 с
    }

    @Test
    void theWrongTierKeepsTheSpeedButLosesTheDrop() {
        CrabClawRules.Family pickaxe = CrabClawRules.Family.PICKAXE;
        int diamond = CrabClawRules.requiredTier(true, true, true);
        // Обсидиан деревянной киркой: семейство подходит, уровня нет — делитель 100.
        assertTrue(CrabClawRules.fits(pickaxe, pickaxe));
        assertFalse(CrabClawRules.correctTool(pickaxe, 0, pickaxe, diamond));
        assertTrue(CrabClawRules.correctTool(pickaxe, 3, pickaxe, diamond));
        assertEquals(2500, CrabClawRules.breakTicks(2.0, 50.0, false, 1.0));   // 125 с — как на вики
        assertEquals(5000, CrabClawRules.breakTicks(1.0, 50.0, false, 1.0));   // рукой 250 с
        assertEquals(167, CrabClawRules.breakTicks(9.0, 50.0, true, 1.0));     // незеритом 8.35 с
        // Железная руда: деревянной киркой 7.5 с (без дропа — это решает breakNaturally), каменной 1.15 с.
        assertFalse(CrabClawRules.correctTool(pickaxe, 0, pickaxe, 1));
        assertEquals(150, CrabClawRules.breakTicks(2.0, 3.0, false, 1.0));
        assertEquals(23, CrabClawRules.breakTicks(4.0, 3.0, true, 1.0));
        assertEquals(15, CrabClawRules.breakTicks(6.0, 3.0, true, 1.0));       // железной по алмазной руде
    }

    @Test
    void theClawBorrowsOnlyWhatIsFasterThanTheHand() {
        int hand = CrabClawRules.breakTicks(1.0, 1.5, false, 1.0);        // 150 тиков рукой (7.5 с)
        int pickaxe = CrabClawRules.breakTicks(8.0, 1.5, true, 1.0);      // 6 тиков алмазной киркой
        assertTrue(CrabClawRules.borrowable(pickaxe, hand), "кирка быстрее руки — берём");
        assertFalse(CrabClawRules.borrowable(hand, hand), "рука не быстрее руки — не берём");
        assertFalse(CrabClawRules.borrowable(Integer.MAX_VALUE, hand));
        // Мгновенный блок: брать нечего, ломается сразу и без сессии.
        assertEquals(0, CrabClawRules.breakTicks(8.0, 0.0, true, 1.0));
        assertTrue(CrabClawRules.borrowable(0, hand));
    }

    @Test
    void reachBonusAndWearAreNiceRoundNumbers() {
        assertEquals(3.0, CrabClawRules.REACH_BONUS, 1e-9);
        assertEquals(2, CrabClawRules.WEAR_PER_BLOCK);
        // Клешня добавляет дальность к ванильным 4.5 блока (атрибут) — итого 7.5.
        assertTrue(4.5 + CrabClawRules.REACH_BONUS > 7.0);
    }
}
