package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Настройки из комплекта, склеенные так же, как это делает {@link PluginSettings}
 * на сервере: {@code settings/combat.yml}, {@code settings/sky.yml} и остальные
 * читаются как один конфиг.
 *
 * <p>Тестам не нужно знать, в каком файле лежит ключ, — так проверка не ломается,
 * если тему перенесли из файла в файл.
 */
final class ShippedSettings {

    private ShippedSettings() {
    }

    /** Все settings/*.yml как одно дерево. */
    static YamlConfiguration merged() {
        YamlConfiguration config = new YamlConfiguration();

        for (String file : PluginSettings.FILES) {
            YamlConfiguration part = ConfigSync.parse(read("settings/" + file));

            if (part == null) {
                throw new IllegalStateException("settings/" + file + " не читается как YAML");
            }

            for (String path : part.getKeys(true)) {
                if (part.get(path) instanceof ConfigurationSection) {
                    continue;
                }
                config.set(path, part.get(path));
            }
        }

        return config;
    }

    /** Текст файла из комплекта. */
    static String read(String name) {
        try (InputStream stream = ShippedSettings.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException(name + " должен лежать в комплекте");
            }

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[4096];
                int read;

                while ((read = reader.read(buffer)) > 0) {
                    out.append(buffer, 0, read);
                }

                return out.toString();
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
