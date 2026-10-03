package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Шейкер (Shaker) — инструмент для смешивания напитков и коктейлей.
 *
 * <p>Модели: {@code sheiker_open.json} (открытый) и {@code sheiker_close.json} (закрытый).
 *
 * <h2>Механика взаимодействия</h2>
 * <ul>
 *   <li><b>Обе руки:</b> Шейкер можно держать как в основной руке, так и во второй (левой).
 *       Взаимодействие работает симметрично в обеих руках.</li>
 *   <li><b>Открытие и закрытие:</b> Shift + ПКМ без предмета во второй руке переключает
 *       состояние шейкера (открыт/закрыт). Модель меняется на {@code sheiker_open} или
 *       {@code sheiker_close}.</li>
 *   <li><b>Добавление ингредиентов:</b> В открытый шейкер через Shift + ПКМ предметом из другой руки.
 *       При добавлении жидкостей бутылочка опустошается и пустая стеклянная бутылочка остаётся
 *       у игрока в руке. В закрытый шейкер класть нельзя.</li>
 *   <li><b>Готовый напиток как материал:</b> При взбивании готовый напиток остаётся внутри шейкера
 *       как материал (1 слот из 5); не забирая его, можно докладывать другие ингредиенты.</li>
 *   <li><b>Извлечение предметов:</b> Твёрдые предметы из открытого шейкера извлекаются через
 *       Shift + ЛКМ напрямую в инвентарь игрока. Для забора жидкости нужна пустая бутылочка
 *       в руке (ПКМ), иначе выводится «Нужна бутылочка!».</li>
 *   <li><b>Взбивание с открытым шейкером:</b> Если трясти открытый шейкер, первые 4 взмаха
 *       проходят вхолостую, а затем каждые 2 взмаха из шейкера вылетает 1 предмет (выпадает
 *       из игрока). Чтобы напиток успешно смешался, шейкер должен быть закрыт (16 взмахов).</li>
 * </ul>
 */
public class Shaker implements Listener {

    public static final int MAX_SLOTS = 5;
    public static final int REQUIRED_STROKES = 16;
    public static final int STROKE_TIMEOUT_TICKS = 7;
    public static final float MIN_STROKE_PITCH = 20.0f;

    public static final String MODEL_OPEN = "sheiker_open";
    public static final String MODEL_CLOSE = "sheiker_close";
    public static final NamespacedKey MODEL_OPEN_KEY = new NamespacedKey("f8resurs", MODEL_OPEN);
    public static final NamespacedKey MODEL_CLOSE_KEY = new NamespacedKey("f8resurs", MODEL_CLOSE);
    public static final NamespacedKey MODEL_KEY = MODEL_OPEN_KEY;
    public static final String MODEL_NAME = MODEL_OPEN;

    public static final NamespacedKey SHAKER_KEY = new NamespacedKey("adaptation", "shaker");
    public static final NamespacedKey CONTENTS_KEY = new NamespacedKey("adaptation", "shaker_contents");
    public static final NamespacedKey OPEN_KEY = new NamespacedKey("adaptation", "shaker_open");
    public static final NamespacedKey DRINK_PDC_KEY = new NamespacedKey("adaptation", "shaker_drink_type");

    public static final String RECIPE_MEAD = "mead";
    public static final String RECIPE_DAIQUIRI = "daiquiri";
    public static final String RECIPE_MURK = "murk";

    private final Plugin plugin;
    private final Map<UUID, ShakeTracker> shakeTrackers = new HashMap<>();

    public Shaker(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Создать новый шейкер (по умолчанию открытый).
     */
    public static ItemStack create() {
        ItemStack item = new ItemStack(Material.IRON_NUGGET);
        try {
            item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        } catch (Throwable ignored) {
        }
        updateMeta(item, new ArrayList<>());
        return item;
    }

    /**
     * Проверка, является ли предмет шейкером.
     */
    public static boolean isShaker(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(SHAKER_KEY, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    /**
     * Открыт ли шейкер.
     */
    public static boolean isOpen(ItemStack item) {
        if (!isShaker(item)) return false;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(OPEN_KEY, PersistentDataType.BYTE);
        return b == null || b == (byte) 1;
    }

    /**
     * Установить состояние открытости шейкера.
     */
    public static void setOpen(ItemStack item, boolean open) {
        if (!isShaker(item)) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(OPEN_KEY, PersistentDataType.BYTE, (byte) (open ? 1 : 0));
        meta.setItemModel(open ? MODEL_OPEN_KEY : MODEL_CLOSE_KEY);
        item.setItemMeta(meta);
    }

    /**
     * Проверка, есть ли внутри шейкера готовый смешанный напиток.
     */
    public static boolean isMixed(ItemStack item) {
        if (!isShaker(item)) return false;
        List<ItemStack> contents = getContents(item);
        return findDrinkIndex(contents) != -1;
    }

    /**
     * Получить тип первого готового напитка в шейкере.
     */
    public static String getDrink(ItemStack item) {
        if (!isShaker(item)) return null;
        List<ItemStack> contents = getContents(item);
        int idx = findDrinkIndex(contents);
        if (idx == -1) return null;
        return contents.get(idx).getItemMeta().getPersistentDataContainer().get(DRINK_PDC_KEY, PersistentDataType.STRING);
    }

    /**
     * Получить список предметов внутри шейкера.
     */
    public static List<ItemStack> getContents(ItemStack item) {
        if (!isShaker(item)) return new ArrayList<>();
        byte[] bytes = item.getItemMeta().getPersistentDataContainer().get(CONTENTS_KEY, PersistentDataType.BYTE_ARRAY);
        return deserializeItemList(bytes);
    }

    /**
     * Обновить мета-данные шейкера (PDC, имя, модель, список содержимого).
     * В лоре не содержится тёмно-серых подсказок.
     */
    public static void updateMeta(ItemStack item, List<ItemStack> contents) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        boolean open = isOpen(item);

        meta.displayName(Component.text("Шейкер", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.setItemModel(open ? MODEL_OPEN_KEY : MODEL_CLOSE_KEY);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(SHAKER_KEY, PersistentDataType.BYTE, (byte) 1);
        pdc.set(OPEN_KEY, PersistentDataType.BYTE, (byte) (open ? 1 : 0));

        if (contents != null && !contents.isEmpty()) {
            pdc.set(CONTENTS_KEY, PersistentDataType.BYTE_ARRAY, serializeItemList(contents));
        } else {
            pdc.remove(CONTENTS_KEY);
        }

        List<Component> lore = new ArrayList<>();
        int count = contents == null ? 0 : contents.size();
        String status = open ? "(открыт)" : "(закрыт)";
        if (count == 0) {
            lore.add(Component.text("Пустой шейкер (0/" + MAX_SLOTS + ") " + status, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Содержимое (" + count + "/" + MAX_SLOTS + ") " + status + ":", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            for (ItemStack ingredient : contents) {
                lore.add(Component.text("• " + getItemDisplayName(ingredient), getItemColor(ingredient)).decoration(TextDecoration.ITALIC, false));
            }
            if (canShake(contents)) {
                lore.add(Component.empty());
                lore.add(Component.text("Трясите камеру вверх-вниз для смешивания.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            }
        }

        meta.lore(lore);
        item.setItemMeta(meta);
    }

    public static void updateMeta(ItemStack item, List<ItemStack> contents, boolean mixed, String drink) {
        updateMeta(item, contents);
    }

    /**
     * Цвет названия предмета/напитка в подсказке шейкера.
     */
    public static TextColor getItemColor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return NamedTextColor.WHITE;
        String drink = item.getItemMeta().getPersistentDataContainer().get(DRINK_PDC_KEY, PersistentDataType.STRING);
        if (drink != null) {
            return switch (drink) {
                case RECIPE_MEAD -> NamedTextColor.GOLD;
                case RECIPE_DAIQUIRI -> NamedTextColor.AQUA;
                default -> NamedTextColor.DARK_GREEN;
            };
        }
        return NamedTextColor.WHITE;
    }

    /**
     * Человекочитаемое имя предмета в подсказке шейкера.
     */
    public static String getItemDisplayName(ItemStack item) {
        if (item == null) return "Ничего";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        if (WaterBottle.isWaterBottle(item)) {
            return "Бутылочка воды";
        }
        return switch (item.getType()) {
            case HONEY_BOTTLE -> "Бутылочка мёда";
            case SUGAR -> "Сахар";
            case SWEET_BERRIES -> "Сладкие ягоды";
            case ICE -> "Лёд";
            case PACKED_ICE -> "Плотный лёд";
            case BLUE_ICE -> "Синий лёд";
            case FROSTED_ICE -> "Тающий лёд";
            case GLASS_BOTTLE -> "Пустая бутылочка";
            case POTION -> "Зелье";
            case APPLE -> "Яблоко";
            case GOLDEN_APPLE -> "Золотое яблоко";
            case ENCHANTED_GOLDEN_APPLE -> "Зачарованное яблоко";
            case GLOW_BERRIES -> "Светящиеся ягоды";
            case MELON_SLICE -> "Ломтик арбуза";
            case CARROT -> "Морковь";
            case GOLDEN_CARROT -> "Золотая морковь";
            case NETHER_WART -> "Адский нарост";
            case BLAZE_POWDER -> "Огненный порошок";
            case FERMENTED_SPIDER_EYE -> "Маринованный паучий глаз";
            case SPIDER_EYE -> "Паучий глаз";
            case MAGMA_CREAM -> "Сгусток магмы";
            case GHAST_TEAR -> "Слеза гаста";
            case REDSTONE -> "Редстоуновая пыль";
            case GLOWSTONE_DUST -> "Светопыль";
            case GUNPOWDER -> "Порох";
            case DRAGON_BREATH -> "Дыхание дракона";
            case RABBIT_FOOT -> "Кроличья лапка";
            case PHANTOM_MEMBRANE -> "Мембрана фантома";
            case PUFFERFISH -> "Иглобрюх";
            default -> {
                String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                yield Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        };
    }

    /**
     * Проверка, является ли предмет блоком льда любого типа.
     */
    public static boolean isIce(Material m) {
        if (m == null) return false;
        return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE;
    }

    public static boolean isIce(ItemStack item) {
        return item != null && isIce(item.getType());
    }

    /**
     * Проверка, является ли предмет бутилированной жидкостью, оставляющей стеклянную бутылочку.
     */
    public static boolean isBottledLiquid(Material m) {
        if (m == null) return false;
        return m == Material.HONEY_BOTTLE || m == Material.POTION || m == Material.DRAGON_BREATH;
    }

    public static boolean isBottledLiquid(ItemStack item) {
        return item != null && isBottledLiquid(item.getType());
    }

    /**
     * Является ли предмет жидкостью (готовый напиток, зелье, вода или мёд).
     */
    public static boolean isLiquid(ItemStack item) {
        if (item == null) return false;
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(DRINK_PDC_KEY)) {
            return true;
        }
        Material m = item.getType();
        return m == Material.HONEY_BOTTLE || m == Material.POTION || m == Material.DRAGON_BREATH;
    }

    /**
     * Проверка, является ли предмет готовым напитком шейкера.
     */
    public static boolean isDrink(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(DRINK_PDC_KEY);
    }

    /**
     * Можно ли взбивать текущее содержимое шейкера (есть ли несмешанные ингредиенты).
     */
    public static boolean canShake(List<ItemStack> contents) {
        if (contents == null || contents.isEmpty()) return false;
        if (contents.size() == 1 && isDrink(contents.get(0))) return false;
        return true;
    }

    /**
     * Поиск индекса жидкости внутри списка предметов шейкера (с конца).
     */
    public static int findLiquidIndex(List<ItemStack> contents) {
        if (contents == null || contents.isEmpty()) return -1;
        for (int i = contents.size() - 1; i >= 0; i--) {
            if (isLiquid(contents.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Поиск индекса готового напитка шейкера (с конца).
     */
    public static int findDrinkIndex(List<ItemStack> contents) {
        if (contents == null || contents.isEmpty()) return -1;
        for (int i = contents.size() - 1; i >= 0; i--) {
            if (isDrink(contents.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public enum IngredientKind {
        HONEY_BOTTLE,
        WATER_BOTTLE,
        SUGAR,
        SWEET_BERRIES,
        ICE,
        OTHER
    }

    public static IngredientKind classifyIngredient(ItemStack item) {
        if (item == null) return IngredientKind.OTHER;
        if (isDrink(item)) return IngredientKind.OTHER;
        if (WaterBottle.isWaterBottle(item)) return IngredientKind.WATER_BOTTLE;
        if (item.getType() == Material.HONEY_BOTTLE) return IngredientKind.HONEY_BOTTLE;
        if (item.getType() == Material.SUGAR) return IngredientKind.SUGAR;
        if (item.getType() == Material.SWEET_BERRIES) return IngredientKind.SWEET_BERRIES;
        if (isIce(item)) return IngredientKind.ICE;
        return IngredientKind.OTHER;
    }

    public static String matchRecipeFromKinds(List<IngredientKind> kinds) {
        if (kinds == null || kinds.isEmpty()) return null;
        int honey = 0;
        int water = 0;
        int sugar = 0;
        int berries = 0;
        int ice = 0;
        int other = 0;
        for (IngredientKind k : kinds) {
            if (k == null) continue;
            switch (k) {
                case HONEY_BOTTLE -> honey++;
                case WATER_BOTTLE -> water++;
                case SUGAR -> sugar++;
                case SWEET_BERRIES -> berries++;
                case ICE -> ice++;
                case OTHER -> other++;
            }
        }
        // Медовуха: 1 бутылочка меда, 2 сахара, 1 бутылочка воды (всего 4 предмета)
        if (kinds.size() == 4 && honey == 1 && sugar == 2 && water == 1 && other == 0 && berries == 0 && ice == 0) {
            return RECIPE_MEAD;
        }
        // Дайкири: 1 сахар, 2 сладких ягоды, 1 бутылочка воды, 1 блок любого льда (всего 5 предметов)
        if (kinds.size() == 5 && sugar == 1 && berries == 2 && water == 1 && ice == 1 && other == 0 && honey == 0) {
            return RECIPE_DAIQUIRI;
        }
        return RECIPE_MURK;
    }

    /**
     * Определение рецепта по содержащимся предметам.
     */
    public static String matchRecipe(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<IngredientKind> kinds = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            kinds.add(classifyIngredient(item));
        }
        return matchRecipeFromKinds(kinds);
    }

    /**
     * Создать готовый напиток по рецепту.
     */
    public static ItemStack createDrinkItem(String drinkType) {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        if (meta == null) return potion;

        meta.getPersistentDataContainer().set(DRINK_PDC_KEY, PersistentDataType.STRING, drinkType);
        meta.setBasePotionType(PotionType.WATER);

        if (RECIPE_MEAD.equals(drinkType)) {
            meta.displayName(Component.text("Медовуха", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.setColor(Color.fromRGB(235, 175, 40));
            meta.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION, 30 * 20, 0), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.SATURATION, 10 * 20, 0), true);
        } else if (RECIPE_DAIQUIRI.equals(drinkType)) {
            meta.displayName(Component.text("Дайкири", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.setColor(Color.fromRGB(240, 70, 110));
            meta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 30 * 20, 0), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION, 10 * 20, 0), true);
        } else {
            // Муть: текстура/цвет зелья отравления, ровно 10 сек тошноты и 5 сек отравления
            meta.displayName(Component.text("Муть", NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false));
            meta.setColor(Color.fromRGB(78, 147, 49));
            meta.addCustomEffect(new PotionEffect(PotionEffectType.NAUSEA, 10 * 20, 0), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 5 * 20, 0), true);
        }

        potion.setItemMeta(meta);
        return potion;
    }

    /**
     * Обработка выпивания напитков из шейкера.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        String drinkType = item.getItemMeta().getPersistentDataContainer().get(DRINK_PDC_KEY, PersistentDataType.STRING);
        if (drinkType == null) return;

        Player player = event.getPlayer();
        if (RECIPE_MEAD.equals(drinkType)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 30 * 20, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 10 * 20, 0));
        } else if (RECIPE_DAIQUIRI.equals(drinkType)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 30 * 20, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 10 * 20, 0));
        } else if (RECIPE_MURK.equals(drinkType)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 10 * 20, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 5 * 20, 0));
        }
    }

    /**
     * Взаимодействие с шейкером в любой руке:
     * - Открытие/закрытие (Shift + ПКМ без предмета во второй руке)
     * - Загрузка предметов (Shift + ПКМ с предметом)
     * - Извлечение твёрдых предметов в инвентарь (Shift + ЛКМ)
     * - Сбор жидкости в бутылочку (ПКМ с бутылочкой во второй руке)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        EquipmentSlot eventHand = event.getHand();
        if (eventHand != EquipmentSlot.HAND && eventHand != EquipmentSlot.OFF_HAND) {
            return;
        }

        Action action = event.getAction();
        boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;

        if (!isRightClick && !isLeftClick) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainItem = player.getInventory().getItemInMainHand();
        ItemStack offItem = player.getInventory().getItemInOffHand();

        boolean mainHasShaker = isShaker(mainItem);
        boolean offHasShaker = isShaker(offItem);

        if (!mainHasShaker && !offHasShaker) {
            return;
        }

        EquipmentSlot shakerHand;
        EquipmentSlot otherHand;
        ItemStack shaker;
        ItemStack otherItem;

        if (mainHasShaker) {
            shakerHand = EquipmentSlot.HAND;
            otherHand = EquipmentSlot.OFF_HAND;
            shaker = mainItem;
            otherItem = offItem;
            if (eventHand != EquipmentSlot.HAND) {
                return;
            }
        } else {
            shakerHand = EquipmentSlot.OFF_HAND;
            otherHand = EquipmentSlot.HAND;
            shaker = offItem;
            otherItem = mainItem;
            if (eventHand != EquipmentSlot.HAND && otherItem != null && otherItem.getType() != Material.AIR) {
                return;
            }
        }

        boolean hasOtherItem = otherItem != null && otherItem.getType() != Material.AIR;
        boolean sneaking = player.isSneaking();
        List<ItemStack> contents = getContents(shaker);
        boolean open = isOpen(shaker);

        // ========== ЛКМ: Извлечение твёрдых предметов из открытого шейкера ==========
        if (isLeftClick) {
            if (sneaking) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);

                if (!open) {
                    player.sendActionBar(Component.text("Шейкер закрыт! Откройте его (Shift + ПКМ).", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                    return;
                }

                if (contents.isEmpty()) {
                    player.sendActionBar(Component.text("Шейкер пуст!", NamedTextColor.GRAY));
                    return;
                }

                ItemStack top = contents.get(contents.size() - 1);
                if (isLiquid(top)) {
                    player.sendActionBar(Component.text("Нужна бутылочка!", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                    return;
                }

                ItemStack removed = contents.remove(contents.size() - 1);
                giveOrDrop(player, removed);
                updateMeta(shaker, contents);
                setHandItem(player, shakerHand, shaker);

                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, SoundCategory.PLAYERS, 0.6F, 1.1F);
                player.sendActionBar(Component.text("Извлечено в инвентарь: " + getItemDisplayName(removed) + " (" + contents.size() + "/" + MAX_SLOTS + ")", NamedTextColor.GRAY));
                return;
            }
            return;
        }

        // ========== ПКМ: Забор жидкости, открытие/закрытие, добавление предметов ==========

        // 1. Забор жидкости в стеклянную бутылочку
        if (hasOtherItem && otherItem.getType() == Material.GLASS_BOTTLE) {
            if (!open) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                player.sendActionBar(Component.text("Шейкер закрыт! Откройте его (Shift + ПКМ).", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            int liquidIdx = findLiquidIndex(contents);
            if (liquidIdx != -1) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);

                ItemStack liquidItem = contents.remove(liquidIdx);

                if (otherItem.getAmount() > 1) {
                    otherItem.setAmount(otherItem.getAmount() - 1);
                    setHandItem(player, otherHand, otherItem);
                    giveOrDrop(player, liquidItem);
                } else {
                    setHandItem(player, otherHand, liquidItem);
                }

                updateMeta(shaker, contents);
                setHandItem(player, shakerHand, shaker);

                player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, SoundCategory.PLAYERS, 0.7F, 1.1F);
                return;
            }
        }

        // 2. Shift + ПКМ без предмета во второй руке — ОТКРЫТИЕ / ЗАКРЫТИЕ шейкера
        if (sneaking && !hasOtherItem) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);

            boolean newOpen = !open;
            setOpen(shaker, newOpen);
            updateMeta(shaker, contents);
            setHandItem(player, shakerHand, shaker);

            if (newOpen) {
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.7F, 1.3F);
                player.sendActionBar(Component.text("Шейкер открыт", NamedTextColor.GREEN));
            } else {
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.7F, 0.9F);
                player.sendActionBar(Component.text("Шейкер закрыт", NamedTextColor.YELLOW));
            }
            return;
        }

        // 3. Shift + ПКМ с предметом — добавление ингредиента в открытый шейкер
        if (sneaking && hasOtherItem) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);

            if (!open) {
                player.sendActionBar(Component.text("Шейкер закрыт! Откройте его (Shift + ПКМ).", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            if (isShaker(otherItem)) {
                player.sendActionBar(Component.text("Нельзя положить шейкер в шейкер!", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            if (contents.size() >= MAX_SLOTS) {
                player.sendActionBar(Component.text("Шейкер полон! (максимум " + MAX_SLOTS + " предметов)", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            ItemStack inserted = otherItem.clone();
            inserted.setAmount(1);
            contents.add(inserted);

            boolean liquid = isBottledLiquid(otherItem);
            if (liquid) {
                ItemStack emptyBottle = new ItemStack(Material.GLASS_BOTTLE);
                if (otherItem.getAmount() > 1) {
                    otherItem.setAmount(otherItem.getAmount() - 1);
                    setHandItem(player, otherHand, otherItem);
                    giveOrDrop(player, emptyBottle);
                } else {
                    setHandItem(player, otherHand, emptyBottle);
                }
                player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, SoundCategory.PLAYERS, 0.7F, 1.0F);
            } else {
                if (otherItem.getAmount() > 1) {
                    otherItem.setAmount(otherItem.getAmount() - 1);
                    setHandItem(player, otherHand, otherItem);
                } else {
                    setHandItem(player, otherHand, new ItemStack(Material.AIR));
                }
                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.6F, 1.1F);
            }

            updateMeta(shaker, contents);
            setHandItem(player, shakerHand, shaker);

            player.sendActionBar(Component.text("Добавлено: " + getItemDisplayName(inserted) + " (" + contents.size() + "/" + MAX_SLOTS + ")", NamedTextColor.GRAY));
            return;
        }

        // 4. ПКМ без Shift с пустой рукой при наличии жидкости в открытом шейкере
        if (!hasOtherItem && open) {
            int liquidIdx = findLiquidIndex(contents);
            if (liquidIdx != -1) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                player.sendActionBar(Component.text("Нужна бутылочка!", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
            }
        }
    }

    /**
     * Отслеживание взмахов камеры игрока вверх-вниз для смешивания.
     * Если шейкер закрыт: требует 16 непрерывных взмахов.
     * Если шейкер открыт: первые 4 взмаха идут вхолостую, а затем каждые 2 взмаха
     * из шейкера вылетает 1 предмет (выпадает из игрока в мир).
     */
    private static final class ShakeTracker {
        float strokeDelta = 0.0f;
        int currentDir = 0;
        int strokeCount = 0;
        int lastStrokeTick = 0;
        int strokeStartTick = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        float pitchDelta = to.getPitch() - from.getPitch();
        if (Math.abs(pitchDelta) < 1.0f) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        EquipmentSlot shakerHand;
        ItemStack shaker;
        if (isShaker(mainHand)) {
            shakerHand = EquipmentSlot.HAND;
            shaker = mainHand;
        } else if (isShaker(offHand)) {
            shakerHand = EquipmentSlot.OFF_HAND;
            shaker = offHand;
        } else {
            return;
        }

        List<ItemStack> contents = getContents(shaker);
        if (!canShake(contents)) {
            return;
        }

        boolean open = isOpen(shaker);
        int dir = pitchDelta > 0 ? 1 : -1;
        int now = Bukkit.getCurrentTick();
        ShakeTracker tracker = shakeTrackers.computeIfAbsent(player.getUniqueId(), k -> new ShakeTracker());

        // Сброс серии при паузе более 7 тиков
        if (tracker.strokeCount > 0 && now - tracker.lastStrokeTick > STROKE_TIMEOUT_TICKS) {
            tracker.strokeCount = 0;
            tracker.strokeDelta = 0;
            tracker.currentDir = 0;
        }

        if (tracker.currentDir == 0) {
            tracker.currentDir = dir;
            tracker.strokeDelta = pitchDelta;
            tracker.strokeStartTick = now;
        } else if (tracker.currentDir == dir) {
            tracker.strokeDelta += pitchDelta;
            if (now - tracker.strokeStartTick > STROKE_TIMEOUT_TICKS) {
                tracker.strokeCount = 0;
                tracker.strokeDelta = 0;
                tracker.currentDir = 0;
            }
        } else {
            int strokeDuration = now - tracker.strokeStartTick;
            if (Math.abs(tracker.strokeDelta) >= MIN_STROKE_PITCH && strokeDuration <= STROKE_TIMEOUT_TICKS) {
                tracker.strokeCount++;
                tracker.lastStrokeTick = now;

                // Механика открытого шейкера: после 4 взмахов каждые 2 взмаха выпадает 1 предмет
                if (open) {
                    if (tracker.strokeCount > 4 && (tracker.strokeCount - 4) % 2 == 0) {
                        if (!contents.isEmpty()) {
                            ItemStack spilled = contents.remove(contents.size() - 1);
                            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), spilled);
                            dropped.setVelocity(player.getLocation().getDirection().multiply(0.25).add(new Vector(0, 0.15, 0)));

                            player.playSound(player.getLocation(), Sound.ENTITY_SPLASH_POTION_BREAK, SoundCategory.PLAYERS, 0.6F, 1.2F);
                            player.spawnParticle(Particle.SPLASH, player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.5)), 12, 0.2, 0.2, 0.2, 0.08);
                            player.sendActionBar(Component.text("Шейкер открыт! Ингредиенты вылетают!", NamedTextColor.RED));

                            updateMeta(shaker, contents);
                            setHandItem(player, shakerHand, shaker);

                            if (contents.isEmpty()) {
                                tracker.strokeCount = 0;
                                tracker.strokeDelta = 0;
                                tracker.currentDir = 0;
                                return;
                            }
                        }
                    }
                }

                // Звук взбалтывания
                float pitch = 0.9F + (tracker.strokeCount / (float) REQUIRED_STROKES) * 0.7F;
                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.5F, pitch);

                // Порог смешивания: 16 непрерывных взмахов
                if (tracker.strokeCount >= REQUIRED_STROKES) {
                    tracker.strokeCount = 0;
                    tracker.strokeDelta = 0;
                    tracker.currentDir = 0;
                    finishMixing(player, shaker, shakerHand);
                    return;
                }
            } else {
                tracker.strokeCount = 0;
            }
            tracker.currentDir = dir;
            tracker.strokeDelta = pitchDelta;
            tracker.strokeStartTick = now;
        }
    }

    private void finishMixing(Player player, ItemStack shaker, EquipmentSlot shakerHand) {
        List<ItemStack> contents = getContents(shaker);
        if (!canShake(contents)) {
            return;
        }

        String drink = matchRecipe(contents);
        ItemStack drinkItem = createDrinkItem(drink);

        contents.clear();
        contents.add(drinkItem);
        updateMeta(shaker, contents);
        setHandItem(player, shakerHand, shaker);

        Location particleLoc = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.7));
        player.spawnParticle(Particle.HAPPY_VILLAGER, particleLoc, 25, 0.25, 0.25, 0.25, 0.05);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.65F, 1.4F);
        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.PLAYERS, 0.6F, 1.1F);

        String drinkName = switch (drink) {
            case RECIPE_MEAD -> "Медовуха";
            case RECIPE_DAIQUIRI -> "Дайкири";
            default -> "Муть";
        };
        player.sendActionBar(Component.text("Смешано: " + drinkName + "!", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shakeTrackers.remove(event.getPlayer().getUniqueId());
    }

    private static void setHandItem(Player player, EquipmentSlot slot, ItemStack item) {
        if (slot == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(item == null ? new ItemStack(Material.AIR) : item);
        } else {
            player.getInventory().setItemInOffHand(item == null ? new ItemStack(Material.AIR) : item);
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    /**
     * Регистрация рецепта крафта шейкера из 3 слитков железа.
     */
    public void registerRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(plugin, "shaker");
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, create());
        recipe.shape("I I", "I I", " I ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        try {
            Bukkit.addRecipe(recipe);
        } catch (Exception ignored) {
        }
    }

    // ==================== Сериализация содержимого ====================

    public static byte[] serializeItemList(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(items.size());
            for (ItemStack item : items) {
                byte[] b = item.serializeAsBytes();
                dos.writeInt(b.length);
                dos.write(b);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    public static List<ItemStack> deserializeItemList(byte[] bytes) {
        List<ItemStack> list = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            return list;
        }
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            DataInputStream dis = new DataInputStream(bais);
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                int len = dis.readInt();
                byte[] itemBytes = new byte[len];
                dis.readFully(itemBytes);
                ItemStack item = ItemStack.deserializeBytes(itemBytes);
                if (item != null) {
                    list.add(item);
                }
            }
        } catch (Throwable ignored) {
        }
        return list;
    }
}
