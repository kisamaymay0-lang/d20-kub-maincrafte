package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * Один {@code getConfig()} на все файлы {@code settings/}.
 *
 * <p>Настройки разложены по темам ({@code combat.yml}, {@code pets.yml},
 * {@code sky.yml} …), но коду удобно видеть их одним деревом: {@code pets.speech.player-scale},
 * {@code constellations.star-scale} и так далее — ровно те же пути, что были в старом
 * {@code config.yml}. Этот класс склеивает файлы в одно дерево и перечитывает его
 * целиком по {@code /f8 reload}.
 *
 * <p>Порядок в {@link #FILES} задаёт приоритет: если ключ случайно попал в два файла,
 * побеждает тот, что ниже в списке. Значения по умолчанию из кода работают как обычно.
 */
final class PluginSettings {

    /** Все файлы настроек, в порядке склейки. */
    static final List<String> FILES = List.of(
            "combat.yml",
            "pets.yml",
            "sky.yml",
            "copper.yml",
            "drinks.yml",
            "recipe-poilo.yml",
            "fishing.yml",
            "gouge.yml",
            "profiles.yml",
            "misc.yml",
            "prefixes.yml",
            "cosmetics.yml",
            "medals.yml",
            "voice.yml"
    );

    private final JavaPlugin plugin;
    private YamlConfiguration merged = new YamlConfiguration();
    private boolean loaded;

    PluginSettings(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Дерево настроек; при первом обращении файлы читаются с диска. */
    FileConfiguration current() {
        if (!loaded) {
            reload();
        }

        return merged;
    }

    /** Перечитать все файлы настроек с диска. */
    synchronized void reload() {
        YamlConfiguration fresh = new YamlConfiguration();
        fresh.options().copyDefaults(false);

        StringBuilder missing = new StringBuilder();

        for (String name : FILES) {
            File file = Layout.settings(plugin, name).toFile();

            if (!file.isFile()) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(name);
                continue;
            }

            YamlConfiguration part = YamlConfiguration.loadConfiguration(file);

            for (String path : part.getKeys(true)) {
                if (part.get(path) instanceof ConfigurationSection) {
                    continue;
                }
                fresh.set(path, part.get(path));
            }
        }

        merged = fresh;
        loaded = true;

        if (missing.length() > 0) {
            plugin.getLogger().warning("Нет файлов настроек: " + missing
                    + " — используются значения по умолчанию. Полный комплект вернёт /f8 reload.");
        }
    }
}
