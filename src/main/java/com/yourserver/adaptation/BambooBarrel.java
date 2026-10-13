package com.yourserver.adaptation;

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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Бамбуковая бочка для приготовления пиратского рома:
 * - В бочку добавляются: 1 уголь, 1 сахарный тростник, 3 бутылки горячей воды.
 * - Через 10 секунд бочка покрывается каплями воды.
 * - ПКМ с пустой бутылочкой забирает готовый напиток: «Пиратский ром» (f8resurs:skull_poilo).
 */
public class BambooBarrel implements Listener {

    public static final NamespacedKey MODEL_PIRATE_RUM_KEY = new NamespacedKey("f8resurs", "skull_poilo");
    public static final NamespacedKey PIRATE_RUM_KEY = new NamespacedKey("adaptation", "pirate_rum");

    public static final int BREWING_TIME_TICKS = 200; // 10 секунд

    private final Plugin plugin;
    private final Map<Location, BarrelState> barrels = new HashMap<>();

    public BambooBarrel(Plugin plugin) {
        this.plugin = plugin;
        if (plugin != null) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        }
    }

    public static final class BarrelState {
        public int coalCount = 0;
        public int caneCount = 0;
        public int hotWaterCount = 0;
        public boolean brewing = false;
        public int brewingTicks = 0;
        public boolean ready = false;

        public boolean isEmpty() {
            return coalCount == 0 && caneCount == 0 && hotWaterCount == 0 && !brewing && !ready;
        }
    }

    public static boolean isBambooBarrelBlock(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        return mat == Material.BARREL
                || mat == Material.BAMBOO_BLOCK
                || mat == Material.STRIPPED_BAMBOO_BLOCK
                || mat == Material.BAMBOO_PLANKS
                || mat == Material.BAMBOO_MOSAIC
                || mat == Material.BAMBOO_SLAB
                || mat == Material.BAMBOO_MOSAIC_SLAB
                || mat == Material.BAMBOO_STAIRS
                || mat == Material.BAMBOO_MOSAIC_STAIRS
                || mat == Material.BAMBOO_FENCE
                || mat == Material.BAMBOO_FENCE_GATE
                || mat == Material.BAMBOO_TRAPDOOR;
    }

    public static ItemStack createPirateRum() {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        if (meta != null) {
            meta.setBasePotionType(PotionType.WATER);
            meta.itemName(Component.text("Пиратский ром", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.displayName(null);
            meta.setItemModel(MODEL_PIRATE_RUM_KEY);
            meta.setColor(Color.fromRGB(0x8B, 0x3A, 0x14));
            meta.getPersistentDataContainer().set(PIRATE_RUM_KEY, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(Shaker.DRINK_PDC_KEY, PersistentDataType.STRING, "pirate_rum");
            potion.setItemMeta(meta);
        }
        return potion;
    }

    public static boolean isPirateRum(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(PIRATE_RUM_KEY, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private void tick() {
        Iterator<Map.Entry<Location, BarrelState>> it = barrels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, BarrelState> entry = it.next();
            Location loc = entry.getKey();
            BarrelState state = entry.getValue();

            if (loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                continue;
            }

            Block block = loc.getBlock();
            if (!isBambooBarrelBlock(block)) {
                it.remove();
                continue;
            }

            Location center = loc.clone().add(0.5, 0.5, 0.5);

            if (state.brewing) {
                state.brewingTicks -= 2;
                loc.getWorld().spawnParticle(Particle.SPLASH, center.clone().add(0, 0.4, 0), 3, 0.2, 0.1, 0.2, 0.05);

                if (state.brewingTicks <= 0) {
                    state.brewing = false;
                    state.ready = true;
                    loc.getWorld().playSound(loc, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.8F, 1.2F);
                }
            } else if (state.ready) {
                // Покрывается каплями воды (капли стекают по бочке)
                loc.getWorld().spawnParticle(Particle.DRIPPING_WATER, center.clone().add(0, 0.55, 0), 4, 0.35, 0.1, 0.35, 0.0);
                loc.getWorld().spawnParticle(Particle.FALLING_WATER, center.clone().add(0, 0.3, 0), 2, 0.3, 0.2, 0.3, 0.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !isBambooBarrelBlock(block)) return;

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == null) hand = EquipmentSlot.HAND;

        ItemStack held = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        Location loc = block.getLocation();
        BarrelState state = barrels.computeIfAbsent(loc, k -> new BarrelState());

        // 1. Забор готового рома пустой бутылочкой
        if (state.ready) {
            if (held != null && held.getType() == Material.GLASS_BOTTLE) {
                event.setCancelled(true);

                ItemStack rum = createPirateRum();
                if (held.getAmount() > 1) {
                    held.setAmount(held.getAmount() - 1);
                    setHandItem(player, hand, held);
                    giveOrDrop(player, rum);
                } else {
                    setHandItem(player, hand, rum);
                }

                state.ready = false;
                if (state.isEmpty()) {
                    barrels.remove(loc);
                }

                player.playSound(loc, Sound.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8F, 1.0F);
                return;
            }
        }

        if (held == null || held.getType() == Material.AIR) return;

        // 2. Добавление угля
        if (held.getType() == Material.COAL || held.getType() == Material.CHARCOAL) {
            event.setCancelled(true);
            consumeOne(player, hand, held);
            state.coalCount++;
            player.playSound(loc, Sound.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.6F, 1.0F);
            checkStartBrewing(loc, state);
            return;
        }

        // 3. Добавление сахарного тростника
        if (held.getType() == Material.SUGAR_CANE) {
            event.setCancelled(true);
            consumeOne(player, hand, held);
            state.caneCount++;
            player.playSound(loc, Sound.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.6F, 1.0F);
            checkStartBrewing(loc, state);
            return;
        }

        // 4. Добавление бутылочки горячей воды
        if (Shaker.isHotWaterBottle(held)) {
            event.setCancelled(true);
            ItemStack emptyBottle = new ItemStack(Material.GLASS_BOTTLE);
            if (held.getAmount() > 1) {
                held.setAmount(held.getAmount() - 1);
                setHandItem(player, hand, held);
                giveOrDrop(player, emptyBottle);
            } else {
                setHandItem(player, hand, emptyBottle);
            }
            state.hotWaterCount++;
            player.playSound(loc, Sound.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.7F, 1.0F);
            checkStartBrewing(loc, state);
            return;
        }
    }

    private void checkStartBrewing(Location loc, BarrelState state) {
        if (!state.brewing && !state.ready) {
            if (state.coalCount >= 1 && state.caneCount >= 1 && state.hotWaterCount >= 3) {
                state.coalCount -= 1;
                state.caneCount -= 1;
                state.hotWaterCount -= 3;
                state.brewing = true;
                state.brewingTicks = BREWING_TIME_TICKS;

                loc.getWorld().playSound(loc, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.7F, 1.0F);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        barrels.remove(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            barrels.remove(b.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            barrels.remove(b.getLocation());
        }
    }

    private static void consumeOne(Player player, EquipmentSlot hand, ItemStack item) {
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            setHandItem(player, hand, item);
        } else {
            setHandItem(player, hand, new ItemStack(Material.AIR));
        }
    }

    private static void setHandItem(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(item);
        } else {
            player.getInventory().setItemInOffHand(item);
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }
}
