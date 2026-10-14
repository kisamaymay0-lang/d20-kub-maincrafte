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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Бамбуковая бочка для приготовления «Пиратского рома» (3x3x3 мультблок по NBT-структуре bamboo-bo4ka):
 * <ul>
 *   <li><b>Структура 3x3x3:</b> Состоит из обтёсанных бамбуковых блоков (stripped_bamboo_block) и бамбуковых ступеней (bamboo_stairs) с полой сердцевиной (air).</li>
 *   <li><b>Ориентация:</b> Поддерживается горизонтальное расположение вдоль оси X и вдоль оси Z.</li>
 *   <li><b>Ингредиенты:</b> 1 Уголь/Древесный уголь, 1 Сахарный тростник, 3 Бутылочки горячей воды.</li>
 *   <li><b>Добавление:</b> При добавлении предметов из бочки вылетают частицы WAX_OFF.</li>
 *   <li><b>Извлечение:</b> На Shift + ПКМ можно забирать ингредиенты из бочки (для сбора жидкости нужна пустая стеклянная бутылочка).</li>
 *   <li><b>Брожение:</b> 10 секунд (200 тиков) с визуальными эффектами.</li>
 *   <li><b>Готовность:</b> После завершения брожения вся 3x3 поверхность бочки покрывается стекающими каплями воды (dripping_water / falling_water).</li>
 *   <li><b>Забор рома:</b> ПКМ с пустой бутылочкой забирает «Пиратский ром» (модель f8resurs:skull_poilo).</li>
 *   <li><b>Разрушение бочки:</b> Твёрдые ресурсы выпадают в центре, а жидкость разливается и не выпадает.</li>
 *   <li><b>Обратная связь:</b> Исключительно внутриигровая (звуки, партиклы, предметы) без лишнего чата.</li>
 * </ul>
 */
public class BambooBarrel implements Listener {

    public static final NamespacedKey MODEL_PIRATE_RUM_KEY = new NamespacedKey("f8resurs", "skull_poilo");
    public static final NamespacedKey PIRATE_RUM_KEY = new NamespacedKey("adaptation", "pirate_rum");

    public static final int BREWING_TIME_TICKS = 200; // 10 секунд
    private static final Random RANDOM = new Random();

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

    // ===== ПРОВЕРКА МУЛЬТИБЛОКА (3x3x3 NBT СТРУКТУРА) =====

    public static boolean isBarrelWall(Material mat) {
        return mat == Material.STRIPPED_BAMBOO_BLOCK || mat == Material.BAMBOO_BLOCK;
    }

    public static boolean isBarrelStairs(Material mat) {
        return mat == Material.BAMBOO_STAIRS || mat == Material.BAMBOO_MOSAIC_STAIRS;
    }

    public static boolean isCavity(Material mat) {
        return mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR;
    }

    public static boolean isBambooBarrelBlock(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        return isBarrelWall(mat) || isBarrelStairs(mat);
    }

    private static boolean checkStairs(Block block, BlockFace expectedFacing, Bisected.Half expectedHalf) {
        if (block == null) return false;
        if (!isBarrelStairs(block.getType())) return false;
        if (block.getBlockData() instanceof Stairs stairs) {
            if (expectedFacing != null && stairs.getFacing() != expectedFacing) {
                return false;
            }
            if (expectedHalf != null && stairs.getHalf() != expectedHalf) {
                return false;
            }
        }
        return true;
    }

    /**
     * Проверка структуры, ориентированной вдоль оси X (длина 3 по X, сечение по Y-Z).
     */
    public static boolean matchesXAligned(World world, int cx, int cy, int cz) {
        if (world == null) return false;

        // dy = -1 (низ бочки)
        for (int dx = -1; dx <= 1; dx++) {
            if (!checkStairs(world.getBlockAt(cx + dx, cy - 1, cz - 1), BlockFace.SOUTH, Bisected.Half.TOP)) return false;
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy - 1, cz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + dx, cy - 1, cz + 1), BlockFace.NORTH, Bisected.Half.TOP)) return false;
        }

        // dy = 0 (средний слой)
        for (int dx = -1; dx <= 1; dx++) {
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy, cz - 1).getType())) return false;
            if (dx == 0) {
                if (!isCavity(world.getBlockAt(cx, cy, cz).getType())) return false;
            } else {
                if (!isBarrelWall(world.getBlockAt(cx + dx, cy, cz).getType())) return false;
            }
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy, cz + 1).getType())) return false;
        }

        // dy = +1 (верх бочки)
        for (int dx = -1; dx <= 1; dx++) {
            if (!checkStairs(world.getBlockAt(cx + dx, cy + 1, cz - 1), BlockFace.SOUTH, Bisected.Half.BOTTOM)) return false;
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy + 1, cz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + dx, cy + 1, cz + 1), BlockFace.NORTH, Bisected.Half.BOTTOM)) return false;
        }

        return true;
    }

    /**
     * Проверка структуры, ориентированной вдоль оси Z (длина 3 по Z, сечение по X-Y).
     */
    public static boolean matchesZAligned(World world, int cx, int cy, int cz) {
        if (world == null) return false;

        // dy = -1 (низ бочки)
        for (int dz = -1; dz <= 1; dz++) {
            if (!checkStairs(world.getBlockAt(cx - 1, cy - 1, cz + dz), BlockFace.EAST, Bisected.Half.TOP)) return false;
            if (!isBarrelWall(world.getBlockAt(cx, cy - 1, cz + dz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + 1, cy - 1, cz + dz), BlockFace.WEST, Bisected.Half.TOP)) return false;
        }

        // dy = 0 (средний слой)
        for (int dz = -1; dz <= 1; dz++) {
            if (!isBarrelWall(world.getBlockAt(cx - 1, cy, cz + dz).getType())) return false;
            if (dz == 0) {
                if (!isCavity(world.getBlockAt(cx, cy, cz).getType())) return false;
            } else {
                if (!isBarrelWall(world.getBlockAt(cx, cy, cz + dz).getType())) return false;
            }
            if (!isBarrelWall(world.getBlockAt(cx + 1, cy, cz + dz).getType())) return false;
        }

        // dy = +1 (верх бочки)
        for (int dz = -1; dz <= 1; dz++) {
            if (!checkStairs(world.getBlockAt(cx - 1, cy + 1, cz + dz), BlockFace.EAST, Bisected.Half.BOTTOM)) return false;
            if (!isBarrelWall(world.getBlockAt(cx, cy + 1, cz + dz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + 1, cy + 1, cz + dz), BlockFace.WEST, Bisected.Half.BOTTOM)) return false;
        }

        return true;
    }

    /**
     * Проверяет, является ли блок [cx, cy, cz] валидным центром 3x3x3 бочки.
     */
    public static boolean isValidBarrelCenter(World world, int cx, int cy, int cz) {
        if (world == null) return false;
        // Внутри бочки обязательно должно быть пусто
        if (!isCavity(world.getBlockAt(cx, cy, cz).getType())) {
            return false;
        }
        return matchesXAligned(world, cx, cy, cz) || matchesZAligned(world, cx, cy, cz);
    }

    /**
     * По кликнутому блоку находит центр мультиблочной бамбуковой бочки (если он входит в её состав).
     */
    public static Location findBarrelCenter(Block clickedBlock) {
        if (clickedBlock == null) return null;
        World world = clickedBlock.getWorld();
        if (world == null) return null;

        int bx = clickedBlock.getX();
        int by = clickedBlock.getY();
        int bz = clickedBlock.getZ();

        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    int cx = bx + ox;
                    int cy = by + oy;
                    int cz = bz + oz;
                    if (isValidBarrelCenter(world, cx, cy, cz)) {
                        return new Location(world, cx, cy, cz);
                    }
                }
            }
        }
        return null;
    }

    // ===== СОЗДАНИЕ И ПРОВЕРКА ПИРАТСКОГО РОМА =====

    public static ItemStack createPirateRum() {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        if (meta != null) {
            meta.setBasePotionType(PotionType.WATER);
            Component name = Component.text("Пиратский ром", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false);
            meta.itemName(name);
            meta.displayName(name);
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

    // ===== ТИК И ВИЗУАЛЬНЫЕ ЭФФЕКТЫ (ПО ВСЕЙ СТРУКТУРЕ 3x3) =====

    private void tick() {
        Iterator<Map.Entry<Location, BarrelState>> it = barrels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, BarrelState> entry = it.next();
            Location centerLoc = entry.getKey();
            BarrelState state = entry.getValue();

            World world = centerLoc.getWorld();
            if (world == null || !world.isChunkLoaded(centerLoc.getBlockX() >> 4, centerLoc.getBlockZ() >> 4)) {
                continue;
            }

            int cx = centerLoc.getBlockX();
            int cy = centerLoc.getBlockY();
            int cz = centerLoc.getBlockZ();

            // Проверка целостности бочки (мультиблок 3x3x3 с пустой полостью внутри)
            if (!isValidBarrelCenter(world, cx, cy, cz)) {
                dropIngredients(world, centerLoc, state);
                it.remove();
                continue;
            }

            if (state.brewing) {
                state.brewingTicks -= 2;

                // Партиклы пара и кипения по верхней плоскости 3x3 структуры бочки
                for (int i = 0; i < 3; i++) {
                    double px = (cx - 0.8) + RANDOM.nextDouble() * 2.6;
                    double pz = (cz - 0.8) + RANDOM.nextDouble() * 2.6;
                    world.spawnParticle(Particle.SPLASH, px, cy + 1.2, pz, 2, 0.15, 0.1, 0.15, 0.05);
                }
                if (RANDOM.nextFloat() < 0.4f) {
                    double px = (cx - 0.5) + RANDOM.nextDouble() * 2.0;
                    double pz = (cz - 0.5) + RANDOM.nextDouble() * 2.0;
                    world.spawnParticle(Particle.BUBBLE_POP, px, cy + 1.4, pz, 1, 0.0, 0.05, 0.0, 0.01);
                }

                if (state.brewingTicks <= 0) {
                    state.brewing = false;
                    state.ready = true;
                    world.playSound(centerLoc, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.8F, 1.2F);
                    world.playSound(centerLoc, Sound.BLOCK_CONDUIT_ACTIVATE, SoundCategory.BLOCKS, 0.5F, 1.5F);
                }
            } else if (state.ready) {
                // Покрывается каплями воды по всей внешней поверхности структуры 3x3
                for (int i = 0; i < 6; i++) {
                    double px = (cx - 1.0) + RANDOM.nextDouble() * 3.0;
                    double pz = (cz - 1.0) + RANDOM.nextDouble() * 3.0;
                    double py = (cy - 1.0) + RANDOM.nextDouble() * 3.0;

                    if (RANDOM.nextBoolean()) {
                        // Капли сверху и скатывающиеся вниз
                        world.spawnParticle(Particle.DRIPPING_WATER, px, cy + 2.02, pz, 1, 0.0, 0.0, 0.0, 0.0);
                        world.spawnParticle(Particle.FALLING_WATER, px, cy + 1.8, pz, 1, 0.0, 0.0, 0.0, 0.0);
                    } else {
                        // Капли по бокам корпуса
                        world.spawnParticle(Particle.DRIPPING_WATER, px, py, pz, 1, 0.02, 0.02, 0.02, 0.0);
                    }
                }
            }
        }
    }

    // ===== ВЗАИМОДЕЙСТВИЕ ИГРОКА =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !isBambooBarrelBlock(block)) return;

        Location centerLoc = findBarrelCenter(block);
        if (centerLoc == null) return;

        Player player = event.getPlayer();
        World world = block.getWorld();
        EquipmentSlot hand = event.getHand();
        if (hand == null) hand = EquipmentSlot.HAND;

        ItemStack held = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        BarrelState state = barrels.computeIfAbsent(centerLoc, k -> new BarrelState());

        // 1. Shift + ПКМ: Забор предметов из бочки
        if (player.isSneaking()) {
            if (!state.brewing && !state.ready) {
                // Забор жидкости: требуется пустая бутылочка
                if (held != null && held.getType() == Material.GLASS_BOTTLE && state.hotWaterCount > 0) {
                    event.setCancelled(true);
                    consumeOne(player, hand, held);
                    ItemStack hotWater = Shaker.createHotWaterBottle();
                    giveOrDrop(player, hotWater);
                    state.hotWaterCount--;
                    player.playSound(centerLoc, Sound.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8F, 1.0F);
                    world.spawnParticle(Particle.SPLASH, block.getLocation().add(0.5, 0.8, 0.5), 4, 0.1, 0.1, 0.1, 0.02);
                    if (state.isEmpty()) {
                        barrels.remove(centerLoc);
                    }
                    return;
                }

                // Забор твёрдых ресурсов (сахарный тростник)
                if (state.caneCount > 0) {
                    event.setCancelled(true);
                    state.caneCount--;
                    giveOrDrop(player, new ItemStack(Material.SUGAR_CANE));
                    player.playSound(centerLoc, Sound.ITEM_BUNDLE_REMOVE_ONE, SoundCategory.BLOCKS, 0.7F, 1.1F);
                    world.spawnParticle(Particle.WAX_OFF, block.getLocation().add(0.5, 0.8, 0.5), 3, 0.1, 0.1, 0.1, 0.02);
                    if (state.isEmpty()) {
                        barrels.remove(centerLoc);
                    }
                    return;
                }

                // Забор твёрдых ресурсов (уголь)
                if (state.coalCount > 0) {
                    event.setCancelled(true);
                    state.coalCount--;
                    giveOrDrop(player, new ItemStack(Material.COAL));
                    player.playSound(centerLoc, Sound.ITEM_BUNDLE_REMOVE_ONE, SoundCategory.BLOCKS, 0.7F, 1.1F);
                    world.spawnParticle(Particle.WAX_OFF, block.getLocation().add(0.5, 0.8, 0.5), 3, 0.1, 0.1, 0.1, 0.02);
                    if (state.isEmpty()) {
                        barrels.remove(centerLoc);
                    }
                    return;
                }

                // Если в бочке только жидкость, а у игрока нет пустой бутылочки
                if (state.hotWaterCount > 0) {
                    event.setCancelled(true);
                    player.playSound(centerLoc, Sound.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.4F, 1.5F);
                    return;
                }
            }
            return;
        }

        // 2. Забор готового рома пустой бутылочкой (обычный ПКМ)
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
                    barrels.remove(centerLoc);
                }

                player.playSound(centerLoc, Sound.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8F, 1.0F);
                return;
            }
            return;
        }

        if (state.brewing) {
            return;
        }

        if (held == null || held.getType() == Material.AIR) return;

        // 3. Добавление угля (требуется 1 шт.)
        if (held.getType() == Material.COAL || held.getType() == Material.CHARCOAL) {
            if (state.coalCount < 1) {
                event.setCancelled(true);
                consumeOne(player, hand, held);
                state.coalCount++;
                spawnAddParticles(world, block, centerLoc);
                player.playSound(centerLoc, Sound.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.7F, 1.0F);
                checkStartBrewing(centerLoc, state);
            }
            return;
        }

        // 4. Добавление сахарного тростника (требуется 1 шт.)
        if (held.getType() == Material.SUGAR_CANE) {
            if (state.caneCount < 1) {
                event.setCancelled(true);
                consumeOne(player, hand, held);
                state.caneCount++;
                spawnAddParticles(world, block, centerLoc);
                player.playSound(centerLoc, Sound.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.7F, 1.0F);
                checkStartBrewing(centerLoc, state);
            }
            return;
        }

        // 5. Добавление бутылочки горячей воды (требуется 3 шт.)
        if (Shaker.isHotWaterBottle(held)) {
            if (state.hotWaterCount < 3) {
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
                spawnAddParticles(world, block, centerLoc);
                player.playSound(centerLoc, Sound.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.7F, 1.0F);
                checkStartBrewing(centerLoc, state);
            }
            return;
        }
    }

    private static void spawnAddParticles(World world, Block clickedBlock, Location centerLoc) {
        if (world == null) return;
        Location clickPos = clickedBlock != null ? clickedBlock.getLocation().add(0.5, 0.8, 0.5) : centerLoc.clone().add(0.5, 1.2, 0.5);
        world.spawnParticle(Particle.WAX_OFF, clickPos, 7, 0.25, 0.25, 0.25, 0.05);
        Location topCenter = centerLoc.clone().add(0.5, 1.5, 0.5);
        world.spawnParticle(Particle.WAX_OFF, topCenter, 5, 0.3, 0.1, 0.3, 0.05);
    }

    private void checkStartBrewing(Location centerLoc, BarrelState state) {
        if (!state.brewing && !state.ready) {
            if (state.coalCount >= 1 && state.caneCount >= 1 && state.hotWaterCount >= 3) {
                state.coalCount = 0;
                state.caneCount = 0;
                state.hotWaterCount = 0;
                state.brewing = true;
                state.brewingTicks = BREWING_TIME_TICKS;

                World world = centerLoc.getWorld();
                if (world != null) {
                    world.playSound(centerLoc, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.8F, 0.9F);
                }
            }
        }
    }

    // ===== УПОТРЕБЛЕНИЕ НАПИТКА =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (isPirateRum(item)) {
            Player player = event.getPlayer();
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.8F, 0.9F);
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 15, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 15, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 20 * 6, 0));
        }
    }

    // ===== РАЗРУШЕНИЕ БЛОКОВ И ДРОП ИНГРЕДИЕНТОВ =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        handleBlockDestroyed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            handleBlockDestroyed(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            handleBlockDestroyed(b);
        }
    }

    private void handleBlockDestroyed(Block block) {
        if (block == null) return;
        World world = block.getWorld();
        int bx = block.getX();
        int by = block.getY();
        int bz = block.getZ();

        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    Location center = new Location(world, bx + ox, by + oy, bz + oz);
                    BarrelState state = barrels.remove(center);
                    if (state != null) {
                        dropIngredients(world, center, state);
                    }
                }
            }
        }
    }

    private void dropIngredients(World world, Location loc, BarrelState state) {
        if (world == null || loc == null || state == null) return;
        Location dropLoc = loc.clone().add(0.5, 0.5, 0.5);

        if (state.ready) {
            // Готовый ром (жидкость) разливается и не выпадает в виде предмета
            world.spawnParticle(Particle.SPLASH, dropLoc, 25, 0.6, 0.5, 0.6, 0.1);
            world.playSound(dropLoc, Sound.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.8F, 0.9F);
            return;
        }

        if (state.brewing) {
            // Твёрдые ресурсы выпадают в центре, а варящаяся жидкость разливается
            world.dropItemNaturally(dropLoc, new ItemStack(Material.COAL));
            world.dropItemNaturally(dropLoc, new ItemStack(Material.SUGAR_CANE));
            world.spawnParticle(Particle.SPLASH, dropLoc, 20, 0.5, 0.5, 0.5, 0.1);
            world.playSound(dropLoc, Sound.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.8F, 1.0F);
            return;
        }

        // Твёрдые ресурсы выпадают в центре
        if (state.coalCount > 0) {
            world.dropItemNaturally(dropLoc, new ItemStack(Material.COAL, state.coalCount));
        }
        if (state.caneCount > 0) {
            world.dropItemNaturally(dropLoc, new ItemStack(Material.SUGAR_CANE, state.caneCount));
        }
        // Жидкость (горячая вода) разливается и не выпадает
        if (state.hotWaterCount > 0) {
            world.spawnParticle(Particle.SPLASH, dropLoc, 10 * state.hotWaterCount, 0.4, 0.4, 0.4, 0.1);
            world.playSound(dropLoc, Sound.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.6F, 1.2F);
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
