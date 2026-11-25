package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Напитки из {@code settings/recipe-poilo.yml}: что можно приготовить, из чего
 * и как напиток выглядит.
 *
 * <h2>Зачем</h2>
 *
 * Раньше рецепты, названия, цвета и модели напитков были зашиты в {@link Shaker}
 * и менялись только пересборкой плагина. Теперь всё это — файл настроек: новый
 * напиток добавляется строкой в YAML, без правки кода.
 *
 * <h2>Что где</h2>
 *
 * <ul>
 *   <li><b>name</b> — название напитка (в подсказке оно золотое);</li>
 *   <li><b>color</b> — цвет напитка в бутылочке ({@code #RRGGBB});</li>
 *   <li><b>model</b> — модель из ресурспака: {@code f8resurs:<model>}. Пусто — только цвет;</li>
 *   <li><b>description</b> — строка описания в подсказке. Пусто — описания нет;</li>
 *   <li><b>hint</b> — необязательная жёлтая приписка «может стоит добавить …»
 *       ({@code before}, {@code drink}, {@code after}); если она есть, у описания
 *       треугольный маркер ▶, если нет — точка •;</li>
 *   <li><b>method</b> — {@code shaker} (взбить в шейкере) или {@code barrel}
 *       (настаивается в бочке; в шейкер такой напиток не кладётся);</li>
 *   <li><b>ingredients</b> — что должно лежать в шейкере (для {@code shaker});</li>
 *   <li><b>additives</b> — положительные добавки. Всё, чего тут нет, считается
 *       отрицательным: за него напиток даёт тошноту и отравление.</li>
 * </ul>
 *
 * <p>Порядок записей в файле — это порядок проверки рецептов: если содержимое
 * шейкера подходит под два напитка, побеждает тот, что выше. Ничего не подошло —
 * получается «муть» ({@code murk}).
 */
final class DrinkRecipes {

    /** Как готовится напиток. */
    enum Method { SHAKER, BARREL }

    /**
     * Жёлтая приписка в описании: «может стоит добавить в нее <drink>?</...>».
     * Все три части необязательны, пустые просто не рисуются.
     */
    record Hint(String before, String drink, String after) {

        boolean empty() {
            return blank(before) && blank(drink) && blank(after);
        }

        private static boolean blank(String text) {
            return text == null || text.isEmpty();
        }
    }

    /**
     * Напиток. {@code ingredients} и {@code additives} — виды ингредиентов из
     * {@link Shaker.IngredientKind}; у «мути» списки пустые.
     */
    record Drink(String id, String name, TextColor color, String model, String description, Hint hint,
                 Method method, List<Shaker.IngredientKind> ingredients, List<Shaker.IngredientKind> additives) {

        Drink {
            ingredients = List.copyOf(ingredients);
            additives = List.copyOf(additives);
        }

        /** Готовится ли в шейкере (и вообще может ли быть смешан). */
        boolean shaker() {
            return method == Method.SHAKER;
        }

        /** Есть ли у напитка свой рецепт: у «мути» ингредиентов нет, это запасной вариант. */
        boolean hasRecipe() {
            return !ingredients.isEmpty();
        }

        /** Положительная ли добавка; всё, чего нет в списке, — отрицательная. */
        boolean positive(Shaker.IngredientKind kind) {
            return kind != null && additives.contains(kind);
        }

        /** Подходит ли набор ингредиентов под этот рецепт. */
        boolean matches(List<Shaker.IngredientKind> kinds) {
            if (!hasRecipe() || kinds == null) return false;
            for (Shaker.IngredientKind required : ingredients) {
                if (!kinds.contains(required)) return false;
            }
            return true;
        }

        /** Описание целиком, без маркера и без оформления: для справки и тестов. */
        String plainDescription() {
            if (description == null) return null;
            if (hint == null || hint.empty()) return description;
            StringBuilder text = new StringBuilder(description);
            if (hint.before() != null) text.append(hint.before());
            if (hint.drink() != null) text.append(hint.drink());
            if (hint.after() != null) text.append(hint.after());
            return text.toString();
        }
    }

    /** Имя раздела в файле. */
    private static final String ROOT = "recipes";

    /** Что показывать, если файл ещё не прочитан или в нём нет напитка. */
    private static volatile DrinkRecipes current = defaults();

    private final Map<String, Drink> byId;
    private final List<Drink> ordered;

    private DrinkRecipes(Map<String, Drink> byId) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
    }

    /** Текущий каталог напитков: его читают {@link Shaker} и {@link BambooBarrel}. */
    static DrinkRecipes current() {
        return current;
    }

    /** Поставить новый каталог (после чтения файла или /f8 reload). */
    static void install(DrinkRecipes next) {
        if (next != null && next.size() > 0) current = next;
    }

    List<Drink> list() {
        return ordered;
    }

    Drink get(String id) {
        return id == null ? null : byId.get(id);
    }

    int size() {
        return ordered.size();
    }

    /** Напитки, которые готовятся в шейкере (без «мути» и бочки). */
    List<Drink> shakerDrinks() {
        List<Drink> drinks = new ArrayList<>();
        for (Drink drink : ordered) {
            if (drink.shaker() && drink.hasRecipe()) drinks.add(drink);
        }
        return drinks;
    }

    /** Напитки, которые настаиваются в бочке. */
    List<Drink> barrelDrinks() {
        List<Drink> drinks = new ArrayList<>();
        for (Drink drink : ordered) {
            if (!drink.shaker()) drinks.add(drink);
        }
        return drinks;
    }

    /** Запасной напиток «муть» — то, что получается из непонятного набора. */
    Drink murk() {
        Drink murk = byId.get(Shaker.RECIPE_MURK);
        if (murk != null) return murk;
        return defaults().byId.get(Shaker.RECIPE_MURK);
    }

    /** Первый напиток, под который подходит набор ингредиентов, или null. */
    Drink match(List<Shaker.IngredientKind> kinds) {
        if (kinds == null || kinds.isEmpty()) return null;
        for (Drink drink : shakerDrinks()) {
            if (drink.matches(kinds)) return drink;
        }
        return null;
    }

    /** Имя напитка; для неизвестного id — имя «мути». */
    String name(String id) {
        Drink drink = get(id);
        return drink != null ? drink.name() : murk().name();
    }

    // ===== ЗАГРУЗКА =====

    /** Рецепты из комплекта — ровно те, что были зашиты в код до появления файла. */
    static DrinkRecipes defaults() {
        Map<String, Drink> byId = new LinkedHashMap<>();
        byId.put(Shaker.RECIPE_MEAD, new Drink(Shaker.RECIPE_MEAD, Shaker.NAME_MEAD,
                TextColor.color(0xEBAF28), "medovuha", "Безалькогольная медовуха, ",
                new Hint("может стоит добавить в нее ", "пиво", "?..."), Method.SHAKER,
                List.of(Shaker.IngredientKind.HONEYCOMB, Shaker.IngredientKind.HOT_WATER_BOTTLE),
                List.of(Shaker.IngredientKind.SUGAR)));
        byId.put(Shaker.RECIPE_MATCHA_TEA, new Drink(Shaker.RECIPE_MATCHA_TEA, Shaker.NAME_MATCHA_TEA,
                TextColor.color(0x78A83C), "matcha-chai",
                "Чай с бархатистым вкусом, дающий легкую бодрость и яркость ума.", null, Method.SHAKER,
                List.of(Shaker.IngredientKind.GREEN_DYE, Shaker.IngredientKind.HOT_WATER_BOTTLE),
                List.of(Shaker.IngredientKind.HONEY_BOTTLE, Shaker.IngredientKind.MILK_BUCKET,
                        Shaker.IngredientKind.ICE)));
        byId.put(Shaker.RECIPE_DAIQUIRI, new Drink(Shaker.RECIPE_DAIQUIRI, Shaker.NAME_DAIQUIRI,
                TextColor.color(0xF0466E), "daikiri", "Безалкогольный дайкири, ",
                new Hint("может стоит в него добавить ", "белое вино", "?..."), Method.SHAKER,
                List.of(Shaker.IngredientKind.SWEET_BERRIES, Shaker.IngredientKind.SUGAR,
                        Shaker.IngredientKind.WATER_BOTTLE),
                List.of(Shaker.IngredientKind.ICE, Shaker.IngredientKind.SWEET_BERRIES)));
        byId.put(Shaker.RECIPE_ICED_LATTE, new Drink(Shaker.RECIPE_ICED_LATTE, Shaker.NAME_ICED_LATTE,
                TextColor.color(0xE8C499), "iced_latte",
                "Прохладный кофейный напиток с кубиками льда... освежает!", null, Method.SHAKER,
                List.of(Shaker.IngredientKind.COCOA_BEANS, Shaker.IngredientKind.ICE,
                        Shaker.IngredientKind.MILK_BUCKET),
                List.of(Shaker.IngredientKind.HONEY_BOTTLE, Shaker.IngredientKind.ICE)));
        byId.put(Shaker.RECIPE_MURK, new Drink(Shaker.RECIPE_MURK, Shaker.NAME_MURK,
                TextColor.color(0x4E9331), "",
                "Какая-то болотная жижа, может стоит попробовать другие ингридиенты?)", null, Method.SHAKER,
                List.of(), List.of()));
        byId.put(Shaker.RECIPE_PIRATE_RUM, new Drink(Shaker.RECIPE_PIRATE_RUM, Shaker.NAME_PIRATE_RUM,
                TextColor.color(0x8B3A14), "skull_poilo", null, null, Method.BARREL, List.of(), List.of()));
        return new DrinkRecipes(byId);
    }

    /**
     * Прочитать {@code settings/recipe-poilo.yml}. Ошибка в файле — исключение:
     * лучше оставить прежние напитки, чем молча потерять рецепты.
     */
    static DrinkRecipes load(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        ConfigurationSection section = yaml.getConfigurationSection(ROOT);
        if (section == null) throw new IllegalArgumentException("В recipe-poilo.yml нет раздела " + ROOT);
        Map<String, Drink> byId = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = ProfileText.clean(rawId);
            if (id.isEmpty()) continue;
            String root = rawId + ".";
            String name = ProfileText.clean(section.getString(root + "name", ""));
            if (name.isEmpty()) name = id;
            String colorText = ProfileText.clean(section.getString(root + "color", "#E6B94B"));
            TextColor color = TextColor.fromHexString(colorText);
            if (color == null) throw new IllegalArgumentException("Неверный цвет напитка " + id + ": " + colorText);
            String model = ProfileText.clean(section.getString(root + "model", ""));
            String description = section.getString(root + "description", "");
            Method method = parseMethod(section.getString(root + "method", "shaker"), id);
            Hint hint = readHint(section.getConfigurationSection(root + "hint"), id);
            List<Shaker.IngredientKind> ingredients = readKinds(section.getStringList(root + "ingredients"), id);
            List<Shaker.IngredientKind> additives = readKinds(section.getStringList(root + "additives"), id);
            Drink drink = new Drink(id, name, color, model,
                    description == null || description.isEmpty() ? null : description,
                    hint, method, ingredients, additives);
            if (byId.putIfAbsent(id, drink) != null) {
                throw new IllegalArgumentException("Повторяется напиток " + id);
            }
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Список напитков пуст");
        return new DrinkRecipes(byId);
    }

    private static Method parseMethod(String raw, String id) {
        String text = ProfileText.clean(raw == null ? "shaker" : raw).toLowerCase(Locale.ROOT);
        return switch (text) {
            case "", "shaker", "шейкер" -> Method.SHAKER;
            case "barrel", "бочка", "выдержка" -> Method.BARREL;
            default -> throw new IllegalArgumentException("Напиток " + id + ": метод «" + raw
                    + "» — пишите shaker (шейкер) или barrel (бочка)");
        };
    }

    private static Hint readHint(ConfigurationSection section, String id) {
        if (section == null) return null;
        Hint hint = new Hint(section.getString("before", ""), section.getString("drink", ""),
                section.getString("after", ""));
        return hint.empty() ? null : hint;
    }

    private static List<Shaker.IngredientKind> readKinds(List<String> names, String id) {
        List<Shaker.IngredientKind> kinds = new ArrayList<>();
        for (String raw : names) {
            Shaker.IngredientKind kind = kindOf(raw);
            if (kind == null) {
                throw new IllegalArgumentException("Напиток " + id + ": неизвестный ингредиент «" + raw + "». "
                        + "Можно писать: " + kindNames());
            }
            if (!kinds.contains(kind)) kinds.add(kind);
        }
        return kinds;
    }

    /** Ингредиент по записи: {@code hot_water_bottle}, {@code hot-water-bottle} или {@code HOT_WATER_BOTTLE}. */
    static Shaker.IngredientKind kindOf(String raw) {
        if (raw == null) return null;
        String text = ProfileText.clean(raw).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (text.isEmpty()) return null;
        for (Shaker.IngredientKind kind : Shaker.IngredientKind.values()) {
            if (kind.name().equals(text)) return kind;
        }
        return null;
    }

    /** Все допустимые имена ингредиентов — для сообщения об ошибке. */
    static String kindNames() {
        List<String> names = new ArrayList<>();
        for (Shaker.IngredientKind kind : Shaker.IngredientKind.values()) {
            names.add(kind.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }

    // ===== ОФОРМЛЕНИЕ ПОДСКАЗКИ =====

    /**
     * Строка описания для подсказки: маркер, обычный текст белым, жёлтая фраза
     * «может стоит добавить…», жёлтый подчёркнутый напиток и белый хвост.
     * У «мути» и обычных напитков жёлтых кусков нет — будет один плоский текст.
     */
    static Component descriptionLine(Drink drink) {
        if (drink == null || drink.description() == null) return Component.empty();
        Hint hint = drink.hint();
        boolean hasHint = hint != null && !hint.empty();
        String marker = hasHint ? Shaker.DESC_MARKER_ADD : Shaker.DESC_MARKER;
        Component line = Component.text(marker + " " + drink.description(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
        if (!hasHint) return line;
        if (hint.before() != null) {
            line = line.append(Component.text(hint.before(), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (hint.drink() != null) {
            line = line.append(Component.text(hint.drink(), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.UNDERLINED, true));
        }
        if (hint.after() != null) {
            line = line.append(Component.text(hint.after(), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return line;
    }
}
