package com.yourserver.adaptation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Правила клешни краба: дальность взаимодействия и «одолженные» инструменты из инвентаря.
 *
 * Копает сама клешня: ей подставляется компонент {@code minecraft:tool} — тот самый, которым
 * описаны настоящие инструменты. Поэтому в правилах клешни лежат ванильные наборы блоков
 * ({@code #mineable/pickaxe}, {@code #mineable/shovel}, {@code #sword_instantly_mines} и прочие),
 * и игра сама считает скорость копания, дроп и полосу копания на клиенте.
 *
 * Здесь только чистые данные: семейства, уровни, скорости, запасные правила — для тех предметов,
 * у которых нет своего компонента инструмента, — и порядок просмотра инвентаря, по которому
 * инструменты и одалживаются. Числа сверены с вики: камень — дерево 1.15 с,
 * камень-кирка 0.6 с, железо 0.4 с, алмаз 0.3 с, незерит 0.25 с; обсидиан — дерево 125 с и рука 250 с.
 */
final class CrabClawRules {
    /** Прибавка к дальности взаимодействия в блоках — и по блокам, и по сущностям. */
    static final double REACH_BONUS = 3.0;
    /** Износ чужого инструмента за один блок: вдвое больше обычного. */
    static final int WEAR_PER_BLOCK = 2;
    /** Сколько прочности чинит один опыт «Починки» — как в ванили. */
    static final int MEND_PER_EXPERIENCE = 2;

    /* Зачарования. Клешня берёт у инструментов то, что решает, чем выпадет блок: «Удачу»
       и «Шёлковое касание». Зачарования идут от того инструмента, чьим правилом блок и ломается,
       поэтому кирка с «Шёлком» кладёт камень блоками, а ножницы без «Шелка» по шерсти зачарований
       не получают вовсе. Остальное клешня считает сама и потому не копирует: скорость —
       «Эффективность» (она уже в правилах), износ — «Прочность», починку — «Починка».
       Копировать их значило бы посчитать одно и то же дважды. Проклятия тоже остаются
       на самом инструменте: он лежит в инвентаре и теряется сам. */
    static final String FORTUNE = "minecraft:fortune";
    static final String SILK_TOUCH = "minecraft:silk_touch";
    static final String UNBREAKING = "minecraft:unbreaking";
    static final String MENDING = "minecraft:mending";
    /** Что клешня заимствует у инструмента: только то, что решает дроп. */
    static final Set<String> LOOT_ENCHANTS = Set.of(FORTUNE, SILK_TOUCH);

    /** Левая рука в общем порядке просмотра: слот инвентаря у неё не номер, а признак. */
    static final int OFFHAND = -1;

    /**
     * Порядок, в котором клешня ищет инструменты: справа налево и сверху вниз по рядам.
     * Верхний ряд хранилища — 17…9, затем 26…18, затем 35…27, затем нижний ряд (горячий) 8…0,
     * и в самом конце — левая рука. Инструмент каждого семейства берётся первый по этому порядку.
     */
    static final List<Integer> SCAN = scanOrder();

    private static List<Integer> scanOrder() {
        List<Integer> order = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int column = 8; column >= 0; column--) order.add(9 + row * 9 + column);
        }
        for (int column = 8; column >= 0; column--) order.add(column);
        order.add(OFFHAND);
        return List.copyOf(order);
    }

    /** Первое зачарование дропа по порядку списка: если на инструменте вдруг и «Удача», и «Шёлк»
     *  (так бывает только у выданного командой предмета), берём то, что стоит в списке раньше. */
    static String lootEnchant(List<String> ids) {
        for (String id : ids) {
            if (LOOT_ENCHANTS.contains(id)) return id;
        }
        return "";
    }

    /** Порядок семейств в правилах клешни: у кого выше скорость на общих блоках, тот и раньше.
     *  Так ножницы рвут листву вмиг, а меч по той же листве копает в полтора раза быстрее руки. */
    static final List<Family> ORDER = List.of(Family.SHEARS, Family.SWORD, Family.HOE,
            Family.SHOVEL, Family.AXE, Family.PICKAXE);

    /** Опорные блоки: по ним узнаём семейство незнакомого инструмента и его силу. */
    static final List<String> ANCHORS = List.of(
            "stone", "oak_log", "dirt", "hay_block", "white_wool", "cobweb",
            "copper_ore", "iron_ore", "gold_ore", "diamond_ore", "obsidian", "ancient_debris");

    /** Блоки, которые ножницы рвут вмиг: паутина и всякая мелочь, до которой они «дотягиваются». */
    private static final List<String> SHEARS_BLOCKS = List.of(
            "cobweb", "vine", "glow_lichen", "hanging_roots", "tripwire",
            "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant");

    private static final double SHEARS_FAST = 15.0;
    private static final double SHEARS_WOOL = 5.0;
    private static final double SWORD_FAST = 15.0;
    private static final double SWORD_SPEED = 1.5;

    private CrabClawRules() { }

    /** Семейство инструмента: по нему видно, какие блоки он берёт. */
    enum Family { PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS, NONE }

    /** Набор блоков: ванильный тег или перечень блоков. */
    record Blocks(String tag, List<String> names) {
        Blocks {
            names = List.copyOf(names);
        }
    }

    /** Правило, которое клешня отдаёт игре: набор блоков, скорость и дроп. */
    record Spec(Blocks blocks, double speed, boolean drops) { }

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

    /** Семейство незнакомого (например, кастомного) инструмента — по опорным блокам его правил.
     *  Ножницы проверяем раньше меча: только у них в правилах есть шерсть. */
    static Family family(Set<String> anchors) {
        if (anchors.contains("stone")) return Family.PICKAXE;
        if (anchors.contains("oak_log")) return Family.AXE;
        if (anchors.contains("dirt")) return Family.SHOVEL;
        if (anchors.contains("hay_block")) return Family.HOE;
        if (anchors.contains("white_wool")) return Family.SHEARS;
        if (anchors.contains("cobweb")) return Family.SWORD;
        return Family.NONE;
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

    /** Скорость инструмента по блокам своего семейства: дерево 2, камень 4, медь 5, железо 6,
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

    /** «Эффективность» прибавляется только настоящему инструменту: уровень в квадрате плюс один. */
    static double withEfficiency(double speed, int level) {
        return speed > 1.0 && level > 0 ? speed + (double) level * level + 1.0 : speed;
    }

    /**
     * Запасные правила — для предмета без своего компонента инструмента: тогда берём ванильные
     * наборы по имени и уровню материала. Настоящие инструменты пользуются своими правилами.
     */
    static List<Spec> fallback(Family family, int tier, double speed) {
        List<Spec> out = new ArrayList<>();
        switch (family) {
            case SHEARS -> {
                out.add(new Spec(new Blocks(null, SHEARS_BLOCKS), SHEARS_FAST, true));
                out.add(new Spec(new Blocks("wool", List.of()), SHEARS_WOOL, true));
            }
            case SWORD -> {
                out.add(new Spec(new Blocks("sword_instantly_mines", List.of()), SWORD_FAST, true));
                out.add(new Spec(new Blocks("sword_efficient", List.of()), SWORD_SPEED, true));
            }
            case HOE -> out.add(new Spec(new Blocks("mineable/hoe", List.of()), speed, true));
            case SHOVEL -> out.add(new Spec(new Blocks("mineable/shovel", List.of()), speed, true));
            case AXE -> out.add(new Spec(new Blocks("mineable/axe", List.of()), speed, true));
            case PICKAXE -> {
                // «Не тот» уровень копает медленнее и без дропа, поэтому его правила идут раньше общего.
                if (tier < 3) out.add(new Spec(new Blocks("needs_diamond_tool", List.of()), speed, false));
                if (tier < 2) out.add(new Spec(new Blocks("needs_iron_tool", List.of()), speed, false));
                if (tier < 1) out.add(new Spec(new Blocks("needs_stone_tool", List.of()), speed, false));
                out.add(new Spec(new Blocks("mineable/pickaxe", List.of()), speed, true));
            }
            default -> { }
        }
        return List.copyOf(out);
    }

    /** Сколько прочности возьмёт блок с «Прочностью»: с шансом уровень/(уровень+1) износ
     *  не тратится вовсе (в ванили «Прочность III» бережёт предмет в трёх случаях из четырёх). */
    static int wear(int base, int unbreaking, double roll) {
        if (base <= 0 || unbreaking <= 0) return Math.max(0, base);
        double skip = (double) unbreaking / (unbreaking + 1);
        return roll < skip ? 0 : base;
    }

    /** Сколько прочности останется после «Починки»: каждый опыт чинит две прочности. */
    static int mend(int damage, int experience) {
        if (experience <= 0) return Math.max(0, damage);
        return Math.max(0, damage - experience * MEND_PER_EXPERIENCE);
    }

    /** Сколько секунд займёт блок, если копать его с такой скоростью (таблица из вики). */
    static double seconds(double speed, double hardness, boolean correctTool) {
        if (hardness <= 0) return 0.0;
        double progress = speed / hardness / (correctTool ? 30.0 : 100.0);
        if (progress >= 1.0) return 0.0;
        return Math.ceil(1.0 / progress - 1e-9) / 20.0;
    }
}
