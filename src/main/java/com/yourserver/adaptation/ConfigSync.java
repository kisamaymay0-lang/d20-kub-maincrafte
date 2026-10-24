package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Полный файл настроек при запуске плагина.
 *
 * Файлы плагина лежат в комплекте (в jar) — вместе с комментариями ко всему, что читает код.
 * При запуске они приводятся к полному виду:
 *
 * <ul>
 *   <li>файла нет — он создаётся из комплекта целиком, со всеми комментариями;</li>
 *   <li>файл есть, но в нём нет части ключей (например, он остался от прежней версии) —
 *       недостающие ключи дописываются в свои разделы вместе с их комментариями, а значения
 *       владельца остаются нетронутыми; прежний файл уходит в копию {@code *.backup};</li>
 *   <li>файл есть, но пуст — в него записывается полный набор из комплекта;</li>
 *   <li>файл не читается (сломан YAML) — он уходит в копию {@code *.broken}, а на его месте
 *       появляется полный новый.</li>
 * </ul>
 *
 * Дописывание идёт текстом, а не пересохранением конфига: комментарии владельца, порядок
 * ключей и оформление остаются как были — Bukkit при обычном сохранении их теряет.
 */
final class ConfigSync {
    /** Файлы плагина: держим их полными так же, как основной config.yml. */
    static final List<String> FILES = List.of("config.yml", "prefixes.yml", "cosmetics.yml",
            "medals/config.yml", "voice/config.yml");

    /** Отметка перед дописанным куском, чтобы владелец видел, что появилось при обновлении. */
    static final String MARKER = "# f8-plugin: дописано при запуске — этих ключей не было в вашем файле";

    private ConfigSync() { }

    /** Привести все файлы настроек к полному виду (вызывается при запуске и на /f8 reload). */
    static void sync(JavaPlugin plugin) {
        for (String name : FILES) sync(plugin, name);
    }

    private static void sync(JavaPlugin plugin, String name) {
        String defaults = resource(plugin, name);
        if (defaults == null) return;                       // такого файла в комплекте нет — сверять нечего
        Path file = plugin.getDataFolder().toPath().resolve(name);
        if (!Files.exists(file)) {
            plugin.saveResource(name, false);
            plugin.getLogger().info("Создан " + name + " — полный файл настроек из комплекта.");
            return;
        }
        String actual = read(file);
        YamlConfiguration parsed = parse(actual);
        if (parsed == null) {                               // файл не читается: сохраняем как есть и пишем новый
            copy(file, file.resolveSibling(file.getFileName() + ".broken"));
            plugin.saveResource(name, true);
            plugin.getLogger().warning(name + " не читался как YAML: он сохранён как " + name
                    + ".broken, а на его месте — полный новый файл.");
            return;
        }
        if (actual.isBlank()) {                             // файл есть, но внутри ничего нет
            write(file, defaults);
            plugin.getLogger().info(name + " был пуст — записан полный файл настроек из комплекта.");
            return;
        }
        List<String> missing = missing(parse(defaults), parsed);
        if (missing.isEmpty()) return;
        copy(file, file.resolveSibling(file.getFileName() + ".backup"));
        write(file, topUp(actual, defaults, missing));
        plugin.getLogger().info(name + ": дописано ключей — " + missing.size()
                + " (" + String.join(", ", missing) + "). Прежний файл сохранён как " + name + ".backup");
    }

    // ===== ЧТО ДОПИСАТЬ =====

    /** Ключи, которых нет в файле владельца: если нет целого раздела, он попадает в список целиком,
     *  а не по частям, — так в файл уходит готовый кусок вместе с комментариями. */
    static List<String> missing(ConfigurationSection defaults, ConfigurationSection actual) {
        List<String> missing = new ArrayList<>();
        for (String path : defaults.getKeys(true)) {
            if (actual.contains(path)) continue;                       // значение владельца не трогаем
            boolean covered = false;
            for (String added : missing) {
                if (path.startsWith(added + ".")) { covered = true; break; }
            }
            if (!covered) missing.add(path);
        }
        return List.copyOf(missing);
    }

    // ===== КАК ДОПИСАТЬ =====

    /**
     * Дописать недостающее в текст файла владельца: каждый ключ встаёт в конец своего раздела
     * (или в конец файла, если раздела нет вовсе) вместе с комментариями из комплекта.
     */
    static String topUp(String actualText, String defaultsText, List<String> missing) {
        if (missing.isEmpty()) return actualText;
        List<String> lines = linesOf(actualText);
        List<String> defaultLines = linesOf(defaultsText);
        Map<String, Block> actualBlocks = blocks(lines);
        Map<String, Block> defaultBlocks = blocks(defaultLines);

        Map<String, List<String>> byParent = new LinkedHashMap<>();
        for (String path : missing) {
            byParent.computeIfAbsent(parent(path), key -> new ArrayList<>()).add(path);
        }
        // Вставки собираются по местам: в конец раздела может прийтись сразу несколько кусков
        // (например, подраздел последнего раздела и новый раздел после него). Кто глубже — встаёт
        // первым, иначе строчки нового раздела уехали бы внутрь прежнего.
        List<Insertion> order = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : byParent.entrySet()) {
            String parent = group.getKey();
            int at;
            int delta;
            if (parent.isEmpty()) {
                at = lines.size();
                delta = 0;
            } else {
                Block block = actualBlocks.get(parent);
                if (block == null) continue;                 // родителя нет — сначала допишется он сам
                at = block.end();
                Block reference = defaultBlocks.get(parent);
                delta = reference == null ? 0 : block.indent() - reference.indent();
            }
            order.add(new Insertion(at, depth(parent), delta, group.getValue()));
        }
        order.sort(Comparator.comparingInt(Insertion::at)
                .thenComparing(Comparator.comparingInt(Insertion::depth).reversed())
                .thenComparing(insertion -> String.join(",", insertion.paths())));
        Map<Integer, List<String>> insertions = new LinkedHashMap<>();
        for (Insertion insertion : order) {
            List<String> added = insertions.computeIfAbsent(insertion.at(), key -> new ArrayList<>());
            boolean marked = false;
            for (String path : insertion.paths()) {
                String subtree = subtree(defaultLines, path);
                if (subtree.isEmpty()) continue;
                if (insertion.delta() != 0) subtree = reindent(subtree, insertion.delta());
                if (!marked) {                       // каждый кусок помечаем один раз
                    added.add(markerIndent(subtree) + MARKER);
                    marked = true;
                }
                added.addAll(linesOf(subtree));
            }
        }
        boolean changed = false;
        for (List<String> added : insertions.values()) {
            if (!added.isEmpty()) changed = true;
        }
        if (!changed) return actualText;

        List<String> out = new ArrayList<>(lines.size() + 8);
        for (int i = 0; i <= lines.size(); i++) {
            List<String> added = insertions.get(i);
            if (added != null) out.addAll(added);
            if (i < lines.size()) out.add(lines.get(i));
        }
        return String.join("\n", out) + "\n";
    }

    /** Подраздел файла по умолчанию: строки ключа вместе с его комментариями и всем содержимым. */
    static String subtree(String defaultsText, String path) {
        return subtree(linesOf(defaultsText), path);
    }

    private static String subtree(List<String> lines, String path) {
        Block block = blocks(lines).get(path);
        if (block == null) return "";
        int start = block.start();
        while (start > 0 && lines.get(start - 1).trim().startsWith("#")) start--;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < block.end(); i++) out.append(lines.get(i)).append('\n');
        return out.toString();
    }

    // ===== РАЗБОР ТЕКСТА =====

    /** Строки без завершающего перевода: так к ним удобно добавлять новые. */
    static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.endsWith("\r")) lines.set(i, line.substring(0, line.length() - 1));
        }
        return lines;
    }

    /** Раздел файла: где начинается его ключ и на какой строке содержимое заканчивается. */
    record Block(int start, int end, int indent) { }

    /** Куда и в каком порядке встаёт дописываемый кусок: место в файле, глубина раздела,
     *  поправка отступа и сами ключи. */
    private record Insertion(int at, int depth, int delta, List<String> paths) { }

    /** Насколько ключ глубоко: «gouge» — один, «gouge.slide» — два. */
    private static int depth(String path) {
        return path.isEmpty() ? 0 : path.split("\\.").length;
    }

    /** Границы всех разделов файла: путь → ключ и конец содержимого. */
    static Map<String, Block> blocks(List<String> lines) {
        Map<String, Block> blocks = new LinkedHashMap<>();
        record Open(String path, int indent, int start) { }
        Deque<Open> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String key = key(trimmed);
            if (key == null) continue;
            int indent = indentOf(lines.get(i));
            while (!stack.isEmpty() && stack.peek().indent() >= indent) {
                Open open = stack.pop();
                blocks.put(open.path(), new Block(open.start(), trimEnd(lines, i), open.indent()));
            }
            String path = stack.isEmpty() ? key : stack.peek().path() + "." + key;
            stack.push(new Open(path, indent, i));
        }
        while (!stack.isEmpty()) {
            Open open = stack.pop();
            blocks.put(open.path(), new Block(open.start(), trimEnd(lines, lines.size()), open.indent()));
        }
        return blocks;
    }

    /** Имя ключа строки или null, если это не ключ (значение списка, строка и прочее). */
    static String key(String trimmed) {
        if (trimmed.startsWith("-")) return null;                 // значение списка
        if (trimmed.startsWith("\"")) {                           // ключ в кавычках — бывает с «:» внутри
            int end = trimmed.indexOf('"', 1);
            if (end < 0) return null;
            String rest = trimmed.substring(end + 1).trim();
            return rest.startsWith(":") ? trimmed.substring(1, end) : null;
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return null;
        String name = trimmed.substring(0, colon).trim();
        return name.isEmpty() || name.indexOf(' ') >= 0 || name.indexOf('\t') >= 0 ? null : name;
    }

    /** Конец содержимого раздела: без пустых строк и «свободных» комментариев в хвосте. Так новый
     *  кусок встаёт сразу за последним ключом раздела, а не перед шапкой следующего раздела. */
    private static int trimEnd(List<String> lines, int end) {
        int last = end;
        while (last > 0) {
            String trimmed = lines.get(last - 1).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) last--;
            else break;
        }
        return last;
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        return indent;
    }

    /** Отступ отметки: как у первой строки дописываемого куска. */
    private static String markerIndent(String subtree) {
        List<String> lines = linesOf(subtree);
        return lines.isEmpty() ? "" : " ".repeat(indentOf(lines.getFirst()));
    }

    static String reindent(String text, int delta) {
        StringBuilder out = new StringBuilder();
        for (String line : linesOf(text)) {
            if (!line.isBlank() && delta > 0) out.append(" ".repeat(delta));
            out.append(delta < 0 && !line.isBlank() ? line.substring(Math.min(-delta, indentOf(line))) : line);
            out.append('\n');
        }
        return out.toString();
    }

    private static String parent(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    // ===== ФАЙЛЫ =====

    /** Текст файла из комплекта плагина: null, если его там нет. */
    private static String resource(JavaPlugin plugin, String name) {
        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) return null;
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) > 0) out.append(buffer, 0, read);
                return out.toString();
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось прочитать " + name + " из комплекта: " + ex.getMessage());
            return null;
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static void write(Path file, String text) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Не смогли записать — владелец увидит предупреждение ниже, сервер продолжит работу.
        }
    }

    private static void copy(Path from, Path to) {
        try {
            if (to.getParent() != null) Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Копия не получилась — это не повод не писать новый файл.
        }
    }

    /** Разбор текста: null вместо исключения, если YAML сломан (Bukkit сообщает о поломке
     *  проверяемым InvalidConfigurationException — её тоже ловим). */
    static YamlConfiguration parse(String text) {
        if (text == null) return null;
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
            return config;
        } catch (Exception ex) {
            return null;
        }
    }

}
