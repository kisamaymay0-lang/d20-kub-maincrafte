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
 *   copper_block        -> exposed_copper_block
 *   exposed_cut_copper  -> weathered_cut_copper
 *   weathered_copper_grate -> oxidized_copper_grate
 *   oxidized_copper_bulb   -> null  (дальше некуда)
 *   waxed_copper_block     -> null  (воск защищает)
 * </pre>
 *
 * Приставка работает и на новых медных блоках, которых здесь нет в списке:
 * проверка — существует ли материал с получившимся именем.
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

    /**
     * Следующая ступень окисления по имени материала (в нижнем регистре, как
     * в {@code Material#name()}) или {@code null}, если окислять нечего:
     * воск защищает, а полностью окисленная медь — последняя ступень.
     */
    static String next(String materialName) {
        if (materialName == null || materialName.isEmpty()) return null;
        String name = materialName.toLowerCase(Locale.ROOT);
        if (name.startsWith(WAXED) || name.startsWith(OXIDIZED)) return null;
        if (name.startsWith(EXPOSED)) return WEATHERED + name.substring(EXPOSED.length());
        if (name.startsWith(WEATHERED)) return OXIDIZED + name.substring(WEATHERED.length());
        return EXPOSED + name;
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
     * Переписать состояние блока ({@code BlockData#getAsString()}) на следующую
     * ступень, сохранив все свойства: {@code minecraft:cut_copper_stairs[
     * facing=east]} → {@code minecraft:exposed_cut_copper_stairs[facing=east]}.
     *
     * @return новое состояние или {@code null}, если окислять нечего.
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
