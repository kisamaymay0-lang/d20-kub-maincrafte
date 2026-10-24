package com.yourserver.adaptation;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Окисление меди на одну ступень: вода «старит» медный блок.
 *
 * <h2>Что здесь считается</h2>
 *
 * В ванили у меди четыре ступени: обычная → {@code exposed} → {@code weathered}
 * → {@code oxidized}. Ступень — это не состояние блока, а отдельный материал,
 * поэтому «окислить» значит подменить материал, сохранив всё остальное:
 * поворот лестницы, форму плиты, открытость двери. Имена материалов устроены
 * регулярно, хватает приставки:
 *
 * <pre>
 *   copper_block            -> exposed_copper
 *   exposed_cut_copper      -> weathered_cut_copper
 *   weathered_copper_grate  -> oxidized_copper_grate
 *   oxidized_copper_bulb    -> null  (дальше некуда)
 *   waxed_copper_block      -> exposed_copper     (воск снимается и медь стареет)
 *   waxed_oxidized_copper   -> null               (последняя ступень)
 * </pre>
 *
 * Приставка работает и на новых медных блоках, которых здесь нет в списке:
 * проверка — существует ли материал с получившимся именем.
 *
 * <h2>Воск</h2>
 *
 * Вода смывает воск и в том же поливе старит медь на одну ступень:
 * {@code waxed_weathered_cut_copper} → {@code weathered_cut_copper} →
 * {@code oxidized_cut_copper}. То есть вощёная медь «догоняет» обычную за один
 * клик, а не за два: снятие воска и ступень — одно действие.
 *
 * <h2>Одно исключение</h2>
 *
 * Медный блок целиком называется {@code copper_block}, а его следующие ступени
 * — {@code exposed_copper}, {@code weathered_copper}, {@code oxidized_copper}:
 * приставка {@code exposed_} дала бы {@code exposed_copper_block}, которого в
 * игре нет. Поэтому для блока приставка заменяется на «пустую», и полив
 * {@code copper_block} → {@code exposed_copper} → {@code weathered_copper} →
 * {@code oxidized_copper} работает, как в ванили.
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
    /** Медный блок целиком: единственная ступень без приставки в имени. */
    private static final String COPPER_BLOCK = "copper_block";

    private CopperWeathering() { }

    /**
     * Что станет с медным именем после одного полива: ступень окисления +1, а у
     * вощёной меди вода сначала смывает воск и тут же старит на ту же ступень.
     * Имя ожидается в нижнем регистре, как в {@code Material#name()}.
     *
     * @return новое имя или {@code null}, если менять нечего: полностью
     *         окисленная медь — последняя ступень, а у не-меди следующего
     *         материала просто не существует.
     */
    static String next(String materialName) {
        if (materialName == null || materialName.isEmpty()) return null;
        String name = materialName.toLowerCase(Locale.ROOT);
        if (name.startsWith(WAXED)) {
            // Воск снимается, и медь в том же поливе стареет на одну ступень.
            name = name.substring(WAXED.length());
            if (name.isEmpty()) return null;
        }
        if (name.startsWith(OXIDIZED)) return null;
        if (name.startsWith(EXPOSED)) return WEATHERED + name.substring(EXPOSED.length());
        if (name.startsWith(WEATHERED)) return OXIDIZED + name.substring(WEATHERED.length());
        // Единственное исключение: следующая ступень медного блока — exposed_copper,
        // а не exposed_copper_block (такого материала в игре нет).
        return EXPOSED + (name.equals(COPPER_BLOCK) ? "copper" : name);
    }

    /**
     * Следующая ступень окисления или {@code null}, если её нет либо такого
     * материала не существует (медь ли это — решает существование следующего
     * имени: {@code exposed_stone} не существует, значит камень не трогаем).
     */
    static Material next(Material material) {
        if (material == null || material.isAir()) return null;
        String name = next(material.name());
        if (name == null) return null;
        return Material.getMaterial(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Переписать состояние блока ({@code BlockData#getAsString()}) одним поливом:
     * следующая ступень, а у вощёной меди — ещё и снятие воска. Все свойства
     * сохраняются: {@code minecraft:cut_copper_stairs[facing=east]} →
     * {@code minecraft:exposed_cut_copper_stairs[facing=east]},
     * {@code minecraft:waxed_copper_grate[waterlogged=true]} →
     * {@code minecraft:exposed_copper_grate[waterlogged=true]}.
     *
     * @return новое состояние или {@code null}, если поливать нечего.
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
