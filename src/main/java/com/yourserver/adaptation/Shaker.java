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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Механика предмета «Шейкер» (шейкер для приготовления напитков).
 * <ul>
 *   <li><b>Модели:</b> f8resurs:sheiker_open (открытый) и f8resurs:sheiker_close (закрытый).</li>
 *   <li><b>Вместимость:</b> максимум 5 слотов по 1 предмету.</li>
 *   <li><b>Обе руки:</b> шейкер можно держать как в основной, так и в левой руке.</li>
 *   <li><b>Открытие/закрытие:</b> Shift + ПКМ без предмета во второй руке переключает состояние.</li>
 *   <li><b>Добавление предметов:</b> Shift + ПКМ предметом в свободной руке кладёт его в открытый шейкер.
 *       Запрещены любые блоки, кроме любого льда. При запрете проигрывается тихий звук без сообщений.
 *       При добавлении жидкостей пустая стеклянная бутылочка остаётся у игрока.</li>
 *   <li><b>Готовый напиток как материал:</b> Напиток остаётся внутри шейкера как материал,
 *       и в шейкер можно продолжать докладывать ингредиенты без предварительного извлечения.</li>
 *   <li><b>Извлечение предметов:</b> Твёрдые предметы из открытого шейкера извлекаются через
 *       Shift + ЛКМ напрямую в инвентарь игрока. Для забора жидкости нужна пустая бутылочка
 *       в руке (ПКМ), иначе выводится «Нужна бутылочка!».</li>
 *   <li><b>Взбивание с открытым шейкером:</b> С открытым шейкером нельзя смешивать напитки.
 *       Первые 4 взмаха идут вхолостую, а затем каждые 2 взмаха из шейкера вылетает 1 предмет
 *       (выпадает из игрока со звуком подбирания предметов Sound.ENTITY_ITEM_PICKUP).
 *       Смешивание рецепта происходит только при закрытом шейкере (16 непрерывных взмахов).</li>
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
    private final Map<UUID, Integer> lastInteractTick = new HashMap<>();

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
        updateMeta(item, new ArrayList<>(), true);
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
     * Открыт ли шейкер (по умолчанию считается открытым).
     */
    public static boolean isOpen(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return true;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(OPEN_KEY, PersistentDataType.BYTE);
        return b == null || b == (byte) 1;
    }

    /**
     * Установить состояние открытости шейкера.
     */
    public static void setOpen(ItemStack item, boolean open) {
        if (!isShaker(item)) return;
        List<ItemStack> contents = getContents(item);
        updateMeta(item, contents, open);
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
     * Обновить мета-данные шейкера (сохраняет текущее состояние открытости).
     */
    public static void updateMeta(ItemStack item, List<ItemStack> contents) {
        boolean open = isOpen(item);
        updateMeta(item, contents, open);
    }

    /**
     * Обновить мета-данные шейкера с явным состоянием открытости.
     * В лоре отображается чистый формат без скобочек вокруг цифр («Пустой 0/5», «Содержимое X/5:»).
     */
    public static void updateMeta(ItemStack item, List<ItemStack> contents, boolean open) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

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
        if (count == 0) {
            lore.add(Component.text("Пустой 0/" + MAX_SLOTS, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Содержимое " + count + "/" + MAX_SLOTS + ":", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            for (ItemStack ingredient : contents) {
                lore.add(Component.text("• " + getItemDisplayName(ingredient), getItemColor(ingredient)).decoration(TextDecoration.ITALIC, false));
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
            Component dn = meta.displayName();
            if (dn != null) {
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(dn);
            }
        }
        Material mat = item.getType();
        if (mat == Material.POTION) {
            if (meta instanceof PotionMeta potionMeta) {
                PotionType baseType = potionMeta.getBasePotionType();
                if (baseType == PotionType.WATER) {
                    return "Бутылочка воды";
                }
            }
            return "Бутылочка воды";
        }
        return switch (mat) {
            case HONEY_BOTTLE -> "Бутылочка мёда";
            case SUGAR -> "Сахар";
            case SWEET_BERRIES -> "Сладкие ягоды";
            case ICE -> "Лёд";
            case PACKED_ICE -> "Плотный лёд";
            case BLUE_ICE -> "Синий лёд";
            case FROSTED_ICE -> "Талый лёд";
            case GLASS_BOTTLE -> "Стеклянная бутылочка";
            default -> formatMaterialName(mat);
        };
    }

    private static String formatMaterialName(Material mat) {
        String name = mat.name().toLowerCase().replace('_', ' ');
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Проверка, является ли предмет бутилированной жидкостью, оставляющей пустую бутылочку.
     */
    public static boolean isBottledLiquid(Material type) {
        if (type == null) return false;
        return type == Material.HONEY_BOTTLE
                || type == Material.POTION
                || type == Material.SPLASH_POTION
                || type == Material.LINGERING_POTION
                || type == Material.DRAGON_BREATH;
    }

    public static boolean isBottledLiquid(ItemStack item) {
        return item != null && isBottledLiquid(item.getType());
    }

    /**
     * Проверка, является ли предмет жидкостью (готовый напиток, мёд, зелье/вода).
     */
    public static boolean isLiquid(ItemStack item) {
        if (item == null) return false;
        if (isDrink(item)) return true;
        return isBottledLiquid(item.getType());
    }

    /**
     * Проверка, является ли предмет готовым напитком из шейкера.
     */
    public static boolean isDrink(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(DRINK_PDC_KEY, PersistentDataType.STRING);
    }

    /**
     * Проверка, является ли материал блоком любого типа льда.
     */
    public static boolean isIce(Material type) {
        if (type == null) return false;
        return type == Material.ICE
                || type == Material.PACKED_ICE
                || type == Material.BLUE_ICE
                || type == Material.FROSTED_ICE;
    }

    public static boolean isIceBlock(ItemStack item) {
        if (item == null) return false;
        return isIce(item.getType());
    }

    /**
     * Разрешён ли материал в шейкере.
     * Все блоки запрещены, кроме любого типа льда.
     */
    public static boolean isAllowedIngredient(Material material) {
        if (material == null || material == Material.AIR) return false;
        if (isIce(material)) return true;
        return !material.isBlock();
    }

    /**
     * Разрешён ли предмет для помещения в шейкер.
     */
    public static boolean isAllowedIngredient(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (isShaker(item)) return false;
        return isAllowedIngredient(item.getType());
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

    public static IngredientKind classify(ItemStack item) {
        if (item == null) return IngredientKind.OTHER;
        Material mat = item.getType();
        if (mat == Material.HONEY_BOTTLE) return IngredientKind.HONEY_BOTTLE;
        if (mat == Material.SUGAR) return IngredientKind.SUGAR;
        if (mat == Material.SWEET_BERRIES) return IngredientKind.SWEET_BERRIES;
        if (isIce(mat)) return IngredientKind.ICE;
        if (mat == Material.POTION) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof PotionMeta potionMeta) {
                if (potionMeta.getBasePotionType() == PotionType.WATER) {
                    return IngredientKind.WATER_BOTTLE;
                }
            }
            return IngredientKind.WATER_BOTTLE;
        }
        return IngredientKind.OTHER;
    }

    /**
     * Сопоставление содержимого с рецептом.
     */
    public static String matchRecipe(List<ItemStack> contents) {
        if (contents == null || contents.isEmpty()) return null;
        List<IngredientKind> kinds = new ArrayList<>(contents.size());
        for (ItemStack item : contents) {
            kinds.add(classify(item));
        }
        return matchRecipeFromKinds(kinds);
    }

    /**
     * Сопоставление классифицированных ингредиентов:
     * - Медовуха (4): 1 мед, 2 сахара, 1 вода.
     * - Дайкири (5): 1 сахар, 2 сладких ягоды, 1 вода, 1 лед.
     * - Муть: любые другие комбинации.
     */
    public static String matchRecipeFromKinds(List<IngredientKind> kinds) {
        if (kinds == null || kinds.isEmpty()) return null;

        Map<IngredientKind, Integer> counts = new HashMap<>();
        for (IngredientKind k : kinds) {
            counts.put(k, counts.getOrDefault(k, 0) + 1);
        }

        // Медовуха: ровно 1 мед, 2 сахара, 1 вода (всего 4 предмета)
        if (kinds.size() == 4
                && counts.getOrDefault(IngredientKind.HONEY_BOTTLE, 0) == 1
                && counts.getOrDefault(IngredientKind.SUGAR, 0) == 2
                && counts.getOrDefault(IngredientKind.WATER_BOTTLE, 0) == 1) {
            return RECIPE_MEAD;
        }

        // Дайкири: ровно 1 сахар, 2 сладких ягоды, 1 вода, 1 лед (всего 5 предметов)
        if (kinds.size() == 5
                && counts.getOrDefault(IngredientKind.SUGAR, 0) == 1
                && counts.getOrDefault(IngredientKind.SWEET_BERRIES, 0) == 2
                && counts.getOrDefault(IngredientKind.WATER_BOTTLE, 0) == 1
                && counts.getOrDefault(IngredientKind.ICE, 0) == 1) {
            return RECIPE_DAIQUIRI;
        }

        return RECIPE_MURK;
    }

    /**
     * Создать предмет готового напитка для шейкера.
     */
    public static ItemStack createDrinkItem(String recipe) {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        if (meta == null) return potion;

        meta.getPersistentDataContainer().set(DRINK_PDC_KEY, PersistentDataType.STRING, recipe);

        if (RECIPE_MEAD.equals(recipe)) {
            meta.displayName(Component.text("Медовуха", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.setColor(Color.fromRGB(0xEB, 0xAF, 0x28));
            meta.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION, 30 * 20, 0), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.SATURATION, 10 * 20, 0), true);
            potion.setItemMeta(meta);
        } else if (RECIPE_DAIQUIRI.equals(recipe)) {
            meta.displayName(Component.text("Дайкири", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.setColor(Color.fromRGB(0xF0, 0x46, 0x6E));
            meta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 30 * 20, 0), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION, 10 * 20, 0), true);
            potion.setItemMeta(meta);
        } else {
            meta.setBasePotionType(null);
            meta.displayName(Component.text("Муть", NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false));
            meta.setColor(Color.fromRGB(0x4E, 0x93, 0x31));
            meta.clearCustomEffects();
            meta.addCustomEffect(new PotionEffect(PotionEffectType.NAUSEA, 10 * 20, 0), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 5 * 20, 0), true);
            potion.setItemMeta(meta);
        }
        return potion;
    }

    /**
     * Сериализация списка предметов в байты.
     */
    public static byte[] serializeItemList(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return new byte[0];
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(items.size());
            for (ItemStack is : items) {
                byte[] itemBytes = is == null ? new byte[0] : is.serializeAsBytes();
                dos.writeInt(itemBytes.length);
                if (itemBytes.length > 0) {
                    dos.write(itemBytes);
                }
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Десериализация списка предметов из байтов.
     */
    public static List<ItemStack> deserializeItemList(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new ArrayList<>();
        List<ItemStack> list = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {
            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                int len = dis.readInt();
                if (len > 0) {
                    byte[] itemBytes = new byte[len];
                    dis.readFully(itemBytes);
                    try {
                        ItemStack item = ItemStack.deserializeBytes(itemBytes);
                        list.add(item);
                    } catch (Throwable t) {
                        list.add(new ItemStack(Material.AIR));
                    }
                } else {
                    list.add(new ItemStack(Material.AIR));
                }
            }
        } catch (IOException e) {
            return new ArrayList<>();
        }
        return list;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        EquipmentSlot eventHand = event.getHand();
        if (eventHand != EquipmentSlot.HAND && eventHand != EquipmentSlot.OFF_HAND) {
            return;
        }

        boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;

        if (!isRightClick && !isLeftClick) {
            return;
        }

        Player player = event.getPlayer();

        // Защита от дублирования событий между основной и второй рукой в одном тике
        int currentTick = Bukkit.getCurrentTick();
        Integer lastTick = lastInteractTick.get(player.getUniqueId());
        if (lastTick != null && lastTick == currentTick) {
            event.setCancelled(true);
            return;
        }

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
            // При шейкере во второй руке событие обрабатывается только один раз
            if (otherItem != null && otherItem.getType() != Material.AIR) {
                if (eventHand != EquipmentSlot.HAND) {
                    return;
                }
            } else {
                if (eventHand != EquipmentSlot.OFF_HAND) {
                    return;
                }
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
                lastInteractTick.put(player.getUniqueId(), currentTick);

                if (!open) {
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                    return;
                }

                if (contents.isEmpty()) {
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
                updateMeta(shaker, contents, true);
                setHandItem(player, shakerHand, shaker);

                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, SoundCategory.PLAYERS, 0.6F, 1.1F);
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
                lastInteractTick.put(player.getUniqueId(), currentTick);
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            int liquidIdx = findLiquidIndex(contents);
            if (liquidIdx != -1) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                lastInteractTick.put(player.getUniqueId(), currentTick);

                ItemStack liquidItem = contents.remove(liquidIdx);

                if (otherItem.getAmount() > 1) {
                    otherItem.setAmount(otherItem.getAmount() - 1);
                    setHandItem(player, otherHand, otherItem);
                    giveOrDrop(player, liquidItem);
                } else {
                    setHandItem(player, otherHand, liquidItem);
                }

                updateMeta(shaker, contents, true);
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
            lastInteractTick.put(player.getUniqueId(), currentTick);

            boolean newOpen = !open;
            updateMeta(shaker, contents, newOpen);
            setHandItem(player, shakerHand, shaker);

            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.7F, newOpen ? 1.3F : 0.9F);
            return;
        }

        // 3. Shift + ПКМ с предметом — добавление ингредиента в открытый шейкер
        if (sneaking && hasOtherItem) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            lastInteractTick.put(player.getUniqueId(), currentTick);

            if (!open) {
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            if (isShaker(otherItem)) {
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            // Запрещены все блоки, кроме любого типа льда (тихий отказ)
            if (!isAllowedIngredient(otherItem)) {
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
                return;
            }

            if (contents.size() >= MAX_SLOTS) {
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

            // Шейкер остаётся открытым после добавления предмета
            updateMeta(shaker, contents, true);
            setHandItem(player, shakerHand, shaker);
            return;
        }

        // 4. ПКМ без Shift с пустой рукой при наличии жидкости в открытом шейкере
        if (!hasOtherItem && open) {
            int liquidIdx = findLiquidIndex(contents);
            if (liquidIdx != -1) {
                event.setCancelled(true);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                lastInteractTick.put(player.getUniqueId(), currentTick);
                player.sendActionBar(Component.text("Нужна бутылочка!", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS, 0.5F, 1.2F);
            }
        }
    }

    /**
     * Отслеживание взмахов камеры игрока вверх-вниз для смешивания.
     * Если шейкер закрыт: требует 16 непрерывных взмахов для смешивания.
     * Если шейкер открыт: смешивание невозможно — первые 4 взмаха идут вхолостую,
     * а затем каждые 2 взмаха из шейкера вылетает 1 предмет со звуком подбирания предметов.
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
        if (contents.isEmpty()) {
            return;
        }

        boolean open = isOpen(shaker);
        if (!open && !canShake(contents)) {
            return;
        }

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
                tracker.strokeDelta = pitchDelta;
                tracker.strokeStartTick = now;
            }
        } else {
            int strokeDuration = now - tracker.strokeStartTick;
            if (Math.abs(tracker.strokeDelta) >= MIN_STROKE_PITCH && strokeDuration <= STROKE_TIMEOUT_TICKS) {
                tracker.strokeCount++;
                tracker.lastStrokeTick = now;

                // Механика открытого шейкера: смешивание невозможно, только вылетают предметы
                if (open) {
                    if (tracker.strokeCount > 4 && (tracker.strokeCount - 4) % 2 == 0) {
                        if (!contents.isEmpty()) {
                            ItemStack spilled = contents.remove(contents.size() - 1);
                            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), spilled);
                            dropped.setVelocity(player.getLocation().getDirection().multiply(0.35).add(new Vector(0, 0.2, 0)));

                            // Звук вылета предметов как звук подбирания предметов (ENTITY_ITEM_PICKUP)
                            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1.0F, 1.0F);
                            player.spawnParticle(Particle.SPLASH, player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.5)), 15, 0.25, 0.25, 0.25, 0.1);

                            updateMeta(shaker, contents, true);
                            setHandItem(player, shakerHand, shaker);

                            if (contents.isEmpty()) {
                                tracker.strokeCount = 0;
                                tracker.strokeDelta = 0;
                                tracker.currentDir = 0;
                                return;
                            }
                        }
                    }
                    // Звук взбалтывания (с открытым шейкером не смешивается)
                    player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.5F, 1.0F);
                    tracker.currentDir = dir;
                    tracker.strokeDelta = pitchDelta;
                    tracker.strokeStartTick = now;
                    return;
                }

                // Закрытый шейкер: звук взбалтывания с повышением тона
                float pitch = 0.9F + (tracker.strokeCount / (float) REQUIRED_STROKES) * 0.7F;
                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.5F, pitch);

                // Порог смешивания: 16 непрерывных взмахов (только при закрытом шейкере)
                if (tracker.strokeCount >= REQUIRED_STROKES) {
                    tracker.strokeCount = 0;
                    tracker.strokeDelta = 0;
                    tracker.currentDir = 0;
                    finishMixing(player, shaker, shakerHand);
                    return;
                }
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
        updateMeta(shaker, contents, false);
        setHandItem(player, shakerHand, shaker);

        Location particleLoc = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.7));
        player.spawnParticle(Particle.HAPPY_VILLAGER, particleLoc, 25, 0.25, 0.25, 0.25, 0.05);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.65F, 1.4F);
        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.PLAYERS, 0.6F, 1.1F);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shakeTrackers.remove(event.getPlayer().getUniqueId());
        lastInteractTick.remove(event.getPlayer().getUniqueId());
    }

    private static void setHandItem(Player player, EquipmentSlot slot, ItemStack item) {
        if (slot == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(item);
        } else if (slot == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack rem : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rem);
            }
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
}
