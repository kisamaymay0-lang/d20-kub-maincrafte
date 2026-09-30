package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
 * <p>Модель: {@code sheiker.json} из ресурспака (f8resurs:sheiker).
 * В инвентаре содержимое отображается в описании (лоре) аналогично Древнему кувшину.
 *
 * <h2>Механика взаимодействия</h2>
 * <ul>
 *   <li><b>Добавление ингредиентов:</b> Шейкер в главной руке, предмет во второй
 *       руке + Shift + ПКМ — добавляет 1 штуку предмета в шейкер (максимум 5 слотов, по 1 шт).
 *       При добавлении жидкостей (бутылочки мёда, воды или зелий) пустая стеклянная бутылочка
 *       не расходуется, а остаётся у игрока во второй руке.</li>
 *   <li><b>Извлечение ингредиентов:</b> Шейкер в главной руке, пустая вторая рука + Shift + ПКМ —
 *       извлекает последний добавленный предмет напрямую в инвентарь игрока.</li>
 *   <li><b>Смешивание:</b> Быстрые и непрерывные движения камерой вверх-вниз с шейкером в руке.
 *       Требует 16 взмахов практически без пауз (таймаут между взмахами ~0.35с), что исключает
 *       случайное взбивание при строительстве или ходьбе.
 *       По окончании на клиенте вылетают зелёные частицы и звучит победный сигнал.</li>
 *   <li><b>Забор напитка:</b> Пустая стеклянная бутылочка во второй руке + ПКМ забирает
 *       готовый напиток.</li>
 * </ul>
 *
 * <h2>Рецепты</h2>
 * <ul>
 *   <li><b>Медовуха:</b> 1 бутылочка мёда, 2 сахара, 1 бутылочка воды.
 *       Эффекты: Регенерация I (30с), Насыщение I (10с).</li>
 *   <li><b>Дайкири:</b> 1 сахар, 2 сладких ягоды, 1 бутылочка воды, 1 блок любого льда.
 *       Эффекты: Скорость I (30с), Регенерация I (10с).</li>
 *   <li><b>Муть:</b> Любой неудавшийся рецепт / неизвестная смесь. Текстура и цвет зелья отравления.
 *       Эффекты: только Тошнота I (10с) и Отравление I (5с).</li>
 * </ul>
 */
public class Shaker implements Listener {

    public static final int MAX_SLOTS = 5;
    public static final int REQUIRED_STROKES = 16;
    public static final int STROKE_TIMEOUT_TICKS = 7;
    public static final float MIN_STROKE_PITCH = 20.0f;

    public static final String MODEL_NAME = "sheiker";
    public static final NamespacedKey MODEL_KEY = new NamespacedKey("f8resurs", MODEL_NAME);

    public static final NamespacedKey SHAKER_KEY = new NamespacedKey("adaptation", "shaker");
    public static final NamespacedKey CONTENTS_KEY = new NamespacedKey("adaptation", "shaker_contents");
    public static final NamespacedKey MIXED_KEY = new NamespacedKey("adaptation", "shaker_mixed");
    public static final NamespacedKey DRINK_KEY = new NamespacedKey("adaptation", "shaker_drink");
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
     * Создать пустой шейкер.
     */
    public static ItemStack create() {
        ItemStack item = new ItemStack(Material.IRON_NUGGET);
        try {
            item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        } catch (Throwable ignored) {
        }
        updateMeta(item, new ArrayList<>(), false, null);
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
     * Проверка, смешано ли уже содержимое шейкера в готовый напиток.
     */
    public static boolean isMixed(ItemStack item) {
        if (!isShaker(item)) return false;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(MIXED_KEY, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    /**
     * Получить тип напитка в смешанном шейкере.
     */
    public static String getDrink(ItemStack item) {
        if (!isMixed(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(DRINK_KEY, PersistentDataType.STRING);
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
     * Обновить мета-данные шейкера (PDC, имя, модель, подсказку с содержимым).
     */
    public static void updateMeta(ItemStack item, List<ItemStack> contents, boolean mixed, String drink) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.displayName(Component.text("Шейкер", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.setItemModel(MODEL_KEY);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(SHAKER_KEY, PersistentDataType.BYTE, (byte) 1);

        if (mixed && drink != null) {
            pdc.set(MIXED_KEY, PersistentDataType.BYTE, (byte) 1);
            pdc.set(DRINK_KEY, PersistentDataType.STRING, drink);
            pdc.remove(CONTENTS_KEY);
        } else {
            pdc.remove(MIXED_KEY);
            pdc.remove(DRINK_KEY);
            if (contents != null && !contents.isEmpty()) {
                pdc.set(CONTENTS_KEY, PersistentDataType.BYTE_ARRAY, serializeItemList(contents));
            } else {
                pdc.remove(CONTENTS_KEY);
            }
        }

        List<Component> lore = new ArrayList<>();
        if (mixed && drink != null) {
            lore.add(Component.text("Готовый напиток:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            switch (drink) {
                case RECIPE_MEAD -> lore.add(Component.text("• Медовуха", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                case RECIPE_DAIQUIRI -> lore.add(Component.text("• Дайкири", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                default -> lore.add(Component.text("• Муть", NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Возьмите пустую бутылочку во вторую руку", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("и нажмите ПКМ, чтобы забрать напиток.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            int count = contents == null ? 0 : contents.size();
            if (count == 0) {
                lore.add(Component.text("Пустой шейкер (0/" + MAX_SLOTS + ")", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(Component.text("Положите ингредиент во вторую руку", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("и нажмите Shift + ПКМ, чтобы добавить.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Содержимое (" + count + "/" + MAX_SLOTS + "):", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                for (ItemStack ingredient : contents) {
                    lore.add(Component.text("• " + getItemDisplayName(ingredient), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
                }
                lore.add(Component.empty());
                lore.add(Component.text("Трясите камеру вверх-вниз для смешивания.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Shift + ПКМ с пустой рукой — забрать в инвентарь.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
        }

        meta.lore(lore);
        item.setItemMeta(meta);
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
     * Не добавляет ручных строк описания эффектов — ванильный интерфейс зелий
     * отображает эффекты автоматически и без лишнего дублирования.
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
     * Взаимодействие с шейкером: добавление ингредиентов, извлечение в инвентарь, забор готового напитка.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack shaker = player.getInventory().getItemInMainHand();
        if (!isShaker(shaker)) {
            return;
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean hasOffItem = offHand != null && offHand.getType() != Material.AIR;
        boolean sneaking = player.isSneaking();
        boolean mixed = isMixed(shaker);

        // Случай 1: шейкер смешан -> забор напитка в пустую бутылочку
        if (mixed) {
            if (hasOffItem && offHand.getType() == Material.GLASS_BOTTLE) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);

                String drinkType = getDrink(shaker);
                ItemStack drink = createDrinkItem(drinkType);

                // Расходуем 1 бутылочку из второй руки
                if (offHand.getAmount() > 1) {
                    offHand.setAmount(offHand.getAmount() - 1);
                    player.getInventory().setItemInOffHand(offHand);
                    giveOrDrop(player, drink);
                } else {
                    player.getInventory().setItemInOffHand(drink);
                }

                // Очищаем шейкер
                updateMeta(shaker, new ArrayList<>(), false, null);
                player.getInventory().setItemInMainHand(shaker);

                player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, SoundCategory.PLAYERS, 0.7F, 1.1F);
                player.sendActionBar(Component.text("Вы забрали напиток из шейкера!", NamedTextColor.GREEN));
                return;
            }

            if (sneaking) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                player.sendActionBar(Component.text("Возьмите пустую бутылочку во вторую руку, чтобы забрать напиток!", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }
            return;
        }

        // Случай 2: шейкер не смешан -> Shift + ПКМ действия
        if (sneaking) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);

            List<ItemStack> contents = getContents(shaker);

            if (hasOffItem) {
                // Добавление предмета из второй руки
                if (isShaker(offHand)) {
                    player.sendActionBar(Component.text("Нельзя положить шейкер в шейкер!", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                    return;
                }

                if (contents.size() >= MAX_SLOTS) {
                    player.sendActionBar(Component.text("Шейкер полон! (максимум " + MAX_SLOTS + " предметов)", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                    return;
                }

                // Берём 1 штуку из второй руки
                ItemStack inserted = offHand.clone();
                inserted.setAmount(1);
                contents.add(inserted);

                boolean liquid = isBottledLiquid(offHand);
                if (liquid) {
                    // При наливании жидкости бутылочка остаётся у игрока во второй руке
                    ItemStack emptyBottle = new ItemStack(Material.GLASS_BOTTLE);
                    if (offHand.getAmount() > 1) {
                        offHand.setAmount(offHand.getAmount() - 1);
                        player.getInventory().setItemInOffHand(offHand);
                        giveOrDrop(player, emptyBottle);
                    } else {
                        player.getInventory().setItemInOffHand(emptyBottle);
                    }
                    player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, SoundCategory.PLAYERS, 0.7F, 1.0F);
                } else {
                    if (offHand.getAmount() > 1) {
                        offHand.setAmount(offHand.getAmount() - 1);
                        player.getInventory().setItemInOffHand(offHand);
                    } else {
                        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                    }
                    player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.6F, 1.1F);
                }

                updateMeta(shaker, contents, false, null);
                player.getInventory().setItemInMainHand(shaker);

                player.sendActionBar(Component.text("Добавлено: " + getItemDisplayName(inserted) + " (" + contents.size() + "/" + MAX_SLOTS + ")", NamedTextColor.GRAY));
            } else {
                // Извлечение последнего предмета напрямую в инвентарь игрока
                if (contents.isEmpty()) {
                    player.sendActionBar(Component.text("Шейкер пуст!", NamedTextColor.GRAY));
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                    return;
                }

                ItemStack removed = contents.remove(contents.size() - 1);
                giveOrDrop(player, removed);

                updateMeta(shaker, contents, false, null);
                player.getInventory().setItemInMainHand(shaker);

                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, SoundCategory.PLAYERS, 0.6F, 1.1F);
                player.sendActionBar(Component.text("Извлечено в инвентарь: " + getItemDisplayName(removed) + " (" + contents.size() + "/" + MAX_SLOTS + ")", NamedTextColor.GRAY));
            }
        }
    }

    /**
     * Отслеживание взмахов камеры игрока вверх-вниз для смешивания.
     * Требует непрерывных и быстрых взмахов практически без пауз (таймаут 7 тиков ~0.35с),
     * что исключает случайное накопление при строительстве или осмотре блоков.
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
        if (!isShaker(mainHand) || isMixed(mainHand)) {
            return;
        }

        List<ItemStack> contents = getContents(mainHand);
        if (contents.isEmpty()) {
            return;
        }

        int dir = pitchDelta > 0 ? 1 : -1;
        int now = Bukkit.getCurrentTick();
        ShakeTracker tracker = shakeTrackers.computeIfAbsent(player.getUniqueId(), k -> new ShakeTracker());

        // Строгий таймаут без пауз: если пауза между взмахами превысила 7 тиков (~0.35с), сброс в ноль
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
            // Если единичный взмах длится слишком медленно, это плавный поворот, а не энергичный взмах
            if (now - tracker.strokeStartTick > STROKE_TIMEOUT_TICKS) {
                tracker.strokeCount = 0;
                tracker.strokeDelta = 0;
                tracker.currentDir = 0;
            }
        } else {
            // Смена направления взмаха камеры
            int strokeDuration = now - tracker.strokeStartTick;
            if (Math.abs(tracker.strokeDelta) >= MIN_STROKE_PITCH && strokeDuration <= STROKE_TIMEOUT_TICKS) {
                tracker.strokeCount++;
                tracker.lastStrokeTick = now;

                // Звук взбалтывания с повышением тона
                float pitch = 0.9F + (tracker.strokeCount / (float) REQUIRED_STROKES) * 0.7F;
                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.5F, pitch);

                // Визуальный индикатор в action bar
                int percent = (tracker.strokeCount * 100) / REQUIRED_STROKES;
                player.sendActionBar(Component.text("Взбивание: " + percent + "%", NamedTextColor.YELLOW));

                // Порог: 16 непрерывных взмахов (8 полных циклов вверх-вниз)
                if (tracker.strokeCount >= REQUIRED_STROKES) {
                    tracker.strokeCount = 0;
                    tracker.strokeDelta = 0;
                    tracker.currentDir = 0;
                    finishMixing(player, mainHand);
                    return;
                }
            } else {
                // Недостаточная амплитуда или слишком медленный взмах — сброс серии
                tracker.strokeCount = 0;
            }
            tracker.currentDir = dir;
            tracker.strokeDelta = pitchDelta;
            tracker.strokeStartTick = now;
        }
    }

    private void finishMixing(Player player, ItemStack shaker) {
        List<ItemStack> contents = getContents(shaker);
        if (contents.isEmpty() || isMixed(shaker)) {
            return;
        }

        String drink = matchRecipe(contents);
        updateMeta(shaker, contents, true, drink);
        player.getInventory().setItemInMainHand(shaker);

        // Зелёные частицы на клиенте игрока
        Location particleLoc = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.7));
        player.spawnParticle(Particle.HAPPY_VILLAGER, particleLoc, 25, 0.25, 0.25, 0.25, 0.05);

        // Звуки окончания смешивания
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
