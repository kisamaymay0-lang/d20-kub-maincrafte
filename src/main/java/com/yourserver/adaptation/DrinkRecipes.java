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
     * Ингредиент бочки. Шестнадцать «своих» предметов (уголь, тростник, ягоды, горячая
     * вода…) знает код, а любой обычный предмет Minecraft можно назвать в настройке по
     * имени материала: {@code brown_mushroom}, {@code kelp}, {@code cocoa_beans}. Такой
     * предмет создаётся на лету — заводить его в коде не нужно.
     *
     * <p>В файле пишется строчными буквами ({@code hot_water_bottle}, {@code brown-mushroom}),
     * понимает и дефисы.
     */
    static final class BarrelIngredient {

        /** «Свои» предметы: у них свои названия и, если нужно, свои создатели. */
        private static final Map<String, BarrelIngredient> SPECIAL = new LinkedHashMap<>();
        /** Обычные материалы, которые совпадают с «своими» (уголь, тростник, сахар…). */
        private static final Map<Material, BarrelIngredient> BY_MATERIAL = new LinkedHashMap<>();
        /** Обычные материалы из настроек: создаются по требованию, по одному на материал. */
        private static final Map<Material, BarrelIngredient> VANILLA =
                new java.util.concurrent.ConcurrentHashMap<>();

        static final BarrelIngredient COAL = special("coal", "уголь", Material.COAL,
                () -> new ItemStack(Material.COAL));
        static final BarrelIngredient SUGAR_CANE = special("sugar_cane", "сахарный тростник",
                Material.SUGAR_CANE, () -> new ItemStack(Material.SUGAR_CANE));
        static final BarrelIngredient HOT_WATER_BOTTLE = special("hot_water_bottle",
                "бутылочка горячей воды", null, Shaker::createHotWaterBottle);
        static final BarrelIngredient WATER_BOTTLE = special("water_bottle",
                "бутылочка воды", null, BarrelIngredient::waterBottle);
        static final BarrelIngredient LOW_SWEETIE_BERRY = special("low_sweetie_berry",
                "помятые сладкие ягоды", null, SweetBerryBlock::createLowSweetieBerry);
        static final BarrelIngredient HARD_SWEETIE_BERRY = special("hard_sweetie_berry",
                "продавленные сладкие ягоды", null, SweetBerryBlock::createHardSweetieBerry);
        static final BarrelIngredient SUGAR = special("sugar", "сахар", Material.SUGAR,
                () -> new ItemStack(Material.SUGAR));
        static final BarrelIngredient BONE_MEAL = special("bone_meal", "костная мука", Material.BONE_MEAL,
                () -> new ItemStack(Material.BONE_MEAL));
        static final BarrelIngredient WHEAT = special("wheat", "пшеница", Material.WHEAT,
                () -> new ItemStack(Material.WHEAT));
        static final BarrelIngredient POTATO = special("potato", "картофель", Material.POTATO,
                () -> new ItemStack(Material.POTATO));
        static final BarrelIngredient APPLE = special("apple", "яблоко", Material.APPLE,
                () -> new ItemStack(Material.APPLE));
        static final BarrelIngredient SWEET_BERRIES = special("sweet_berries", "сладкие ягоды",
                Material.SWEET_BERRIES, () -> new ItemStack(Material.SWEET_BERRIES));
        static final BarrelIngredient GLOW_BERRIES = special("glow_berries", "светящиеся ягоды",
                Material.GLOW_BERRIES, () -> new ItemStack(Material.GLOW_BERRIES));
        static final BarrelIngredient HONEY_BOTTLE = special("honey_bottle", "бутылочка мёда",
                Material.HONEY_BOTTLE, () -> new ItemStack(Material.HONEY_BOTTLE));
        static final BarrelIngredient MELON_SLICE = special("melon_slice", "ломтик арбуза",
                Material.MELON_SLICE, () -> new ItemStack(Material.MELON_SLICE));
        static final BarrelIngredient COCOA_BEANS = special("cocoa_beans", "какао-бобы",
                Material.COCOA_BEANS, () -> new ItemStack(Material.COCOA_BEANS));

        static {
            // Древесный уголь — тот же ингредиент, что и обычный.
            BY_MATERIAL.putIfAbsent(Material.CHARCOAL, COAL);
        }

        private final String id;
        private final String label;
        private final Material material;
        private final java.util.function.Supplier<ItemStack> creator;

        private BarrelIngredient(String id, String label, Material material,
                                 java.util.function.Supplier<ItemStack> creator) {
            this.id = id;
            this.label = label;
            this.material = material;
            this.creator = creator;
        }

        private static BarrelIngredient special(String id, String label, Material material,
                                                java.util.function.Supplier<ItemStack> creator) {
            BarrelIngredient ingredient = new BarrelIngredient(id, label, material, creator);
            SPECIAL.put(id, ingredient);
            if (material != null) BY_MATERIAL.putIfAbsent(material, ingredient);
            return ingredient;
        }

        /** Имя в настройке: строчные буквы, дефисы и пробелы — как подчёркивания. */
        private static String normalize(String raw) {
            if (raw == null) return "";
            return ProfileText.clean(raw).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }

        /** Обычный предмет из настроек: создаётся один раз на материал. */
        private static BarrelIngredient vanilla(Material material) {
            return VANILLA.computeIfAbsent(material, key -> {
                String id = key.name().toLowerCase(Locale.ROOT);
                return new BarrelIngredient(id, id.replace('_', ' '), key,
                        () -> new ItemStack(key));
            });
        }

        /**
         * Подходит ли материал как ингредиент бочки «сам по себе». Инструменты, броня и
         * блоки — нет: иначе бочка ела бы кирки, а бамбук уходил бы в бочку вместо стройки.
         * Съедобные блоки (грибы) — да.
         */
        private static boolean isPlain(Material material) {
            if (material == null || material == Material.AIR || material == Material.POTION) return false;
            try {
                if (!material.isItem()) return false;
                if (material.getMaxStackSize() <= 1) return false;
                return !material.isBlock() || material.isEdible();
            } catch (Throwable ignored) {
                // Без запущенного сервера (в unit-тестах) реестры блоков недоступны:
                // решаем по имени — инструменты и броня не годятся, остальное годится.
                String name = material.name();
                return !(name.endsWith("_BLOCK") || name.endsWith("_ORE") || name.endsWith("_PLANKS")
                        || name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_SLAB")
                        || name.endsWith("_STAIRS") || name.endsWith("_WALL") || name.endsWith("_FENCE")
                        || name.endsWith("_SWORD") || name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                        || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_HELMET")
                        || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                        || name.equals("STONE") || name.equals("DIRT") || name.equals("COBBLESTONE")
                        || name.equals("OBSIDIAN") || name.equals("BEDROCK") || name.equals("SAND")
                        || name.equals("GRAVEL") || name.equals("GRASS_BLOCK"));
            }
        }

        /** Ингредиент по материалу; null — такой материал бочке не годится. */
        static BarrelIngredient byMaterial(Material material) {
            if (material == null) return null;
            BarrelIngredient known = BY_MATERIAL.get(material);
            if (known != null) return known;
            return isPlain(material) ? vanilla(material) : null;
        }

        String id() {
            return id;
        }

        /** Русское название — для сообщений и `/f8 drinks`. */
        String label() {
            return label;
        }

        /** Предмет, который отдаём обратно, если ингредиент забирают из бочки. */
        ItemStack create() {
            return creator.get();
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
            if (item.getType() == Material.POTION) {
                return WaterBottle.isWaterBottle(item) ? WATER_BOTTLE : null;
            }
            return byMaterial(item.getType());
        }

        /**
         * Ингредиент по имени из файла: своё имя ({@code hot_water_bottle}) или имя любого
         * обычного предмета ({@code brown_mushroom}). Понимает и дефисы.
         */
        static BarrelIngredient of(String raw) {
            String id = normalize(raw);
            if (id.isEmpty()) return null;
            BarrelIngredient special = SPECIAL.get(id);
            if (special != null) return special;
            Material material = null;
            try {
                material = Material.matchMaterial(id);
                if (material == null) material = Material.matchMaterial(id.toUpperCase(Locale.ROOT));
            } catch (Throwable ignored) {
                // реестр недоступен (unit-тесты) — ниже пробуем по имени константы
            }
            if (material == null) {
                try {
                    material = Material.valueOf(id.toUpperCase(Locale.ROOT));
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return byMaterial(material);
        }

        /** Все «свои» имена — для подсказки в сообщении об ошибке. */
        static String names() {
            return String.join(", ", SPECIAL.keySet());
        }

        /** Свои ингредиенты — их печатает `/f8 drinks`. */
        static List<BarrelIngredient> values() {
            return List.copyOf(SPECIAL.values());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BarrelIngredient ingredient && ingredient.id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * Рецепт для бочки: «предмет: сколько». Совпадение точное — лишний предмет в бочке
     * останавливает варку, поэтому сладкое вино не превратится в обычное, если сахар
     * положили раньше ягод.
     */
    record BarrelRecipe(Map<BarrelIngredient, Integer> ingredients) {

        BarrelRecipe {
            // Порядок — по id ингредиента: «3 × тростник + 2 × вода» не должен
            // перемешиваться от запуска к запуску.
            Map<BarrelIngredient, Integer> original = ingredients;
            Map<BarrelIngredient, Integer> ordered = new LinkedHashMap<>();
            original.keySet().stream()
                    .sorted(java.util.Comparator.comparing(BarrelIngredient::id))
                    .forEach(key -> ordered.put(key, original.get(key)));
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
                 Method method, List<Shaker.IngredientKind> ingredients, List<Shaker.IngredientKind> additives,
                 List<String> baseDrinks, int alcohol) {
        // baseDrinks — id готовых напитков, которые надо положить в шейкер (коктейль из
        // напитков: дайкири из дайкири и вина). alcohol — единиц опьянения за порцию;
        // 0 — не пьянит. У бочки крепость по качествам, тут это запасное значение.

        Drink {
            ingredients = List.copyOf(ingredients);
            additives = List.copyOf(additives);
            baseDrinks = List.copyOf(baseDrinks);
            alcohol = Math.max(0, alcohol);
        }

        /** Готовится ли в шейкере (и вообще может ли быть смешан). */
        boolean shaker() {
            return method == Method.SHAKER;
        }

        /** Есть ли у напитка свой рецепт: у «мути» ингредиентов нет, это запасной вариант. */
        boolean hasRecipe() {
            return !ingredients.isEmpty() || !baseDrinks.isEmpty();
        }

        /** Положительная ли добавка; всё, чего нет в списке, — отрицательная. */
        boolean positive(Shaker.IngredientKind kind) {
            return kind != null && additives.contains(kind);
        }

        /** Подходит ли набор ингредиентов под этот рецепт. */
        boolean matches(List<Shaker.IngredientKind> kinds) {
            return matches(kinds, List.of());
        }

        /** То же, но ещё проверяем готовые напитки в шейкере (коктейль из напитков). */
        boolean matches(List<Shaker.IngredientKind> kinds, List<String> drinkIds) {
            if (!hasRecipe() || kinds == null) return false;
            for (Shaker.IngredientKind required : ingredients) {
                if (!kinds.contains(required)) return false;
            }
            List<String> present = drinkIds == null ? List.of() : drinkIds;
            for (String required : baseDrinks) {
                if (!present.contains(required)) return false;
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

    /** Что не прочиталось: «напиток X пропущен: причина». Показывается в /f8 drinks. */
    private final List<String> problems;

    private DrinkRecipes(Map<String, Drink> byId, Map<String, BarrelRecipe> barrels) {
        this(byId, barrels, List.of());
    }

    private DrinkRecipes(Map<String, Drink> byId, Map<String, BarrelRecipe> barrels, List<String> problems) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
        this.barrels = Map.copyOf(barrels);
        this.problems = List.copyOf(problems);
    }

    /** Проблемы последнего чтения файла: почему какие-то напитки не попали в список. */
    List<String> problems() {
        return problems;
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
        return match(kinds, List.of());
    }

    /**
     * То же, но с готовыми напитками в шейкере: рецепт может просить, например,
     * «дайкири + белое вино». Порядок — как в файле, побеждает первый подходящий.
     */
    Drink match(List<Shaker.IngredientKind> kinds, List<String> drinkIds) {
        if ((kinds == null || kinds.isEmpty()) && (drinkIds == null || drinkIds.isEmpty())) return null;
        for (Drink drink : shakerDrinks()) {
            if (drink.matches(kinds, drinkIds)) return drink;
        }
        return null;
    }

    /** Единиц опьянения за порцию по настройке {@code alcohol}; 0 — не пьянит. */
    int alcoholOf(String id) {
        Drink drink = get(id);
        return drink == null ? 0 : drink.alcohol();
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
                List.of(Shaker.IngredientKind.SUGAR), List.of(), 0));
        byId.put(Shaker.RECIPE_MATCHA_TEA, new Drink(Shaker.RECIPE_MATCHA_TEA, Shaker.NAME_MATCHA_TEA,
                TextColor.color(0x78A83C), "matcha-chai",
                "Чай с бархатистым вкусом, дающий легкую бодрость и яркость ума.", null, Method.SHAKER,
                List.of(Shaker.IngredientKind.GREEN_DYE, Shaker.IngredientKind.HOT_WATER_BOTTLE),
                List.of(Shaker.IngredientKind.HONEY_BOTTLE, Shaker.IngredientKind.MILK_BUCKET,
                        Shaker.IngredientKind.ICE), List.of(), 0));
        byId.put(Shaker.RECIPE_DAIQUIRI, new Drink(Shaker.RECIPE_DAIQUIRI, Shaker.NAME_DAIQUIRI,
                TextColor.color(0xF0466E), "daikiri", "Безалкогольный дайкири, ",
                new Hint("может стоит в него добавить ", "белое вино", "?..."), Method.SHAKER,
                List.of(Shaker.IngredientKind.SWEET_BERRIES, Shaker.IngredientKind.SUGAR,
                        Shaker.IngredientKind.WATER_BOTTLE),
                List.of(Shaker.IngredientKind.ICE, Shaker.IngredientKind.SWEET_BERRIES),
                List.of(), 0));
        byId.put(Shaker.RECIPE_ICED_LATTE, new Drink(Shaker.RECIPE_ICED_LATTE, Shaker.NAME_ICED_LATTE,
                TextColor.color(0xE8C499), "iced_latte",
                "Прохладный кофейный напиток с кубиками льда... освежает!", null, Method.SHAKER,
                List.of(Shaker.IngredientKind.COCOA_BEANS, Shaker.IngredientKind.ICE,
                        Shaker.IngredientKind.MILK_BUCKET),
                List.of(Shaker.IngredientKind.HONEY_BOTTLE, Shaker.IngredientKind.ICE),
                List.of(), 0));
        byId.put(Shaker.RECIPE_MURK, new Drink(Shaker.RECIPE_MURK, Shaker.NAME_MURK,
                TextColor.color(0x4E9331), "",
                "Какая-то болотная жижа, может стоит попробовать другие ингридиенты?)", null, Method.SHAKER,
                List.of(), List.of(), List.of(), 0));
        byId.put(Shaker.RECIPE_PIRATE_RUM, new Drink(Shaker.RECIPE_PIRATE_RUM, Shaker.NAME_PIRATE_RUM,
                TextColor.color(0x8B3A14), "skull_poilo", null, null, Method.BARREL,
                List.of(), List.of(), List.of(), 0));
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

        // Все id, что вообще есть в файле: на них могут ссылаться коктейли вида
        // «дайкири + белое вино», даже если напиток-ингредиент записан ниже.
        List<String> knownIds = new ArrayList<>();
        for (String rawId : section.getKeys(false)) {
            String id = ProfileText.clean(rawId);
            if (!id.isEmpty()) knownIds.add(id);
        }

        Map<String, Drink> byId = new LinkedHashMap<>();
        Map<String, BarrelRecipe> barrels = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String rawId : section.getKeys(false)) {
            String id = ProfileText.clean(rawId);
            if (id.isEmpty()) continue;
            if (byId.containsKey(id)) {
                problems.add("напиток «" + id + "» записан в файле дважды — второй раз пропущен");
                continue;
            }
            try {
                BarrelRecipe barrel = readBarrelRecipe(
                        section.getConfigurationSection(rawId + ".ingredients"), id);
                Drink drink = readDrink(section, rawId, id, barrel, knownIds);
                byId.put(id, drink);
                if (barrel != null && !barrel.empty()) barrels.put(id, barrel);
            } catch (Exception ex) {
                // Один кривой напиток не должен ронять весь файл: остальные читаются,
                // а причина видна в /f8 drinks и в журнале сервера.
                problems.add("напиток «" + id + "» пропущен: " + ex.getMessage());
            }
        }
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("Ни один напиток не прочитался: " + String.join("; ", problems));
        }
        return new DrinkRecipes(byId, barrels, problems);
    }

    /** Один напиток из файла. Ошибка здесь пропускает только его, остальные читаются. */
    private static Drink readDrink(ConfigurationSection section, String rawId, String id,
                                   BarrelRecipe barrel, List<String> knownIds) {
        String root = rawId + ".";
        String name = ProfileText.clean(section.getString(root + "name", ""));
        if (name.isEmpty()) name = id;
        String colorText = ProfileText.clean(section.getString(root + "color", "#E6B94B"));
        TextColor color = TextColor.fromHexString(colorText);
        if (color == null) throw new IllegalArgumentException("неверный цвет «" + colorText + "»");
        String model = ProfileText.clean(section.getString(root + "model", ""));
        String description = section.getString(root + "description", "");
        Method method = parseMethod(section.getString(root + "method", "shaker"), id);
        if (barrel != null && method != Method.BARREL) {
            throw new IllegalArgumentException("ingredients записан как «предмет: сколько». "
                    + "Так пишут только бочку (method: barrel), а у шейкера — списком");
        }
        Hint hint = readHint(section.getConfigurationSection(root + "hint"), id);

        List<Shaker.IngredientKind> ingredients = new ArrayList<>();
        List<String> baseDrinks = new ArrayList<>();
        if (barrel == null) {
            for (String raw : section.getStringList(root + "ingredients")) {
                Shaker.IngredientKind kind = kindOf(raw);
                if (kind != null) {
                    if (!ingredients.contains(kind)) ingredients.add(kind);
                    continue;
                }
                // Не ингредиент шейкера — может быть готовый напиток (коктейль из напитков).
                String base = ProfileText.clean(raw).toLowerCase(Locale.ROOT)
                        .replace('-', '_').replace(' ', '_');
                if (knownIds.contains(base)) {
                    if (!baseDrinks.contains(base)) baseDrinks.add(base);
                    continue;
                }
                throw new IllegalArgumentException("неизвестный ингредиент «" + raw + "»: это не ингредиент "
                        + "шейкера (" + kindNames() + ") и не id другого напитка из этого файла");
            }
        }
        List<Shaker.IngredientKind> additives = readKinds(section.getStringList(root + "additives"), id);
        int alcohol = readAlcohol(section, root);
        return new Drink(id, name, color, model,
                description == null || description.isEmpty() ? null : description,
                hint, method, ingredients, additives, baseDrinks, alcohol);
    }

    /** Сколько единиц опьянения даёт порция: {@code alcohol: 4}. Нет строки — не пьянит. */
    private static int readAlcohol(ConfigurationSection section, String root) {
        if (!section.contains(root + "alcohol")) return 0;
        Object raw = section.get(root + "alcohol");
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("alcohol записан не числом: «" + raw + "»");
        }
        int units = number.intValue();
        if (units < 0) throw new IllegalArgumentException("alcohol не может быть отрицательным");
        return Math.min(units, 50);
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
        Map<BarrelIngredient, Integer> counts = new LinkedHashMap<>();
        for (String raw : section.getKeys(false)) {
            BarrelIngredient ingredient = BarrelIngredient.of(raw);
            if (ingredient == null) {
                throw new IllegalArgumentException("Напиток " + id + ": бочка не знает предмет «" + raw + "». "
                        + "Можно писать имя любого обычного предмета Minecraft (brown_mushroom) "
                        + "или одно из своих: " + BarrelIngredient.names());
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
                List.of(), List.of(), List.of(), 0));
        buckets(barrels, id, ingredients);
    }

    /** Положить рецепт бочки в карту — короткая запись для {@link #defaults()}. */
    private static void buckets(Map<String, BarrelRecipe> barrels, String id,
                                Map<BarrelIngredient, Integer> ingredients) {
        barrels.put(id, new BarrelRecipe(new LinkedHashMap<>(ingredients)));
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
