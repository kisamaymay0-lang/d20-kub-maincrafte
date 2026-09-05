package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Икра и бутерброды с икрой.
 *
 *  - Шифт+ПКМ по треске/лососю в основной руке "потрошит" рыбу:
 *    с редким шансом в неё попадается икра (0..8 шт.), а рыба заменяется
 *    на опустошённую.
 *  - Крафт: 1 хлеб + 1 икра = 2 бутерброда с икрой
 *    (по питательности как золотая морковка).
 */
public class CaviarListener implements Listener {

    private static final String RED = "red";
    private static final String BLACK = "black";

    private final JavaPlugin plugin;
    private final NamespacedKey caviarTypeKey;
    private final NamespacedKey depletedKey;

    public CaviarListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.caviarTypeKey = new NamespacedKey(plugin, "caviar_type");
        this.depletedKey = new NamespacedKey(plugin, "fish_depleted");

        registerRecipes();
    }

    // ===== РЕЦЕПТЫ =====

    private void registerRecipes() {
        // Треска -> чёрная икра, лосось -> красная (в крафте — наоборот:
        // тип бутерброда зависит от типа икры).
        Bukkit.addRecipe(createSandwichRecipe(RED));
        Bukkit.addRecipe(createSandwichRecipe(BLACK));
    }

    private ShapelessRecipe createSandwichRecipe(String type) {
        ItemStack result = createCaviarSandwich(type);
        result.setAmount(2);

        ShapelessRecipe recipe = new ShapelessRecipe(
                new NamespacedKey(
                        plugin,
                        "caviar_sandwich_" + type
                ),
                result
        );

        recipe.addIngredient(
                new RecipeChoice.MaterialChoice(Material.BREAD)
        );
        recipe.addIngredient(
                new RecipeChoice.ExactChoice(
                        type.equals(RED)
                                ? createRedCaviar()
                                : createBlackCaviar()
                )
        );

        return recipe;
    }

    // ===== СОЗДАНИЕ ПРЕДМЕТОВ =====

    public ItemStack createRedCaviar() {
        return createCaviar(RED);
    }

    public ItemStack createBlackCaviar() {
        return createCaviar(BLACK);
    }

    private ItemStack createCaviar(String type) {
        boolean red = type.equals(RED);

        ItemStack item = new ItemStack(
                red ? Material.RED_DYE : Material.INK_SAC
        );

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(
                    red ? "§cКрасная икра" : "§8Чёрная икра"
            );
            meta.setLore(Collections.singletonList(
                    "§7Добывается из рыбы (шифт + ПКМ)"
            ));
            meta.setItemModel(new NamespacedKey(
                    "f8resurs",
                    red ? "red_caviar" : "black_caviar"
            ));
            meta.getPersistentDataContainer().set(
                    caviarTypeKey,
                    PersistentDataType.STRING,
                    type
            );
            item.setItemMeta(meta);
        }

        return item;
    }

    public ItemStack createDepletedFish(Material original) {
        boolean cod = original == Material.COD;

        ItemStack item = new ItemStack(
                cod ? Material.COD : Material.SALMON
        );

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(
                    cod ? "§7Опустошённая треска"
                        : "§7Опустошённый лосось"
            );
            meta.setItemModel(new NamespacedKey(
                    "f8resurs",
                    cod ? "empty_cod" : "empty_salmon"
            ));
            meta.getPersistentDataContainer().set(
                    depletedKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );
            item.setItemMeta(meta);
        }

        return item;
    }

    public ItemStack createCaviarSandwich(String type) {
        boolean red = type.equals(RED);

        ItemStack item = new ItemStack(Material.BREAD);

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(
                    red
                            ? "§6Бутерброд с красной икрой"
                            : "§6Бутерброд с чёрной икрой"
            );
            meta.setLore(Collections.singletonList(
                    "§7Сытный, как золотая морковка"
            ));
            meta.setItemModel(new NamespacedKey(
                    "f8resurs",
                    red ? "caviar_sandwich_red"
                        : "caviar_sandwich_black"
            ));

            // Питательность как у золотой морковки:
            // 6 голода и 14.4 насыщения.
            FoodComponent food = meta.getFood();

            food.setNutrition(6);
            food.setSaturation(14.4f);
            food.setCanAlwaysEat(false);
            meta.setFood(food);

            item.setItemMeta(meta);
        }

        return item;
    }

    // ===== ОПРЕДЕЛЕНИЕ ПРЕДМЕТОВ =====

    private boolean isCaviar(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(caviarTypeKey, PersistentDataType.STRING);
    }

    private boolean isDepletedFish(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(depletedKey, PersistentDataType.BYTE);
    }

    private boolean isGuttableFish(ItemStack item) {
        if (item == null || isDepletedFish(item)) {
            return false;
        }

        Material type = item.getType();

        return type == Material.COD || type == Material.SALMON;
    }

    // ===== ШИФТ+ПКМ: ПОТРОШЕНИЕ РЫБЫ =====

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // Обрабатываем только основную руку.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();

        if (!player.isSneaking()) {
            return;
        }

        ItemStack fish = player.getInventory().getItemInMainHand();

        if (!isGuttableFish(fish)) {
            return;
        }

        event.setCancelled(true);

        boolean cod = fish.getType() == Material.COD;

        int chance = plugin.getConfig().getInt(
                "caviar.chance-percent",
                12
        );

        int maxAmount = plugin.getConfig().getInt(
                "caviar.max-amount",
                8
        );

        int amount = 0;

        if (ThreadLocalRandom.current().nextInt(100) < chance) {
            amount = 1 + ThreadLocalRandom.current().nextInt(maxAmount);
        }

        // Меняем рыбу на опустошённую.
        if (fish.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(
                    createDepletedFish(fish.getType())
            );
        } else {
            fish.setAmount(fish.getAmount() - 1);
            giveItem(player, createDepletedFish(fish.getType()));
        }

        // Выдаём икру (треска -> чёрная, лосось -> красная).
        if (amount > 0) {
            ItemStack caviar = cod
                    ? createBlackCaviar()
                    : createRedCaviar();

            caviar.setAmount(amount);
            giveItem(player, caviar);

            player.sendActionBar(
                    "§aВ рыбе нашлась икра! (+" + amount + ")"
            );

            player.playSound(
                    player.getLocation(),
                    Sound.ENTITY_ITEM_PICKUP,
                    0.6f,
                    1.3f
            );

        } else {
            player.sendActionBar(
                    "§cВ рыбе не оказалось икры."
            );
        }
    }

    private void giveItem(Player player, ItemStack item) {
        var leftovers = player.getInventory().addItem(item);

        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    leftover
            );
        }
    }
}
