package com.yourserver.adaptation;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Настройки опьянения из {@code settings/drunk.yml} (раздел {@code drunk:}).
 *
 * <p>Здесь только то, что действительно хочется менять руками: размер шкалы, скорость
 * затухания, выключатели «шумных» эффектов и материалы для фантомных блоков и луж.
 * Пороги стадий и сами эффекты — таблица в коде ({@link DrunkStage}), иначе файл
 * настроек превратился бы в программу.
 */
record DrunkSettings(int scale,
                     int decayTicks,
                     boolean hangover,
                     boolean slurredChat,
                     boolean wobble,
                     boolean phantomBlocks,
                     boolean puddles,
                     int puddleSeconds,
                     int puddleMax,
                     List<Material> puddleMaterials,
                     Set<Material> naturalFloor,
                     boolean ignoreCreative,
                     double pukeRate,
                     int phantomMax) {

    /** Что стоит, если файла нет или в нём ничего не написано. */
    static DrunkSettings defaults() {
        return new DrunkSettings(
                DrunkStage.MAX_POINTS,
                600,
                true,
                true,
                true,
                true,
                true,
                60,
                160,
                List.of(Material.MUD, Material.SLIME_BLOCK, Material.DIRT),
                EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT,
                        Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.MOSS_BLOCK,
                        Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
                        Material.STONE, Material.DEEPSLATE, Material.SOUL_SAND, Material.SOUL_SOIL,
                        Material.TUFF, Material.SNOW_BLOCK, Material.PACKED_MUD),
                true,
                1.0,
                16);
    }

    /**
     * Разобрать раздел {@code drunk}. Чего нет — берётся из значений по умолчанию,
     * поэтому файл можно оставить почти пустым.
     */
    static DrunkSettings parse(ConfigurationSection section) {
        DrunkSettings base = defaults();
        if (section == null) return base;

        int scale = (int) clamp(section.getInt("scale", base.scale()), 5, 1000);
        int decay = (int) clamp(section.getInt("decay-ticks", base.decayTicks()), 20, 20 * 60 * 60);

        int puddleSeconds = (int) clamp(section.getInt("puddles-seconds", base.puddleSeconds()), 5, 600);
        int puddleMax = (int) clamp(section.getInt("puddles-max", base.puddleMax()), 1, 2000);

        List<Material> materials = materials(section.getStringList("puddle-materials"), base.puddleMaterials());
        List<Material> floors = materials(section.getStringList("natural-floor"), List.copyOf(base.naturalFloor()));

        double pukeRate = Math.max(0.0, Math.min(6.0, section.getDouble("puke-rate", base.pukeRate())));

        int phantomMax = (int) clamp(section.getInt("phantom-max", base.phantomMax()), 1, 40);

        return new DrunkSettings(
                scale,
                decay,
                section.getBoolean("hangover", base.hangover()),
                section.getBoolean("slurred-chat", base.slurredChat()),
                section.getBoolean("wobble", base.wobble()),
                section.getBoolean("phantom-blocks", base.phantomBlocks()),
                section.getBoolean("puddles", base.puddles()),
                puddleSeconds,
                puddleMax,
                List.copyOf(materials),
                EnumSet.copyOf(floors),
                section.getBoolean("ignore-creative", base.ignoreCreative()),
                pukeRate,
                phantomMax);
    }

    /**
     * Блок блевотины и «пол» под ним: незнакомые названия просто пропускаем.
     *
     * <p>Проверять «а это точно блок» здесь нечем: {@code Material#isBlock()} лезет в
     * реестр сервера, которого в тестах нет. Поэтому берём всё, что нашлось по имени,
     * а на не-блок плагин просто не поставит лужу (проверка в {@link Vomit}).
     */
    static List<Material> materials(List<String> names, List<Material> fallback) {
        List<Material> materials = new ArrayList<>();
        if (names != null) {
            for (String raw : names) {
                Material material = material(raw);
                if (material != null && !materials.contains(material)) {
                    materials.add(material);
                }
            }
        }
        return materials.isEmpty() ? List.copyOf(fallback) : List.copyOf(materials);
    }

    /**
     * Материал по имени: {@code MUD}, {@code mud}, {@code minecraft:mud}.
     *
     * <p>Ищем по списку материалов, а не через {@code Material.matchMaterial}: тот
     * спрашивает у сервера реестр, которого в тестах нет. Незнакомое имя — просто
     * пропускаем, чтобы одна опечатка в файле не ломала опьянение целиком.
     */
    static Material material(String raw) {
        if (raw == null) return null;
        String text = ProfileText.clean(raw).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (text.isEmpty()) return null;
        if (text.startsWith("MINECRAFT:")) text = text.substring("MINECRAFT:".length());
        try {
            return Material.valueOf(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Сколько единиц нужно, чтобы дойти до стадии (для подсказок и тестов). */
    int pointsFor(DrunkStage stage) {
        int normalized = stage.from();
        return (int) Math.max(1, Math.round(normalized * (double) scale / DrunkStage.MAX_POINTS));
    }
}
