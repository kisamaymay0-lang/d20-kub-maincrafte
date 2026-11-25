package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
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
 *   <li><b>ingredients</b> — что должно лежать в шейкере: список (для {@code shaker})
 *       или «предмет: сколько» (для {@code barrel});</li>
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
     * Что бочка принимает как ингредиент. В файле пишется строчными буквами
     * ({@code hot_water_bottle}, {@code low_sweetie_berry}), понимает и дефисы.
     * Кроме восьми «своих» предметов принимаются обычные: {@code wheat}, {@code potato},
     * {@code apple}, {@code sweet_berries}, {@code glow_berries}, {@code honey_bottle},
     * {@code melon_slice}, {@code cocoa_beans}.
     *
     * <p>{@link #WATER_BOTTLE} — «просто вода»: под неё подходит и обычная бутылочка
     * воды, и бутылочка горячей воды. Обратное неверно: ром требует именно горячую.
     */
    enum BarrelIngredient {
        COAL("уголь"),
        SUGAR_CANE("сахарный тростник"),
        HOT_WATER_BOTTLE("бутылочка горячей воды"),
        WATER_BOTTLE("бутылочка воды"),
        LOW_SWEETIE_BERRY("помятые сладкие ягоды"),
        HARD_SWEETIE_BERRY("продавленные сладкие ягоды"),
        SUGAR("сахар"),
        BONE_MEAL("костная мука"),
        // Обычные предметы — чтобы в настройках хватало не только восьми старых.
        // Пишутся так же: wheat, potato, apple, sweet_berries, glow_berries, honey_bottle,
        // melon_slice, cocoa_beans.
        WHEAT("пшеница"),
        POTATO("картофель"),
        APPLE("яблоко"),
        SWEET_BERRIES("сладкие ягоды"),
        GLOW_BERRIES("светящиеся ягоды"),
        HONEY_BOTTLE("бутылочка мёда"),
        MELON_SLICE("ломтик арбуза"),
        COCOA_BEANS("какао-бобы");

        private final String label;

        BarrelIngredient(String label) {
            this.label = label;
        }

        /** Русское название — для сообщений и тестов. */
        String label() {
            return label;
        }

        /** Предмет, который отдаём обратно, если ингредиент забирают из бочки. */
        ItemStack create() {
            return switch (this) {
                case COAL -> new ItemStack(Material.COAL);
                case SUGAR_CANE -> new ItemStack(Material.SUGAR_CANE);
                case HOT_WATER_BOTTLE -> Shaker.createHotWaterBottle();
                case WATER_BOTTLE -> waterBottle();
                case LOW_SWEETIE_BERRY -> SweetBerryBlock.createLowSweetieBerry();
                case HARD_SWEETIE_BERRY -> SweetBerryBlock.createHardSweetieBerry();
                case SUGAR -> new ItemStack(Material.SUGAR);
                case BONE_MEAL -> new ItemStack(Material.BONE_MEAL);
                case WHEAT -> new ItemStack(Material.WHEAT);
                case POTATO -> new ItemStack(Material.POTATO);
                case APPLE -> new ItemStack(Material.APPLE);
                case SWEET_BERRIES -> new ItemStack(Material.SWEET_BERRIES);
                case GLOW_BERRIES -> new ItemStack(Material.GLOW_BERRIES);
                case HONEY_BOTTLE -> new ItemStack(Material.HONEY_BOTTLE);
                case MELON_SLICE -> new ItemStack(Material.MELON_SLICE);
                case COCOA_BEANS -> new ItemStack(Material.COCOA_BEANS);
            };
        }

        private static ItemStack waterBottle() {
            ItemStack item = new ItemStack(Material.POTION);
            if (item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta) {
                meta.setBasePotionType(org.bukkit.potion.PotionType.WATER);
                item.setItemMeta(meta);
            }
            return item;
        }

        /** Ингредиент по предмету; null — бочке такой предмет не нужен. */
        static BarrelIngredient of(ItemStack item) {
            if (item == null || item.getType() == Material.AIR) return null;
            if (Shaker.isHotWaterBottle(item)) return HOT_WATER_BOTTLE;
            if (SweetBerryBlock.isHardSweetieBerry(item)) return HARD_SWEETIE_BERRY;
            if (SweetBerryBlock.isLowSweetieBerry(item)) return LOW_SWEETIE_BERRY;
            return switch (item.getType()) {
                case COAL, CHARCOAL -> COAL;
                case SUGAR_CANE -> SUGAR_CANE;
                case SUGAR -> SUGAR;
                case BONE_MEAL -> BONE_MEAL;
                case WHEAT -> WHEAT;
                case POTATO -> POTATO;
                case APPLE -> APPLE;
                case SWEET_BERRIES -> SWEET_BERRIES;
                case GLOW_BERRIES -> GLOW_BERRIES;
                case HONEY_BOTTLE -> HONEY_BOTTLE;
                case MELON_SLICE -> MELON_SLICE;
                case COCOA_BEANS -> COCOA_BEANS;
                case POTION -> WaterBottle.isWaterBottle(item) ? WATER_BOTTLE : null;
                default -> null;
            };
        }

        /** Запись из файла: {@code hot_water_bottle}, {@code hot-water-bottle} или {@code HOT_WATER_BOTTLE}. */
        static BarrelIngredient of(String raw) {
            if (raw == null) return null;
            String text = ProfileText.clean(raw).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (text.isEmpty()) return null;
            for (BarrelIngredient ingredient : values()) {
                if (ingredient.name().equals(text)) return ingredient;
            }
            return null;
        }

        /** Все допустимые имена — для сообщения об ошибке в файле. */
        static String names() {
            List<String> names = new ArrayList<>();
            for (BarrelIngredient ingredient : values()) {
                names.add(ingredient.name().toLowerCase(Locale.ROOT));
            }
            return String.join(", ", names);
        }
    }

    /**
     * Рецепт для бочки: «предмет: сколько». Совпадение точное — лишний предмет в бочке
     * останавливает варку, поэтому сладкое вино не превратится в обычное, если сахар
     * положили раньше ягод.
     */
    record BarrelRecipe(Map<BarrelIngredient, Integer> ingredients) {

        BarrelRecipe {
            // EnumMap, а не Map.copyOf: порядок ингредиентов должен совпадать с enum,
            // иначе «3 × тростник + 2 × вода» в чате каждый раз перемешивается.
            EnumMap<BarrelIngredient, Integer> ordered = new EnumMap<>(BarrelIngredient.class);
            ordered.putAll(ingredients);
            ingredients = java.util.Collections.unmodifiableMap(ordered);
        }

        /** Сколько всего предметов нужно. */
        int total() {
            int sum = 0;
            for (int count : ingredients.values()) sum += count;
            return sum;
        }

        /** Сколько этого ингредиента нужно по рецепту (0 — не нужен). */
        int needed(BarrelIngredient ingredient) {
            Integer count = ingredients.get(ingredient);
            return count == null ? 0 : count;
        }

        /**
         * Лежит ли в бочке ровно то, что нужно. Вода с оговоркой: там, где просят
         * обычную воду, подойдёт и горячая; там, где просят горячую, — только горячая.
         */
        boolean matches(Map<BarrelIngredient, Integer> have) {
            if (have == null || have.isEmpty()) return false;
            int counted = 0;
            for (int count : have.values()) counted += count;
            if (counted != total()) return false;
            for (Map.Entry<BarrelIngredient, Integer> need : ingredients.entrySet()) {
                if (available(have, need.getKey()) < need.getValue()) return false;
            }
            return true;
        }

        /** Сколько этого ингредиента реально лежит в бочке (с оговоркой про воду). */
        private static int available(Map<BarrelIngredient, Integer> have, BarrelIngredient ingredient) {
            int count = have.getOrDefault(ingredient, 0);
            if (ingredient == BarrelIngredient.WATER_BOTTLE) {
                count += have.getOrDefault(BarrelIngredient.HOT_WATER_BOTTLE, 0);
            }
            return count;
        }

        /** Пустой ли рецепт (у «мути» и прочих запасных напитков). */
        boolean empty() {
            return ingredients.isEmpty();
        }
    }

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
    private final Map<String, BarrelRecipe> barrels;

    private DrinkRecipes(Map<String, Drink> byId, Map<String, BarrelRecipe> barrels) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
        this.barrels = Map.copyOf(barrels);
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

    /** Рецепт бочки для напитка; null — напиток в бочке не варится. */
    BarrelRecipe barrelRecipe(String id) {
        return id == null ? null : barrels.get(id);
    }

    /**
     * Сколько этого ингредиента бочка принимает за один раз: столько, сколько его
     * просит самый жадный рецепт. Больше класть некуда — всё равно не совпадёт.
     */
    int maxBarrelCount(BarrelIngredient ingredient) {
        int max = 0;
        for (BarrelRecipe recipe : barrels.values()) {
            max = Math.max(max, recipe.needed(ingredient));
        }
        return Math.max(1, max);
    }

    // ===== ЧЕЛОВЕЧЕСКИЕ ОПИСАНИЯ (для /f8 drinks и подсказок бочки) =====

    /** Что напиток просит в бочку: «3 × сахарный тростник + 2 × бутылочка воды». */
    String recipeText(String id) {
        BarrelRecipe recipe = id == null ? null : barrels.get(id);
        if (recipe == null || recipe.empty()) return "";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<BarrelIngredient, Integer> entry : recipe.ingredients().entrySet()) {
            parts.add(entry.getValue() + " × " + entry.getKey().label());
        }
        return String.join(" + ", parts);
    }

    /** Ингредиенты шейкера строкой: «медовые соты, бутылочка горячей воды». */
    String shakerText(String id) {
        Drink drink = get(id);
        if (drink == null || drink.ingredients().isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (Shaker.IngredientKind kind : drink.ingredients()) {
            names.add(kindLabel(kind));
        }
        return String.join(", ", names);
    }

    /** Русское название ингредиента шейкера — для отчёта /f8 drinks. */
    static String kindLabel(Shaker.IngredientKind kind) {
        if (kind == null) return "что-то ещё";
        return switch (kind) {
            case HONEYCOMB -> "медовые соты";
            case HONEY_BOTTLE -> "бутылочка мёда";
            case WATER_BOTTLE -> "бутылочка воды";
            case HOT_WATER_BOTTLE -> "бутылочка горячей воды";
            case SUGAR -> "сахар";
            case SWEET_BERRIES -> "сладкие ягоды";
            case ICE -> "лёд";
            case COCOA_BEANS -> "какао-бобы";
            case MILK_BUCKET -> "ведро молока";
            case GREEN_DYE -> "зелёный краситель";
            case OTHER -> "что-то ещё";
        };
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
        Map<String, BarrelRecipe> barrels = new LinkedHashMap<>();
        buckets(barrels, Shaker.RECIPE_PIRATE_RUM, Map.of(
                BarrelIngredient.COAL, 1,
                BarrelIngredient.SUGAR_CANE, 1,
                BarrelIngredient.HOT_WATER_BOTTLE, 3));
        // Вина: модели пока нет, поэтому бутылочка узнаётся по цвету.
        wine(byId, barrels, "wine", "Вино", 0x7A1123, Map.of(
                BarrelIngredient.LOW_SWEETIE_BERRY, 3,
                BarrelIngredient.WATER_BOTTLE, 1));
        wine(byId, barrels, "sweet_wine", "Сладкое вино", 0xB23A63, Map.of(
                BarrelIngredient.LOW_SWEETIE_BERRY, 3,
                BarrelIngredient.SUGAR, 1,
                BarrelIngredient.WATER_BOTTLE, 1));
        wine(byId, barrels, "white_wine", "Белое вино", 0xE4D9A0, Map.of(
                BarrelIngredient.LOW_SWEETIE_BERRY, 3,
                BarrelIngredient.BONE_MEAL, 1,
                BarrelIngredient.WATER_BOTTLE, 1));
        wine(byId, barrels, "bitter_wine", "Горькое вино", 0x3E0C18, Map.of(
                BarrelIngredient.HARD_SWEETIE_BERRY, 3,
                BarrelIngredient.WATER_BOTTLE, 1));
        // Пиво: «солод» у нас — сахарный тростник, плюс вода. Пьянит слабо, зато пьётся долго.
        wine(byId, barrels, "beer", "Пиво", 0xC68B2E, Map.of(
                BarrelIngredient.SUGAR_CANE, 3,
                BarrelIngredient.WATER_BOTTLE, 2));
        return new DrinkRecipes(byId, barrels);
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
        Map<String, BarrelRecipe> barrels = new LinkedHashMap<>();
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
            BarrelRecipe barrel = readBarrelRecipe(section.getConfigurationSection(root + "ingredients"), id);
            if (barrel != null && method != Method.BARREL) {
                throw new IllegalArgumentException("Напиток " + id + ": ingredients записан как «предмет: сколько». "
                        + "Так пишут только бочку (method: barrel), а у шейкера — списком");
            }
            List<Shaker.IngredientKind> ingredients = barrel == null
                    ? readKinds(section.getStringList(root + "ingredients"), id)
                    : List.of();
            List<Shaker.IngredientKind> additives = readKinds(section.getStringList(root + "additives"), id);
            Drink drink = new Drink(id, name, color, model,
                    description == null || description.isEmpty() ? null : description,
                    hint, method, ingredients, additives);
            if (byId.putIfAbsent(id, drink) != null) {
                throw new IllegalArgumentException("Повторяется напиток " + id);
            }
            if (barrel != null && !barrel.empty()) barrels.put(id, barrel);
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Список напитков пуст");
        return new DrinkRecipes(byId, barrels);
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

    /** Рецепт бочки из раздела {@code ingredients}; null — там список (шейкер) или пусто. */
    private static BarrelRecipe readBarrelRecipe(ConfigurationSection section, String id) {
        if (section == null) return null;
        Map<BarrelIngredient, Integer> counts = new EnumMap<>(BarrelIngredient.class);
        for (String raw : section.getKeys(false)) {
            BarrelIngredient ingredient = BarrelIngredient.of(raw);
            if (ingredient == null) {
                throw new IllegalArgumentException("Напиток " + id + ": бочка не знает ингредиент «" + raw + "». "
                        + "Можно писать: " + BarrelIngredient.names());
            }
            int count = section.getInt(raw, 0);
            if (count <= 0) {
                throw new IllegalArgumentException("Напиток " + id + ": у ингредиента «" + raw
                        + "» должно быть количество больше нуля");
            }
            counts.put(ingredient, count);
        }
        if (counts.isEmpty()) return null;
        return new BarrelRecipe(counts);
    }

    /** Добавить вино в комплект: бутылочка без модели, рецепт бочки — из карты. */
    private static void wine(Map<String, Drink> byId, Map<String, BarrelRecipe> barrels,
                             String id, String name, int color, Map<BarrelIngredient, Integer> ingredients) {
        byId.put(id, new Drink(id, name, TextColor.color(color), "", null, null, Method.BARREL,
                List.of(), List.of()));
        buckets(barrels, id, ingredients);
    }

    /** Положить рецепт бочки в карту — короткая запись для {@link #defaults()}. */
    private static void buckets(Map<String, BarrelRecipe> barrels, String id,
                                Map<BarrelIngredient, Integer> ingredients) {
        barrels.put(id, new BarrelRecipe(new EnumMap<>(ingredients)));
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
