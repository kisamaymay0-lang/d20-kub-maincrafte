package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Instrument;
import org.bukkit.Location;
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
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Древний кувшин — особый предмет рыбалки в пустынных биомах
 * (шанс улова полностью повторяет заледеневшую изморозь).
 *
 * Кувшин ставится как блок: тот же маркер, что у медного нотного блока
 * (нота 24), но свои инструменты — пустой кувшин это нота 24 + флейта,
 * а наполненный — ноты 1..9 (номер ноты = количество жидкости, поэтому
 * компаратор выдаёт ровно столько сигнала, сколько жидкости внутри)
 * + банджо. По этим парам ресурспак выбирает модель.
 *
 * В поставленный кувшин ПКМ выливаются жидкости: обычные зелья, бутылочки
 * мёда и любые предметы, помеченные другими плагинами как жидкость
 * (любой PDC-ключ с именем "liquid"). Всего до 9 бутылочек одного вида,
 * пустая тара возвращается. Сломанный кувшин сохраняет содержимое
 * (хранится в jugs.yml), пить из него можно прямо из руки (ПКМ по воздуху,
 * пьётся 1.6 секунды, как обычное зелье). Кувшин можно надеть как
 * нагрудник — тогда он не замедляет. Пока кувшин хотя бы с одной
 * бутылочкой лежит в инвентаре (не надет), игрок получает Замедление IV
 * без частиц, прыгает ниже, а на элитрах теряет их прочность в 4 раза
 * быстрее и медленно тянется вниз.
 */
public class AncientJug implements Listener {

    static final String TITLE = "Древний кувшин";
    /** Та же резервная нота, что у медного блока: модель выбирает ресурспак. */
    static final int MARKER_NOTE = 24;
    /** Пустой кувшин: нота 24 + флейта. */
    static final Instrument EMPTY_INSTRUMENT = Instrument.FLUTE;
    /** Кувшин с жидкостью: нота 1..9 (= количество) + банджо. */
    static final Instrument FILLED_INSTRUMENT = Instrument.BANJO;
    static final int MAX_BOTTLES = 9;
    static final String POTION_KIND = "POTION";
    static final String HONEY_KIND = "HONEY";
    static final String CUSTOM_KIND = "CUSTOM";

    /** Биомы, в которых клюёт кувшин. */
    private static final Set<String> DESERT_BIOMES = Set.of("minecraft:desert");
    /** Длительность питья, как у ванильных зелий. */
    private static final int DRINK_TICKS = 32;

    private final JavaPlugin plugin;
    private final NamespacedKey jugKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey potionKey;
    private final NamespacedKey countKey;
    private final NamespacedKey customKey;
    private final NamespacedKey castLuck;
    private final NamespacedKey rolled;
    private final File jugsFile;
    private final YamlConfiguration jugsData;
    private final BatchedYamlFile storage;
    /** Ключи блоков, которым на следующий тик вернут нату/инструмент кувшина. */
    private final Set<String> pendingRestore = new HashSet<>();
    /** Кто сейчас пьёт из кувшина. */
    private final Map<UUID, DrinkSession> drinking = new HashMap<>();

    public AncientJug(JavaPlugin plugin, AsyncTextWriter writer) {
        this.plugin = plugin;
        jugKey = new NamespacedKey(plugin, "ancient_jug");
        kindKey = new NamespacedKey(plugin, "jug_kind");
        potionKey = new NamespacedKey(plugin, "jug_potion");
        countKey = new NamespacedKey(plugin, "jug_count");
        customKey = new NamespacedKey(plugin, "jug_custom");
        castLuck = new NamespacedKey(plugin, "jug_cast_luck");
        rolled = new NamespacedKey(plugin, "jug_catch_rolled");
        jugsFile = new File(plugin.getDataFolder(), "jugs.yml");
        jugsData = YamlConfiguration.loadConfiguration(jugsFile);
        storage = new BatchedYamlFile(plugin, writer, jugsFile.toPath(), () -> jugsData.saveToString());
        // Раз в секунду — замедление/пониженный прыжок/износ элитр, каждый тик — утягивание вниз на элитрах.
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyCarryEffects, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyFlightPull, 1L, 1L);
    }

    // ===== СОДЕРЖИМОЕ =====

    /** Что налито в кувшине: вид жидкости + количество бутылочек. */
    static final class Contents {
        final String kind;
        final String potion;
        final byte[] custom;
        final int count;

        Contents(String kind, String potion, byte[] custom, int count) {
            this.kind = kind;
            this.potion = potion;
            this.custom = custom;
            this.count = Math.clamp(count, 0, MAX_BOTTLES);
        }

        /** Идентичность жидкости для проверки «в кувшине уже другая жидкость». */
        String identity() {
            if (count <= 0) return "";
            if (HONEY_KIND.equals(kind)) return "HONEY";
            if (CUSTOM_KIND.equals(kind)) return "CUSTOM:" + (custom == null ? "" : Base64.getEncoder().encodeToString(custom));
            return "POTION:" + (potion == null ? "" : potion);
        }
    }

    // ===== ПРЕДМЕТ =====

    public ItemStack createEmpty() {
        return create(0, null, null, null);
    }

    /** Создать кувшин: 0 бутылочек — пустой, иначе с жидкостью данного вида. */
    public ItemStack create(int bottles, String kind, String potion, byte[] custom) {
        bottles = Math.clamp(bottles, 0, MAX_BOTTLES);
        if (bottles == 0) {
            kind = null;
            potion = null;
            custom = null;
        }
        ItemStack item = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(TITLE, NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(ProfileItems.text("Особый предмет рыбалки в пустынных биомах.", NamedTextColor.GRAY));
        if (bottles > 0 && kind != null) {
            lore.add(liquidName(kind, potion, custom)
                    .append(Component.text(" ×" + bottles + "/" + MAX_BOTTLES, NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind);
            if (POTION_KIND.equals(kind) && potion != null) {
                meta.getPersistentDataContainer().set(potionKey, PersistentDataType.STRING, potion);
            }
            if (CUSTOM_KIND.equals(kind) && custom != null) {
                meta.getPersistentDataContainer().set(customKey, PersistentDataType.BYTE_ARRAY, custom);
            }
            meta.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, bottles);
        }
        meta.lore(lore);
        meta.setItemModel(new NamespacedKey("f8resurs", bottles > 0 ? "ancient_jug_filled" : "ancient_jug"));
        meta.getPersistentDataContainer().set(jugKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        try {
            // Кувшин можно надеть как нагрудник — надетый кувшин не замедляет.
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.CHEST).build());
        } catch (Throwable ignored) {
            // Старое ядро без компонента: предмет остаётся рабочим, просто не надевается.
        }
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

    Contents contentsOf(ItemStack item) {
        if (!isJug(item)) return new Contents(null, null, null, 0);
        var pdc = item.getItemMeta().getPersistentDataContainer();
        Integer count = pdc.get(countKey, PersistentDataType.INTEGER);
        int bottles = count == null ? 0 : Math.clamp(count, 0, MAX_BOTTLES);
        if (bottles == 0) return new Contents(null, null, null, 0);
        return new Contents(
                pdc.get(kindKey, PersistentDataType.STRING),
                pdc.get(potionKey, PersistentDataType.STRING),
                pdc.get(customKey, PersistentDataType.BYTE_ARRAY),
                bottles
        );
    }

    private Component liquidName(String kind, String potion, byte[] custom) {
        Component name;
        if (HONEY_KIND.equals(kind)) {
            name = Component.translatable("item.minecraft.honey_bottle");
        } else if (CUSTOM_KIND.equals(kind)) {
            name = customLiquidName(custom);
        } else {
            PotionType type = parseType(potion);
            name = type != null
                    ? Component.translatable("item.minecraft.potion.effect." + type.name().toLowerCase(Locale.ROOT))
                    : Component.translatable("item.minecraft.potion.effect.water");
        }
        return name.color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false);
    }

    private Component customLiquidName(byte[] custom) {
        try {
            if (custom != null) {
                ItemStack stored = ItemStack.deserializeBytes(custom);
                ItemMeta meta = stored.hasItemMeta() ? stored.getItemMeta() : null;
                Component displayName = meta == null ? null : meta.displayName();
                if (displayName != null) return displayName;
            }
        } catch (Throwable ignored) {
        }
        return Component.text("Неизвестная жидкость");
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
        if (ThreadLocalRandom.current().nextDouble() < WinterRules.catchChance(luck)) caught.setItemStack(createEmpty());
    }

    // ===== ПОСТАВЛЕННЫЙ БЛОК =====

    private String blockKey(Block block) {
        return block.getWorld().getName() + "_" + block.getX() + "_" + block.getY() + "_" + block.getZ();
    }

    private Contents readContents(String key) {
        int count = Math.clamp(jugsData.getInt(key + ".count", 0), 0, MAX_BOTTLES);
        if (count <= 0) return new Contents(null, null, null, 0);
        String kind = jugsData.getString(key + ".kind", "");
        String potion = jugsData.getString(key + ".potion", "");
        byte[] custom = null;
        String encoded = jugsData.getString(key + ".custom", "");
        if (!encoded.isEmpty()) {
            try { custom = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException ignored) { }
        }
        if (kind.isEmpty()) kind = !potion.isEmpty() ? POTION_KIND : HONEY_KIND;
        return new Contents(kind, potion, custom, count);
    }

    private void writeContents(String key, Contents contents) {
        if (contents.count <= 0) {
            jugsData.set(key, null);
            return;
        }
        jugsData.set(key + ".kind", contents.kind);
        jugsData.set(key + ".potion", POTION_KIND.equals(contents.kind) && contents.potion != null ? contents.potion : "");
        jugsData.set(key + ".custom", CUSTOM_KIND.equals(contents.kind) && contents.custom != null
                ? Base64.getEncoder().encodeToString(contents.custom) : "");
        jugsData.set(key + ".count", contents.count);
    }

    /** Кувшин = (нота 24 + флейта) либо (нота 1..9 + банджо) + запись в jugs.yml. */
    private boolean isJugBlock(Block block) {
        if (block == null || block.getType() != Material.NOTE_BLOCK) return false;
        if (!(block.getBlockData() instanceof NoteBlock noteBlock)) return false;
        int note = noteBlock.getNote().getId();
        Instrument instrument = noteBlock.getInstrument();
        boolean jugState = (note == MARKER_NOTE && instrument == EMPTY_INSTRUMENT)
                || (instrument == FILLED_INSTRUMENT && note >= 1 && note <= MAX_BOTTLES);
        return jugState && jugsData.contains(blockKey(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (!isJug(hand)) return;
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.NOTE_BLOCK) return;
        Contents contents = contentsOf(hand);

        NoteBlock data = (NoteBlock) block.getBlockData();
        data.setNote(new Note(contents.count > 0 ? contents.count : MARKER_NOTE));
        data.setInstrument(contents.count > 0 ? FILLED_INSTRUMENT : EMPTY_INSTRUMENT);
        data.setPowered(false);
        block.setBlockData(data, false);

        writeContents(blockKey(block), contents);
        storage.markDirty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isJugBlock(block)) return;
        String key = blockKey(block);
        Contents contents = readContents(key);
        jugsData.set(key, null);
        storage.markDirty();
        // Обычный нот-блок не выпадает: вместо него — кувшин с содержимым.
        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation(),
                create(contents.count, contents.kind, contents.potion, contents.custom));
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

    /** Взрыв уничтожает кувшин с выпадением содержимого, запись не должна оставаться. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explodeCleanup(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explodeCleanup(event.blockList());
    }

    private void explodeCleanup(List<Block> blocks) {
        boolean changed = false;
        for (Block block : blocks) {
            if (!isJugBlock(block)) continue;
            String key = blockKey(block);
            Contents contents = readContents(key);
            block.getWorld().dropItemNaturally(block.getLocation(),
                    create(contents.count, contents.kind, contents.potion, contents.custom));
            jugsData.set(key, null);
            changed = true;
        }
        if (changed) storage.markDirty();
    }

    // ===== ЗАЩИТА БЛОКСТЕЙТА КУВШИНА =====
    // Нот-блок пересчитывает инструмент от соседних блоков; ваниль может
    // «переписать» ноту/инструмент кувшина. Любое такое изменение
    // откатывается на следующий тик к сохранённому состоянию кувшина.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        scheduleRestore(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNeighborPlace(BlockPlaceEvent event) {
        scheduleRestoreAround(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNeighborBreak(BlockBreakEvent event) {
        scheduleRestoreAround(event.getBlock());
    }

    private void scheduleRestoreAround(Block changed) {
        scheduleRestore(changed.getRelative(0, 1, 0));
        scheduleRestore(changed.getRelative(0, -1, 0));
        scheduleRestore(changed.getRelative(1, 0, 0));
        scheduleRestore(changed.getRelative(-1, 0, 0));
        scheduleRestore(changed.getRelative(0, 0, 1));
        scheduleRestore(changed.getRelative(0, 0, -1));
    }

    private void scheduleRestore(Block block) {
        if (block == null || block.getType() != Material.NOTE_BLOCK) return;
        String key = blockKey(block);
        if (!jugsData.contains(key)) return;
        if (!pendingRestore.add(key)) return;
        Location location = block.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingRestore.remove(key);
            restoreIfDrifted(location, key);
        });
    }

    private void restoreIfDrifted(Location location, String key) {
        if (!jugsData.contains(key)) return;
        Block block = location.getBlock();
        if (block.getType() != Material.NOTE_BLOCK || !(block.getBlockData() instanceof NoteBlock data)) return;
        Contents contents = readContents(key);
        int note = contents.count > 0 ? contents.count : MARKER_NOTE;
        Instrument instrument = contents.count > 0 ? FILLED_INSTRUMENT : EMPTY_INSTRUMENT;
        if (data.getNote().getId() == note && data.getInstrument() == instrument) return;
        data.setNote(new Note(note));
        data.setInstrument(instrument);
        block.setBlockData(data, false);
    }

    // ===== ВЫЛИВАНИЕ ЖИДКОСТЕЙ (ПКМ по кувшину) =====

    /** Жидкость для выливания: зелье, мёд или предмет с любым PDC-ключом "liquid". */
    private static final class Liquid {
        final String kind;
        final String potion;
        final byte[] custom;
        final boolean returnsBottle;

        Liquid(String kind, String potion, byte[] custom, boolean returnsBottle) {
            this.kind = kind;
            this.potion = potion;
            this.custom = custom;
            this.returnsBottle = returnsBottle;
        }

        String identity() {
            if (HONEY_KIND.equals(kind)) return "HONEY";
            if (CUSTOM_KIND.equals(kind)) return "CUSTOM:" + (custom == null ? "" : Base64.getEncoder().encodeToString(custom));
            return "POTION:" + (potion == null ? "" : potion);
        }
    }

    private Liquid classifyLiquid(ItemStack item) {
        if (item.getType() == Material.POTION && item.getItemMeta() instanceof PotionMeta meta) {
            PotionType type = meta.getBasePotionType();
            if (type == null) return null;
            return new Liquid(POTION_KIND, type.name(), null, true);
        }
        if (item.getType() == Material.HONEY_BOTTLE) return new Liquid(HONEY_KIND, null, null, true);
        if (item.hasItemMeta()) {
            // Универсальный маркер для жидкостей из других плагинов: любой
            // неймспейс, имя ключа "liquid".
            boolean marked = item.getItemMeta().getPersistentDataContainer().getKeys().stream()
                    .anyMatch(key -> key.getKey().equals("liquid"));
            if (marked) {
                ItemStack one = item.clone();
                one.setAmount(1);
                return new Liquid(CUSTOM_KIND, null, one.serializeAsBytes(), false);
            }
        }
        return null;
    }

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
            // (например, поставить блок о кувшин). Выливание пропускаем.
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        Liquid liquid = classifyLiquid(hand);
        if (liquid == null) return;
        pour(player, block, hand, liquid);
    }

    private void pour(Player player, Block block, ItemStack source, Liquid liquid) {
        String key = blockKey(block);
        Contents stored = readContents(key);

        if (stored.count >= MAX_BOTTLES) {
            player.sendActionBar("§cКувшин полон! Больше " + MAX_BOTTLES + " бутылочек не влить.");
            return;
        }
        if (stored.count > 0 && !stored.identity().equals(liquid.identity())) {
            player.sendActionBar("§cКажется, в кувшине уже есть другая жидкость...");
            return;
        }

        int newCount = stored.count + 1;
        writeContents(key, new Contents(liquid.kind, liquid.potion, liquid.custom, newCount));
        storage.markDirty();

        if (block.getBlockData() instanceof NoteBlock data) {
            data.setInstrument(FILLED_INSTRUMENT);
            data.setNote(new Note(newCount));
            block.setBlockData(data, false);
        }

        if (liquid.returnsBottle) {
            // Бутылочка возвращается пустой.
            if (source.getAmount() > 1) {
                source.setAmount(source.getAmount() - 1);
                player.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE)).values()
                        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            } else {
                player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
            }
        } else if (source.getAmount() > 1) {
            source.setAmount(source.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 0.8f, 1.0f);
    }

    // ===== ПИТЬЁ ПРЯМО ИЗ ИНВЕНТАРЯ (ПКМ по воздуху с кувшином) =====

    private static final class DrinkSession {
        final ItemStack snapshot;
        BukkitTask task;
        int ticks;

        DrinkSession(ItemStack snapshot) {
            this.snapshot = snapshot;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrink(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!isJug(main) || bottles(main) <= 0) return;
        if (drinking.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        DrinkSession session = new DrinkSession(main.clone());
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> drinkTick(player, session), 4L, 4L);
        drinking.put(player.getUniqueId(), session);
    }

    private void drinkTick(Player player, DrinkSession session) {
        UUID id = player.getUniqueId();
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!player.isOnline() || player.isDead() || !main.isSimilar(session.snapshot)) {
            stopDrinking(id);
            return;
        }
        session.ticks += 4;
        player.playSound(player.getEyeLocation(), Sound.ENTITY_GENERIC_DRINK, 0.5f,
                0.9f + ThreadLocalRandom.current().nextFloat() * 0.3f);
        if (session.ticks >= DRINK_TICKS) {
            stopDrinking(id);
            finishDrink(player, session.snapshot);
        }
    }

    private void stopDrinking(UUID id) {
        DrinkSession session = drinking.remove(id);
        if (session != null && session.task != null) session.task.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopDrinking(event.getPlayer().getUniqueId());
    }

    private void finishDrink(Player player, ItemStack snapshot) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!main.isSimilar(snapshot)) return;
        Contents contents = contentsOf(main);
        if (contents.count <= 0) return;

        applyDrinkEffects(player, contents);
        player.playSound(player.getEyeLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.0f);

        Component customName = main.getItemMeta().displayName();
        Component defaultName = ProfileItems.text(TITLE, NamedTextColor.GOLD);
        ItemStack updated = create(contents.count - 1, contents.kind, contents.potion, contents.custom);
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

    private void applyDrinkEffects(Player player, Contents contents) {
        if (POTION_KIND.equals(contents.kind)) {
            PotionType type = parseType(contents.potion);
            if (type != null) for (PotionEffect effect : type.getPotionEffects()) player.addPotionEffect(effect);
        } else if (HONEY_KIND.equals(contents.kind)) {
            // Мёд: лечит отравление и восстанавливает голод, как бутылочка мёда.
            player.removePotionEffect(PotionEffectType.POISON);
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + 6));
            player.setSaturation(Math.min((float) player.getFoodLevel(), player.getSaturation() + 1.2f));
        }
        // CUSTOM: эффекты сторонней жидкости неизвестны — просто выпивается.
    }

    // ===== ЭФФЕКТЫ ПЕРЕНОСКИ НАПОЛНЕННОГО КУВШИНА =====

    /** Наполненный кувшин в хранилище/оффхенде; надетый как нагрудник не считается. */
    private boolean carriesFilledJug(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isJug(item) && bottles(item) > 0) return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return isJug(offhand) && bottles(offhand) > 0;
    }

    private void applyCarryEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!carriesFilledJug(player)) continue;
            // Замедление IV без частиц.
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 3, true, false));
            // Пониженный прыжок (примерно в полублок): отрицательный усилитель.
            try {
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 30, -1, true, false));
            } catch (Throwable ignored) {
                // Ядро не приняло отрицательный усилитель — остаётся только замедление.
            }
            if (player.isGliding()) damageElytra(player);
        }
    }

    /** Элитры с кувшином изнашиваются в 4 раза быстрее: ванильное 1/сек + наши 3/сек. */
    private void damageElytra(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) return;
        if (!(chest.getItemMeta() instanceof Damageable meta)) return;
        int damage = meta.getDamage() + 3;
        if (damage >= chest.getType().getMaxDurability()) {
            player.getInventory().setChestplate(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);
        } else {
            meta.setDamage(damage);
            chest.setItemMeta(meta);
        }
    }

    /** Вес кувшина медленно утягивает игрока вниз во время полёта на элитрах. */
    private void applyFlightPull() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isGliding() || !carriesFilledJug(player)) continue;
            Vector velocity = player.getVelocity();
            if (velocity.getY() > -1.35) player.setVelocity(velocity.subtract(new Vector(0, 0.02, 0)));
        }
    }

    void disable() {
        for (UUID id : new ArrayList<>(drinking.keySet())) stopDrinking(id);
        storage.flushBlocking();
    }
}
