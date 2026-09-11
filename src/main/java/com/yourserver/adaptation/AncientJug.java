package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Instrument;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Древний кувшин — особый предмет рыбалки в пустынных биомах
 * (шанс улова полностью повторяет заледеневшую изморозь).
 *
 * Кувшин ставится как блок (маркер тот же, что у медного нотного блока:
 * нота 24, но свои инструменты — по ним ресурспак выбирает модель кувшина).
 * В поставленный кувшин ПКМ выливаются зелья из бутылочек (до 9 штук,
 * только одного вида; бутылочка возвращается пустой). Сломанный кувшин
 * сохраняет содержимое, а пить из него можно прямо из руки (ПКМ по воздуху).
 * Пока в инвентаре лежит кувшин хотя бы с одной бутылочкой, игрок получает
 * Замедление IV без частиц.
 *
 * Содержимое поставленных кувшинов хранится в jugs.yml (как инвентари
 * медных блоков — в blocks.yml).
 */
public class AncientJug implements Listener {

    static final String TITLE = "Древний кувшин";
    /** Та же резервная нота, что у медного блока: модель выбирает ресурспак. */
    static final int MARKER_NOTE = 24;
    /** Пустой кувшин: нота 24 + флейта. */
    static final Instrument EMPTY_INSTRUMENT = Instrument.FLUTE;
    /** Кувшин с зельями (хотя бы 1 бутылочка): нота 24 + банджо. */
    static final Instrument FILLED_INSTRUMENT = Instrument.BANJO;
    static final int MAX_BOTTLES = 9;

    /** Биомы, в которых клюёт кувшин. */
    private static final Set<String> DESERT_BIOMES = Set.of("minecraft:desert");

    private final JavaPlugin plugin;
    private final NamespacedKey jugKey;
    private final NamespacedKey potionKey;
    private final NamespacedKey countKey;
    private final NamespacedKey castLuck;
    private final NamespacedKey rolled;
    private final File jugsFile;
    private final YamlConfiguration jugsData;
    private final BatchedYamlFile storage;

    public AncientJug(JavaPlugin plugin, AsyncTextWriter writer) {
        this.plugin = plugin;
        jugKey = new NamespacedKey(plugin, "ancient_jug");
        potionKey = new NamespacedKey(plugin, "jug_potion");
        countKey = new NamespacedKey(plugin, "jug_count");
        castLuck = new NamespacedKey(plugin, "jug_cast_luck");
        rolled = new NamespacedKey(plugin, "jug_catch_rolled");
        jugsFile = new File(plugin.getDataFolder(), "jugs.yml");
        jugsData = YamlConfiguration.loadConfiguration(jugsFile);
        storage = new BatchedYamlFile(plugin, writer, jugsFile.toPath(), () -> jugsData.saveToString());
        // Раз в секунду проверяем, не несёт ли игрок наполненный кувшин.
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyCarrySlowness, 20L, 20L);
    }

    // ===== ПРЕДМЕТ =====

    /** Создать кувшин: 0 бутылочек — пустой, иначе с зельем данного типа. */
    public ItemStack create(int bottles, PotionType type) {
        bottles = Math.clamp(bottles, 0, MAX_BOTTLES);
        if (bottles == 0) type = null;
        ItemStack item = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(TITLE, NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(ProfileItems.text("Особый предмет рыбалки в пустынных биомах.", NamedTextColor.GRAY));
        if (type != null) {
            lore.add(Component.translatable("item.minecraft.potion.effect." + type.name().toLowerCase(Locale.ROOT))
                    .color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(" ×" + bottles + "/" + MAX_BOTTLES, NamedTextColor.GRAY)));
        }
        meta.lore(lore);
        meta.setItemModel(new NamespacedKey("f8resurs", bottles > 0 ? "ancient_jug_filled" : "ancient_jug"));
        meta.getPersistentDataContainer().set(jugKey, PersistentDataType.BYTE, (byte) 1);
        if (type != null) {
            meta.getPersistentDataContainer().set(potionKey, PersistentDataType.STRING, type.name());
            meta.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, bottles);
        }
        item.setItemMeta(meta);
        return item;
    }

    public boolean isJug(ItemStack item) {
        return item != null && item.getType() == Material.NOTE_BLOCK && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(jugKey, PersistentDataType.BYTE);
    }

    public int bottles(ItemStack item) {
        if (!isJug(item)) return 0;
        Integer count = item.getItemMeta().getPersistentDataContainer().get(countKey, PersistentDataType.INTEGER);
        return count == null ? 0 : Math.clamp(count, 0, MAX_BOTTLES);
    }

    public PotionType potion(ItemStack item) {
        if (!isJug(item)) return null;
        return parseType(item.getItemMeta().getPersistentDataContainer().get(potionKey, PersistentDataType.STRING));
    }

    private static PotionType parseType(String name) {
        if (name == null || name.isEmpty()) return null;
        try { return PotionType.valueOf(name); } catch (IllegalArgumentException ex) { return null; }
    }

    // ===== РЫБАЛКА В ПУСТЫНЕ (шансы как у изморози) =====

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        var hook = event.getHook();
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            ItemStack rod = event.getHand() == EquipmentSlot.OFF_HAND ? event.getPlayer().getInventory().getItemInOffHand()
                    : event.getPlayer().getInventory().getItemInMainHand();
            if (event.getHand() == null && rod.getType() != Material.FISHING_ROD) rod = event.getPlayer().getInventory().getItemInOffHand();
            hook.getPersistentDataContainer().set(castLuck, PersistentDataType.INTEGER, rod.getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA));
            return;
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        if (hook.getPersistentDataContainer().has(rolled, PersistentDataType.BYTE)) return;
        hook.getPersistentDataContainer().set(rolled, PersistentDataType.BYTE, (byte) 1);
        String biome = hook.getLocation().getBlock().getBiome().getKey().toString();
        if (!DESERT_BIOMES.contains(biome)) return;
        int luck = hook.getPersistentDataContainer().getOrDefault(castLuck, PersistentDataType.INTEGER, 0);
        if (ThreadLocalRandom.current().nextDouble() < WinterRules.catchChance(luck)) caught.setItemStack(create(0, null));
    }

    // ===== ПОСТАВЛЕННЫЙ БЛОК =====

    private String blockKey(Block block) {
        return block.getWorld().getName() + "_" + block.getX() + "_" + block.getY() + "_" + block.getZ();
    }

    /** Кувшин = нота 24 + один из кувшинных инструментов + запись в jugs.yml. */
    private boolean isJugBlock(Block block) {
        if (block == null || block.getType() != Material.NOTE_BLOCK) return false;
        if (!(block.getBlockData() instanceof NoteBlock noteBlock)) return false;
        if (noteBlock.getNote().getId() != MARKER_NOTE) return false;
        Instrument instrument = noteBlock.getInstrument();
        return (instrument == EMPTY_INSTRUMENT || instrument == FILLED_INSTRUMENT)
                && jugsData.contains(blockKey(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (!isJug(hand)) return;
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.NOTE_BLOCK) return;
        int bottles = bottles(hand);
        PotionType type = potion(hand);

        NoteBlock data = (NoteBlock) block.getBlockData();
        data.setNote(new Note(MARKER_NOTE));
        data.setInstrument(bottles > 0 ? FILLED_INSTRUMENT : EMPTY_INSTRUMENT);
        data.setPowered(false);
        block.setBlockData(data, false);

        String key = blockKey(block);
        jugsData.set(key + ".potion", type == null ? "" : type.name());
        jugsData.set(key + ".count", bottles);
        storage.markDirty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isJugBlock(block)) return;
        String key = blockKey(block);
        int bottles = Math.clamp(jugsData.getInt(key + ".count", 0), 0, MAX_BOTTLES);
        PotionType type = parseType(jugsData.getString(key + ".potion", ""));
        jugsData.set(key, null);
        storage.markDirty();
        // Обычный нот-блок не выпадает: вместо него — кувшин с содержимым.
        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation(), create(bottles, type));
    }

    /** Кувшин нельзя двигать поршнем: содержимое привязано к координатам. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) if (isJugBlock(block)) { event.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) if (isJugBlock(block)) { event.setCancelled(true); return; }
    }

    /** Красный камень не играет ноты кувшина. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNotePlay(NotePlayEvent event) {
        if (isJugBlock(event.getBlock())) event.setCancelled(true);
    }

    // ===== ВЫЛИВАНИЕ ЗЕЛИЙ (ПКМ по кувшину с бутылочкой) =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (!isJugBlock(block)) return;
        Player player = event.getPlayer();
        if (ContainerInteraction.bypassMenu(player.isSneaking(),
                player.getInventory().getItemInMainHand().getType().isAir(),
                player.getInventory().getItemInOffHand().getType().isAir())) {
            // Шифт с предметом в руке — ванильный вторичный жест
            // (например, поставить блок о кувшин). Меню/выливание пропускаем.
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.POTION) return;
        pour(player, block, hand);
    }

    private void pour(Player player, Block block, ItemStack potionItem) {
        if (!(potionItem.getItemMeta() instanceof PotionMeta meta)) return;
        PotionType type = meta.getBasePotionType();
        if (type == null) return;

        String key = blockKey(block);
        int count = Math.clamp(jugsData.getInt(key + ".count", 0), 0, MAX_BOTTLES);
        String stored = jugsData.getString(key + ".potion", "");

        if (count >= MAX_BOTTLES) {
            player.sendActionBar("§cКувшин полон! Больше " + MAX_BOTTLES + " бутылочек не влить.");
            return;
        }
        if (count > 0 && !stored.equals(type.name())) {
            player.sendActionBar("§cКажется, в кувшине уже есть другая жидкость...");
            return;
        }

        jugsData.set(key + ".potion", type.name());
        jugsData.set(key + ".count", count + 1);
        storage.markDirty();

        if (count == 0 && block.getBlockData() instanceof NoteBlock data) {
            data.setInstrument(FILLED_INSTRUMENT);
            block.setBlockData(data, false);
        }

        // Бутылочка возвращается пустой.
        if (potionItem.getAmount() > 1) {
            potionItem.setAmount(potionItem.getAmount() - 1);
            player.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE)).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
        }
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 0.8f, 1.0f);
    }

    // ===== ПИТЬЁ ПРЯМО ИЗ ИНВЕНТАРЯ (ПКМ по воздуху с кувшином) =====

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrink(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!isJug(main)) return;
        int count = bottles(main);
        PotionType type = potion(main);
        if (count <= 0 || type == null) return;
        event.setCancelled(true);

        for (PotionEffect effect : type.getPotionEffects()) player.addPotionEffect(effect);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.0f);

        Component customName = main.getItemMeta().displayName();
        Component defaultName = ProfileItems.text(TITLE, NamedTextColor.GOLD);
        ItemStack updated = create(count - 1, count - 1 > 0 ? type : null);
        if (customName != null && !customName.equals(defaultName)) {
            ItemMeta updatedMeta = updated.getItemMeta();
            updatedMeta.displayName(customName);
            updated.setItemMeta(updatedMeta);
        }
        if (main.getAmount() > 1) {
            // В стеке несколько кувшинов: выпиваем один, остальные не затираем.
            main.setAmount(main.getAmount() - 1);
            player.getInventory().addItem(updated).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        } else {
            player.getInventory().setItemInMainHand(updated);
        }
    }

    // ===== ЗАМЕДЛЕНИЕ ОТ НАПОЛНЕННОГО КУВШИНА В ИНВЕНТАРЕ =====

    private void applyCarrySlowness() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!carriesFilledJug(player)) continue;
            // Замедление IV, без частиц; обновляется каждую секунду.
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 3, true, false));
        }
    }

    private boolean carriesFilledJug(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isJug(item) && bottles(item) > 0) return true;
        }
        return false;
    }

    void disable() {
        storage.flushBlocking();
    }
}
