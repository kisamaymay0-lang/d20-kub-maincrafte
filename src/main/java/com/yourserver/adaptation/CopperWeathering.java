package com.yourserver.adaptation;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Вода меняет медный блок на одну ступень: старит или снимает воск.
 *
 * <h2>Что здесь считается</h2>
 *
 * В ванили у меди четыре ступени: обычная → {@code exposed} → {@code weathered}
 * → {@code oxidized}. Ступень — это не состояние блока, а отдельный материал,
 * поэтому «изменить ступень» значит подменить материал, сохранив всё остальное:
 * поворот лестницы, форму плиты, открытость двери. Имена материалов устроены
 * регулярно, хватает приставки:
 *
 * <pre>
 *   copper_block            -> exposed_copper
 *   exposed_cut_copper      -> weathered_cut_copper
 *   weathered_copper_grate  -> oxidized_copper_grate
 *   waxed_weathered_copper  -> weathered_copper     (воск снимается)
 *   oxidized_copper_bulb    -> null                 (дальше некуда)
 * </pre>
 *
 * Приставка работает и на новых медных блоках, которых здесь нет в списке:
 * проверка — существует ли материал с получившимся именем.
 *
 * <h2>Воск</h2>
 *
 * Вода снимает воск, а не проходит сквозь него: {@code waxed_* → *}. Так
 * поливать можно <b>любой</b> медный блок, включая вощёный, — иначе вощёная
 * медь была бы единственной, на которую вода не действует. Ступень при снятии
 * воска не меняется: сразу после этого блок можно полить ещё раз и состарить.
 *
 * <h2>Медная семья</h2>
 *
 * Медь — всё, в имени чего есть {@code copper} (блоки, срезы, ступени, плиты,
 * решётки, лампы, двери и сундуки). Руда и самородки медью не считаются: у
 * них нет ступеней, материал следующей ступени не существует, и проверка
 * это отсекает сама.
 *
 * <h2>Почему не «тик окисления»</h2>
 *
 * Ваниль окисляет медь сама и очень медленно, а здесь нужен ровно один шаг по
 * клику игрока — поэтому меняем материал, а не время жизни блока.
 */
final class CopperWeathering {

    private static final String EXPOSED = "exposed_";
    private static final String WEATHERED = "weathered_";
    private static final String OXIDIZED = "oxidized_";
    private static final String WAXED = "waxed_";

    private CopperWeathering() { }

    /** Медный ли это материал: окисляется и вощится. */
    static boolean isCopper(Material material) {
        if (material == null || material.isAir()) return false;
        String name = material.name().toLowerCase(Locale.ROOT);
        return name.contains("copper");
    }

    /** Навощён ли блок: {@code waxed_copper_block}, {@code waxed_cut_copper_stairs}. */
    static boolean isWaxed(Material material) {
        return material != null && material.name().toLowerCase(Locale.ROOT).startsWith(WAXED);
    }

    /**
     * Следующее имя материала (в нижнем регистре, как в {@code Material#name()})
     * или {@code null}, если менять нечего: полностью окисленная медь —
     * последняя ступень, а у не-меди следующей ступени просто не существует.
     *
     * Вощёная медь теряет воск и остаётся на той же ступени.
     */
    static String next(String materialName) {
        if (materialName == null || materialName.isEmpty()) return null;
        String name = materialName.toLowerCase(Locale.ROOT);
        if (name.startsWith(WAXED)) return name.substring(WAXED.length());
        if (name.startsWith(OXIDIZED)) return null;
        if (name.startsWith(EXPOSED)) return WEATHERED + name.substring(EXPOSED.length());
        if (name.startsWith(WEATHERED)) return OXIDIZED + name.substring(WEATHERED.length());
        return EXPOSED + (name.equals("copper_block") ? "copper" : name);
    }

    /**
     * Следующая ступень окисления либо медь без воска; {@code null}, если это
     * не медь или следующего материала не существует ({@code exposed_stone} не
     * существует — значит камень не трогаем).
     */
    static Material next(Material material) {
        if (!isCopper(material)) return null;
        String name = next(material.name());
        if (name == null) return null;
        return Material.getMaterial(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Переписать состояние блока ({@code BlockData#getAsString()}) на следующую
     * ступень, сохранив все свойства: {@code minecraft:cut_copper_stairs[
     * facing=east]} → {@code minecraft:exposed_cut_copper_stairs[facing=east]},
     * {@code minecraft:waxed_copper_grate[waterlogged=true]} →
     * {@code minecraft:copper_grate[waterlogged=true]}.
     *
     * @return новое состояние или {@code null}, если менять нечего.
     */
    static String nextBlockData(String asString) {
        if (asString == null || asString.isEmpty()) return null;
        int bracket = asString.indexOf('[');
        String id = bracket < 0 ? asString : asString.substring(0, bracket);
        String states = bracket < 0 ? "" : asString.substring(bracket);
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String name = colon < 0 ? id : id.substring(colon + 1);
        String stepped = next(name);
        if (stepped == null) return null;
        return namespace + ":" + stepped + states;
    }
}
