package com.yourserver.adaptation;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Куда что лежит в папке плагина.
 *
 * <p>Раньше всё было в одной куче: {@code config.yml} на двести строк, рядом
 * {@code prefixes.yml}, {@code cosmetics.yml}, {@code medals/config.yml},
 * {@code voice/config.yml}, а между ними — {@code blocks.yml} с расставленными
 * нотами и {@code playerdata.yml} с прогрессом. Понять, что тут можно править
 * руками, а что плагин перезаписывает сам, было невозможно.
 *
 * <p>Теперь три папки, и попасть не туда сложно:
 *
 * <ul>
 *   <li>{@code settings/} — всё, что правит владелец: бой, питомцы, небо, медь,
 *       напитки, рыбалка, Gouge, профили, префиксы, косметика, медали, голос;</li>
 *   <li>{@code content/} — каталоги-контенты: паки префиксов и файлы косметики;</li>
 *   <li>{@code data/} — всё, что плагин пишет сам: прогресс, ноты медных блоков,
 *       кувшины, кораллы, созвездия, профили игроков, записи голоса.</li>
 * </ul>
 *
 * <p>Старые места не бросаются: при первом обращении файл или папка переезжают
 * на новое место сами. Данные (ноты, прогресс, медали игроков) не теряются.
 */
final class Layout {

    /** Настройки: правит владелец. */
    static final String SETTINGS = "settings";
    /** Контент: паки префиксов, файлы косметики. */
    static final String CONTENT = "content";
    /** Данные: плагин пишет сам. */
    static final String DATA = "data";

    private Layout() {
    }

    /** Файл настроек: {@code settings/<name>}. */
    static Path settings(JavaPlugin plugin, String name) {
        return relocate(plugin, SETTINGS, name, legacySettings(name));
    }

    /** Каталог контента: {@code content/<name>}. */
    static Path content(JavaPlugin plugin, String name) {
        return relocate(plugin, CONTENT, name, name);
    }

    /** Файл или каталог данных: {@code data/<name>}. */
    static Path data(JavaPlugin plugin, String name) {
        return relocate(plugin, DATA, name, name);
    }

    /** Старое место настроек: до раскладки по папкам файлы лежали в корне. */
    private static String legacySettings(String name) {
        return switch (name) {
            case "medals.yml" -> "medals/config.yml";
            case "voice.yml" -> "voice/config.yml";
            default -> name;
        };
    }

    /** Новый путь с переездом со старого, если старый ещё на месте. */
    private static Path relocate(JavaPlugin plugin, String folder, String name, String legacyName) {
        Path target = plugin.getDataFolder().toPath().resolve(folder).resolve(name);

        try {
            Path parent = target.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (Files.exists(target)) {
                return target;
            }

            Path legacy = plugin.getDataFolder().toPath().resolve(legacyName);

            if (Files.exists(legacy)) {
                Files.move(legacy, target);
                plugin.getLogger().info("Переехало: " + legacyName + " → " + folder + "/" + name);
            }
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("Не удалось разложить " + folder + "/" + name + ": " + error);
        }

        return target;
    }

    /** Что раньше лежало в корне, а теперь живёт в {@code data/}. */
    private static final String[] LEGACY_DATA = {
            "playerdata.yml", "blocks.yml", "jugs.yml", "apvsh.yml", "corals.yml", "sweet_berries.yml",
            "constellations", "profiles", "medals/players", "voice/records"
    };

    /** Что раньше лежало в корне, а теперь живёт в {@code content/}. */
    private static final String[] LEGACY_CONTENT = {"prefixpacks", "cosmetics"};

    /**
     * Что где лежит — для {@code /f8 config}. Каждая строка готова к отправке
     * в чат: сначала папка, потом файл и его тема (первый комментарий файла),
     * чтобы не открывать YAML ради «а где тут питомцы».
     */
    static List<String> describe(JavaPlugin plugin) {
        List<String> lines = new ArrayList<>();

        lines.add("§6Папка f8-plugin: §fsettings/§7 — настройки, §fcontent/§7 — контент, "
                + "§fdata/§7 — данные (их пишет плагин)");
        lines.add("§7Настройки — правьте и применяйте §f/f8 reload§7:");

        int ready = 0;

        for (String name : PluginSettings.FILES) {
            Path file = settings(plugin, name);
            boolean exists = Files.isRegularFile(file);

            if (exists) {
                ready++;
            }

            lines.add((exists ? "§a✔ §f" : "§c✖ §f") + "settings/" + name + " §8— " + topic(file));
        }

        lines.add("§7Контент:");
        for (String name : LEGACY_CONTENT) {
            lines.add(exists(content(plugin, name)) ? "§a✔ §fcontent/" + name
                    : "§8· §7content/" + name + " §8— появится сам");
        }

        lines.add("§7Данные (руками не правим):");
        for (String name : LEGACY_DATA) {
            Path path = data(plugin, name);
            lines.add(exists(path) ? "§a✔ §fdata/" + name : "§8· §7data/" + name + " §8— пока пусто");
        }

        lines.add("§7Файлов настроек на месте: §f" + ready + "/" + PluginSettings.FILES.size()
                + "§7. Чего нет — вернёт §f/f8 reload§7 из комплекта.");

        return lines;
    }

    private static boolean exists(Path path) {
        return Files.exists(path);
    }

    /** Тема файла — его первая строка-комментарий. */
    private static String topic(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    continue;
                }

                if (!trimmed.startsWith("#")) {
                    break;
                }

                String text = trimmed.substring(1).trim();

                if (!text.isEmpty()) {
                    return text.length() > 72 ? text.substring(0, 72) + "…" : text;
                }
            }
        } catch (IOException ignored) {
            // Файла нет или он занят — строка ниже скажет об этом сама.
        }

        return "§cфайла нет — вернёт /f8 reload";
    }

    /**
     * Разложить папку по местам перед первым чтением настроек.
     *
     * <p>Важен порядок: переезд идёт ДО {@link ConfigSync#sync}, иначе комплект
     * создал бы пустой {@code settings/medals.yml} и старые настройки медалей
     * остались бы лежать невостребованными рядом.
     */
    static void migrateLegacy(JavaPlugin plugin) {
        for (String name : PluginSettings.FILES) {
            settings(plugin, name);
        }

        for (String name : LEGACY_DATA) {
            data(plugin, name);
        }

        for (String name : LEGACY_CONTENT) {
            content(plugin, name);
        }

        retireOldMonolith(plugin);
    }

    /**
     * Старый общий {@code config.yml} больше не читается: настройки разложены по
     * {@code settings/*.yml}. Чтобы файл не сбивал с толку, он один раз
     * переименовывается в {@code config.yml.old} — значения из него можно
     * посмотреть и перенести руками.
     */
    static void retireOldMonolith(JavaPlugin plugin) {
        Path old = plugin.getDataFolder().toPath().resolve("config.yml");
        Path backup = plugin.getDataFolder().toPath().resolve("config.yml.old");

        if (!Files.isRegularFile(old) || Files.exists(backup)) {
            return;
        }

        try {
            Files.move(old, backup, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Старый config.yml больше не используется: настройки теперь в "
                    + SETTINGS + "/*.yml. Прежний файл сохранён как config.yml.old.");
        } catch (IOException error) {
            plugin.getLogger().warning("Не удалось переименовать config.yml: " + error);
        }
    }
}
