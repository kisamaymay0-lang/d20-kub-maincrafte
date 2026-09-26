package com.yourserver.adaptation;

/**
 * Правила клешни краба: дальность взаимодействия, «чужие» инструменты из инвентаря и их износ.
 *
 * Здесь только арифметика без доступа к серверу, поэтому таблица скоростей и время копания
 * проверяются юнит-тестами: числа сверены с вики (камень: дерево 1.15 с, камень-кирка 0.6 с,
 * железо 0.4 с, алмаз 0.3 с, незерит 0.25 с; обсидиан деревянной киркой 125 с, рукой 250 с).
 *
 * Ванильное правило: скорость даёт семейство инструмента, а деление на 30 вместо 100 — только
 * подходящий по уровню инструмент. Поэтому деревянная кирка по железной руде копает быстрее
 * руки, но дропа не даёт: об этом заботится сам {@code breakNaturally}.
 */
final class CrabClawRules {
    /** Прибавка к дальности взаимодействия в блоках — и по блокам, и по сущностям. */
    static final double REACH_BONUS = 3.0;
    /** Износ чужого инструмента за один блок: вдвое больше обычного. */
    static final int WEAR_PER_BLOCK = 2;

    private CrabClawRules() { }

    /** Семейство инструмента: по нему видно, подходит ли инструмент блоку. */
    enum Family { PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS, NONE }

    /** Семейство по имени материала Bukkit: DIAMOND_PICKAXE, STONE_AXE, SHEARS, STICK... */
    static Family family(String material) {
        if (material == null) return Family.NONE;
        if (material.endsWith("_PICKAXE")) return Family.PICKAXE;
        if (material.endsWith("_AXE")) return Family.AXE;       // после кирки: у неё тоже конец «_AXE»
        if (material.endsWith("_SHOVEL")) return Family.SHOVEL;
        if (material.endsWith("_HOE")) return Family.HOE;
        if (material.endsWith("_SWORD")) return Family.SWORD;
        if (material.equals("SHEARS")) return Family.SHEARS;
        return Family.NONE;
    }

    /** Подходит ли инструмент блоку: только своё семейство и не «пустая рука». */
    static boolean fits(Family tool, Family block) {
        return tool != Family.NONE && tool == block;
    }

    /** Скорость инструмента по блоку своего семейства: дерево 2, камень 4, медь 5, железо 6,
     *  алмаз 8, незерит 9, золото 12; всё остальное (в том числе рука) копает со скоростью 1. */
    static double tierSpeed(String material) {
        if (material == null) return 1.0;
        if (material.startsWith("WOODEN_")) return 2.0;
        if (material.startsWith("STONE_")) return 4.0;
        if (material.startsWith("COPPER_")) return 5.0;
        if (material.startsWith("IRON_")) return 6.0;
        if (material.startsWith("DIAMOND_")) return 8.0;
        if (material.startsWith("NETHERITE_")) return 9.0;
        if (material.startsWith("GOLDEN_")) return 12.0;
        return 1.0;
    }

    /** Уровень инструмента: дерево и золото 0, камень и медь 1, железо 2, алмаз и незерит 3;
     *  мечи и ножницы — тоже 0 (они не бывают «не того уровня»), у прочего уровня нет (−1). */
    static int toolTier(String material) {
        if (material == null) return -1;
        if (material.equals("SHEARS") || material.endsWith("_SWORD")) return 0;
        if (material.startsWith("WOODEN_") || material.startsWith("GOLDEN_")) return 0;
        if (material.startsWith("STONE_") || material.startsWith("COPPER_")) return 1;
        if (material.startsWith("IRON_")) return 2;
        if (material.startsWith("DIAMOND_") || material.startsWith("NETHERITE_")) return 3;
        return -1;
    }

    /** Требуемый блоком уровень по ванильным тегам: needs_diamond_tool 3, needs_iron_tool 2,
     *  needs_stone_tool 1; всё прочее — 0 (камень, дерево, земля: годится любой инструмент). */
    static int requiredTier(boolean needsStone, boolean needsIron, boolean needsDiamond) {
        if (needsDiamond) return 3;
        if (needsIron) return 2;
        if (needsStone) return 1;
        return 0;
    }

    /** Инструмент подходит блоку: и семейством, и уровнем. Тогда он копает «по-своему» (делитель 30). */
    static boolean correctTool(Family toolFamily, int toolTier, Family blockFamily, int blockTier) {
        return fits(toolFamily, blockFamily) && toolTier >= blockTier;
    }

    /** «Эффективность» прибавляется только к настоящему инструменту: уровень в квадрате плюс один. */
    static double withEfficiency(double speed, int level) {
        return speed > 1.0 && level > 0 ? speed + (double) level * level + 1.0 : speed;
    }

    /** Спешка и проводник ускоряют копание на 20 % за уровень (как в ванили). */
    static double effectMultiplier(int hasteLevel, int conduitLevel) {
        double factor = 1.0;
        if (hasteLevel > 0) factor *= 1.0 + 0.2 * hasteLevel;
        if (conduitLevel > 0) factor *= 1.0 + 0.2 * conduitLevel;
        return factor;
    }

    /** Усталость шахтёра множит уже готовый урон по блоку: 0.3, 0.09, 0.0027… (уровень −1 — нет эффекта). */
    static double fatigueMultiplier(int amplifier) {
        return amplifier < 0 ? 1.0 : Math.pow(0.3, amplifier + 1);
    }

    /** Сколько блок теряет прочности за тик: скорость делится на прочность, а подходящий инструмент
     *  копает втрое быстрее всех прочих (делитель 30 против 100 — как в ванили). */
    static double progressPerTick(double speed, double hardness, boolean correctTool, double fatigueFactor) {
        if (hardness <= 0) return 1.0;
        return Math.max(0.0, speed) / hardness / (correctTool ? 30.0 : 100.0) * Math.clamp(fatigueFactor, 0.0, 1.0);
    }

    /** Тиков до поломки блока: 0 — ломается сразу, максимум — блок не сломать этим инструментом.
     *  Поправка на double: ровно целые случаи (150 тиков рукой по камню, 45 у «не того»
     *  инструмента) из-за деления получаются чуть больше целого и иначе округляются вверх. */
    static int breakTicks(double speed, double hardness, boolean correctTool, double fatigueFactor) {
        if (hardness < 0) return Integer.MAX_VALUE;
        double progress = progressPerTick(speed, hardness, correctTool, fatigueFactor);
        if (progress >= 1.0) return 0;
        if (progress <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil(1.0 / progress - 1e-9);
    }

    /** Инструмент стоит брать, только если им быстрее, чем рукой: иначе клешня копает как рука. */
    static boolean borrowable(int toolTicks, int handTicks) {
        return toolTicks < handTicks;
    }
}
