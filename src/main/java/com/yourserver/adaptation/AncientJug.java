package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Slab;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
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
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Древний кувшин — особый предмет рыбалки в пустынных биомах
 * (шанс улова полностью повторяет заледеневшую изморозь).
 *
 * Кувшин ставится как блок: предмет — обычный нот-блок (его можно поставить
 * на любую грань, хоть в воздухе), но на месте установки нот-блок сразу
 * заменяется на плиту-носитель — техническую плиту петрифайд-дуба.
 * Плита не считается сплошным блоком, поэтому соседние блоки больше не
 * «пропадают» рядом с кувшином: щелей нет ни в полу, ни в стенах, ни в
 * потолке. Модель выбирается состоянием плиты: пустой кувшин — нижняя
 * плита, налитый — верхняя. Двойной плиту держать нельзя (она сплошная),
 * поэтому состояние каждый тик возвращается каноническим.
 * Кувшины из версии 10.10 и раньше стояли нот-блоками (нота 24 + флейта /
 * ноты 1..9 + банджо) — при первой же проверке такие блоки переносятся
 * на плиту, так что старые постройки не пропадают.
 *
 * В поставленный кувшин ПКМ выливаются жидкости: обычные зелья, бутылочки
 * мёда и любые предметы, помеченные другими плагинами как жидкость
 * (любой PDC-ключ с именем "liquid"). Всего до 9 бутылочек одного вида,
 * пустая тара возвращается. Сломанный кувшин сохраняет содержимое
 * (хранится в jugs.yml), пить из него можно прямо из руки (ПКМ по воздуху,
 * пьётся 1.6 секунды, как обычное зелье). Кувшин можно надеть как
 * нагрудник — тогда он не замедляет (надеть можно только вручную, положив
 * кувшин в слот). Пока кувшин хотя бы с одной
 * бутылочкой лежит в инвентаре (не надет), игрок получает Замедление II
 * без частиц, прыгает примерно в половину блока, а на элитрах теряет их
 * прочность в 4 раза быстрее и медленно тянется вниз.
 *
 * Вода и молоко наливаются и забираются вёдрами: одно ведро — три порции
 * («слота»). Молоко из кувшина пьют только при трёх и более порциях.
 *
 * Питьё устроено как у ванильных зелий: ПКМ надо держать, игрок подносит
 * кувшин ко рту, звучат глотки — за это отвечает компонент consumable.
 * Выливание — это шифт + ПКМ в любом месте: порция уходит на землю, всегда
 * с сообщением «Вы вылили жидкость из кувшина...». Если вылили на растение,
 * оно попутно пропитывается (зельем с эффектами или молоком): урожай с него
 * даёт эффект зелья на 4 секунды, а молочный урожай срезает по 8 секунд
 * со всех активных эффектов.
 */
public class AncientJug implements Listener {

    static final String TITLE = "Древний кувшин";
    /** Та же резервная нота, что у медного блока: нужна только для старых кувшинов. */
    static final int MARKER_NOTE = 24;
    /** Кувшин до 10.11 был нот-блоком: пустой — нота 24 + флейта. */
    static final Instrument EMPTY_INSTRUMENT = Instrument.FLUTE;
    /** Кувшин до 10.11 с жидкостью: нота 1..9 (= количество) + банджо. */
    static final Instrument FILLED_INSTRUMENT = Instrument.BANJO;
    /**
     * Блок-носитель кувшина: техническая плита петрифайд-дуба. Её нельзя
     * получить в выживании (только командой), а главное — она не сплошная,
     * поэтому не скрывает грани соседних блоков: щелей вокруг кувшина нет.
     */
    private static final Material JUG_BLOCK = Material.PETRIFIED_OAK_SLAB;
    /** Пустой кувшин — нижняя плита. */
    private static final Slab.Type EMPTY_SLAB = Slab.Type.BOTTOM;
    /** Налитый кувшин — верхняя плита: пара состояний выбирает модель в паке. */
    private static final Slab.Type FILLED_SLAB = Slab.Type.TOP;
    static final int MAX_BOTTLES = 9;
    static final String POTION_KIND = "POTION";
    static final String HONEY_KIND = "HONEY";
    static final String CUSTOM_KIND = "CUSTOM";
    /** Молоко: наливается и забирается ведром, порция — 3 слота. */
    static final String MILK_KIND = "MILK";
    /** Вода: наливается и забирается ведром, порция — 3 слота. */
    static final String WATER_KIND = "WATER";
    /**
     * Метка «растение пропитано молоком» в записи о пропитке. Имена зелий пишутся
     * без решётки, так что метка не может совпасть с настоящим зельем.
     */
    static final String MILK_INFUSION = "#milk";

    /** Сколько секунд держать ПКМ, чтобы выпить одну порцию из кувшина. */
    private static final float DRINK_SECONDS = 1.6f;
    /** Звук питья: как у ванильных бутылочек и зелий. */
    private static final Key DRINK_SOUND = Key.key("entity.generic.drink");
    /** Как часто (в тиках) реестр кувшинов пересобирается целиком. */
    private static final int PIN_RESCAN_TICKS = 200;
    /** Сколько длится эффект зелья со съеденного пропитанного урожая. */
    private static final int INFUSED_EFFECT_TICKS = 20 * 4;
    /** На сколько молочная пропитка срезает таймеры всех активных эффектов. */
    private static final int MILK_CUT_TICKS = 20 * 8;
    /** Сколько «слотов жидкости» наливает/забирает один предмет: бутылочка — 1, ведро — 3. */
    private static final int BUCKET_SLOTS = 3;
    /**
     * Сила прыжка под весом кувшина: 0.27 даёт подъём примерно 0.58 блока
     * (ванильные 0.42 — это 1.25 блока). Плиту и ступеньку игрок ещё берёт,
     * на полный блок уже не забирается.
     */
    private static final double WEIGHTED_JUMP_STRENGTH = 0.27D;
    /** Ванильная сила прыжка игрока: возвращаем её, когда вес снят. */
    private static final double VANILLA_JUMP_STRENGTH = 0.42D;
    /** Как часто (в тиках) проверяем, что пропитанные растения ещё стоят. */
    private static final int INFUSION_SWEEP_TICKS = 100;

    private final JavaPlugin plugin;
    private final NamespacedKey jugKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey potionKey;
    private final NamespacedKey countKey;
    private final NamespacedKey customKey;
    private final NamespacedKey infuseKey;
    private final File jugsFile;
    private final YamlConfiguration jugsData;
    private final BatchedYamlFile storage;
    /** Активные кувшины: ключ записи → блок и его каноническое состояние. */
    private final Map<String, JugPin> pins = new HashMap<>();
    private int guardTicks = PIN_RESCAN_TICKS;
    private int infusionTicks = INFUSION_SWEEP_TICKS;
    /** Пропитанные зельем растения: ключ блока → зелье (живёт в jugs.yml). */
    private final Map<String, String> infusions = new HashMap<>();

    public AncientJug(JavaPlugin plugin, AsyncTextWriter writer) {
        this.plugin = plugin;
        jugKey = new NamespacedKey(plugin, "ancient_jug");
        kindKey = new NamespacedKey(plugin, "jug_kind");
        potionKey = new NamespacedKey(plugin, "jug_potion");
        countKey = new NamespacedKey(plugin, "jug_count");
        customKey = new NamespacedKey(plugin, "jug_custom");
        infuseKey = new NamespacedKey(plugin, "potion_infused");
        jugsFile = new File(plugin.getDataFolder(), "jugs.yml");
        jugsData = YamlConfiguration.loadConfiguration(jugsFile);
        storage = new BatchedYamlFile(plugin, writer, jugsFile.toPath(), () -> jugsData.saveToString());
        loadInfusions();
        // Раз в секунду — замедление/пониженный прыжок/износ элитр, каждый тик — утягивание вниз на элитрах.
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyCarryEffects, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyFlightPull, 1L, 1L);
        // Каждый тик: состояние плиты-носителя должно быть строго каноническим
        // (нижняя плита у пустого, верхняя у налитого) — по нему ресурспак
        // выбирает модель, а двойная плита вернула бы щели. Здесь же старые
        // кувшины-нот-блоки переносятся на плиту.
        Bukkit.getScheduler().runTaskTimer(plugin, this::guardTick, 1L, 1L);
        // Через пару секунд после запуска приводим в порядок кувшины в тех
        // чанках, что уже загружены (остальные проверятся при загрузке чанка).
        Bukkit.getScheduler().runTaskLater(plugin, this::restoreLoadedJugs, 60L);
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
            if (MILK_KIND.equals(kind)) return "MILK";
            if (WATER_KIND.equals(kind)) return "WATER";
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
            // Кувшин надевается на голову, но только вручную, перетаскиванием в слот
            // шлема: swappable = false убирает ванильное «надевание по ПКМ», которое
            // перехватывало жест выливания и питья. Без asset_id ваниль рисует на
            // голове сам предмет, поэтому кувшин видно как настоящую шапку.
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD)
                    .swappable(false)
                    .dispensable(false)
                    .build());
        } catch (Throwable ignored) {
            // Старое ядро без компонента: предмет остаётся рабочим, просто не надевается.
        }
        applyConsumable(item, bottles, kind);
        return item;
    }

    /**
     * Делает кувшин «питьевым»: ПКМ надо держать, как с зельем, игрок подносит
     * кувшин ко рту, звучат глотки. Молоко из неполной порции не пьётся, поэтому
     * компонент ставим только когда порций хватает на глоток-ведро.
     */
    private void applyConsumable(ItemStack item, int bottles, String kind) {
        if (bottles <= 0) return;
        if (MILK_KIND.equals(kind) && bottles < BUCKET_SLOTS) return;
        try {
            item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                    .consumeSeconds(DRINK_SECONDS)
                    .animation(ItemUseAnimation.DRINK)
                    .sound(DRINK_SOUND)
                    .hasConsumeParticles(false)
                    .build());
        } catch (Throwable ignored) {
            // Старое ядро без компонента: остаётся прежнее разовое питьё по клику.
        }
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
        // Имя берётся из ванильного отображения предмета-образца,
        // чтобы клиент рисовал его как обычное зелье/мёд, без сырых ключей.
        ItemStack sample;
        if (HONEY_KIND.equals(kind)) {
            sample = new ItemStack(Material.HONEY_BOTTLE);
        } else if (MILK_KIND.equals(kind)) {
            sample = new ItemStack(Material.MILK_BUCKET);
        } else if (WATER_KIND.equals(kind)) {
            sample = new ItemStack(Material.POTION);
            if (sample.getItemMeta() instanceof PotionMeta meta) {
                meta.setBasePotionType(PotionType.WATER);
                sample.setItemMeta(meta);
            }
        } else if (CUSTOM_KIND.equals(kind)) {
            sample = null;
            try {
                if (custom != null) sample = ItemStack.deserializeBytes(custom);
            } catch (Throwable ignored) {
            }
            if (sample == null) return Component.text("Неизвестная жидкость");
        } else {
            PotionType type = parseType(potion);
            sample = new ItemStack(Material.POTION);
            if (type != null && sample.getItemMeta() instanceof PotionMeta meta) {
                meta.setBasePotionType(type);
                sample.setItemMeta(meta);
            }
        }
        return sample.displayName().color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false);
    }

    private static PotionType parseType(String name) {
        if (name == null || name.isEmpty()) return null;
        try { return PotionType.valueOf(name); } catch (IllegalArgumentException ex) { return null; }
    }

    // ===== ПОСТАВЛЕННЫЙ БЛОК =====

    private String blockKey(World world, int x, int y, int z) {
        return world.getName() + "_" + x + "_" + y + "_" + z;
    }

    private String blockKey(Block block) {
        return blockKey(block.getWorld(), block.getX(), block.getY(), block.getZ());
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
        // Пустой кувшин тоже запоминается: раньше запись удалялась (count = 0),
        // и поставленный пустой кувшин считался обычным блоком — в него нельзя
        // было налить, он вёл себя как чужой блок и выпадал не кувшином.
        jugsData.set(key + ".count", contents.count);
        jugsData.set(key + ".kind", contents.kind == null ? "" : contents.kind);
        jugsData.set(key + ".potion", POTION_KIND.equals(contents.kind) && contents.potion != null ? contents.potion : "");
        jugsData.set(key + ".custom", CUSTOM_KIND.equals(contents.kind) && contents.custom != null
                ? Base64.getEncoder().encodeToString(contents.custom) : "");
    }

    /** Координаты из ключа вида «мир_x_y_z» (имя мира может содержать «_»). */
    private Location locationOf(String key) {
        int last = key.lastIndexOf('_');
        int second = key.lastIndexOf('_', last - 1);
        int third = key.lastIndexOf('_', second - 1);
        if (third <= 0) return null;
        try {
            int x = Integer.parseInt(key.substring(third + 1, second));
            int y = Integer.parseInt(key.substring(second + 1, last));
            int z = Integer.parseInt(key.substring(last + 1));
            World world = Bukkit.getWorld(key.substring(0, third));
            return world == null ? null : new Location(world, x, y, z);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    // ===== СОСТОЯНИЕ БЛОКА: ЗАЩИТА ОТ СБОЯ =====
    // Ресурспак выбирает модель по состоянию плиты-носителя, поэтому у кувшина
    // оно обязано совпадать с содержимым: пустой — нижняя плита, с жидкостью —
    // верхняя. Двойная плита сплошная (вернула бы щели), а игрок может сбить
    // состояние отладкой, поэтому держим его сами: сразу в событии и
    // контрольным проходом каждый тик. Здесь же — перенос старых кувшинов
    // (нот-блоков) на плиту.

    /** Блок кувшина и его каноническое состояние плиты. */
    private final class JugPin {
        private final Location location;
        private final Slab.Type type;

        JugPin(Location location, Slab.Type type) {
            this.location = location.clone();
            this.type = type;
        }

        /** Возвращает блоку каноническое состояние; в незагруженном чанке молчит. */
        void enforce() {
            World world = location.getWorld();
            if (world == null) return;
            if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
            Block block = world.getBlockAt(location);
            if (block.getType() == JUG_BLOCK) {
                if (!(block.getBlockData() instanceof Slab data)) return;
                if (data.getType() == type) return;
                data.setType(type);
                block.setBlockData(data, false);
                return;
            }
            // Кувшин версии 10.10 и раньше стоял нот-блоком: переносим на плиту.
            if (isOldJugState(block)) {
                setJugBlock(block, type);
                plugin.getLogger().info("Старый кувшин (" + world.getName() + " "
                        + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                        + ") перенесён на плиту-носитель 10.11");
            }
        }
    }

    /**
     * Кувшин версии 10.10 и раньше: нот-блок с канонической парой
     * «нота + инструмент». Такие блоки переносятся на новый носитель,
     * чтобы старые кувшины не остались обычными нот-блоками.
     */
    private static boolean isOldJugState(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) return false;
        if (!(block.getBlockData() instanceof NoteBlock data)) return false;
        int note = data.getNote().getId();
        if (data.getInstrument() == EMPTY_INSTRUMENT) return note == MARKER_NOTE;
        return data.getInstrument() == FILLED_INSTRUMENT && note >= 1 && note <= MAX_BOTTLES;
    }

    /** Ставит блок-носитель кувшина в нужном состоянии: нижняя или верхняя плита. */
    private static void setJugBlock(Block block, Slab.Type type) {
        block.setType(JUG_BLOCK, false);
        if (block.getBlockData() instanceof Slab data) {
            data.setType(type);
            block.setBlockData(data, false);
        }
    }

    /** Состояние плиты для содержимого: пустой кувшин — нижняя, налитый — верхняя. */
    private static Slab.Type typeOf(Contents contents) {
        return contents.count > 0 ? FILLED_SLAB : EMPTY_SLAB;
    }

    /** Запоминает кувшин по ключу записи; мир ещё не загружен — пропускаем. */
    private void pin(String key, Contents contents) {
        Location location = locationOf(key);
        if (location == null || location.getWorld() == null) return;
        pins.put(key, new JugPin(location, typeOf(contents)));
    }

    /** Перечитать запись из jugs.yml и сразу вернуть блоку канонический вид. */
    private void refresh(String key) {
        Contents contents = readContents(key);
        pin(key, contents);
        JugPin pin = pins.get(key);
        if (pin != null) pin.enforce();
    }

    private void rebuildPins() {
        pins.clear();
        for (String key : jugsData.getKeys(false)) pin(key, readContents(key));
    }

    /** Раз в тик: у каждого активного кувшина состояние строго каноническое. */
    private void guardTick() {
        if (++guardTicks >= PIN_RESCAN_TICKS) {
            guardTicks = 0;
            rebuildPins();
        }
        for (JugPin pin : pins.values()) pin.enforce();
        if (++infusionTicks >= INFUSION_SWEEP_TICKS) {
            infusionTicks = 0;
            sweepInfusions();
        }
    }

    /** Убираем записи о пропитанных растениях, которых в мире уже нет. */
    private void sweepInfusions() {
        if (infusions.isEmpty()) return;
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, String> entry : infusions.entrySet()) {
            // Мир ещё не загружен (старт сервера) — запись не трогаем.
            Location location = locationOf(entry.getKey());
            if (location == null || location.getWorld() == null) continue;
            World world = location.getWorld();
            if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;
            if (!isPlant(world.getBlockAt(location))) gone.add(entry.getKey());
        }
        for (String key : gone) dropInfusion(key);
    }

    /** Восстановить блок, если это записанный кувшин; остальное не трогаем. */
    private void enforceAt(Block block) {
        JugPin pin = pins.get(blockKey(block));
        if (pin != null) pin.enforce();
    }

    private void enforceAround(Block changed) {
        enforceAt(changed);
        enforceAt(changed.getRelative(0, 1, 0));
        enforceAt(changed.getRelative(0, -1, 0));
        enforceAt(changed.getRelative(1, 0, 0));
        enforceAt(changed.getRelative(-1, 0, 0));
        enforceAt(changed.getRelative(0, 0, 1));
        enforceAt(changed.getRelative(0, 0, -1));
    }

    /** Возвращает кувшинам состояние в уже загруженных чанках (после перезапуска). */
    private void restoreLoadedJugs() {
        rebuildPins();
        for (JugPin pin : pins.values()) pin.enforce();
    }

    /**
     * Кувшин — это плита-носитель, поставленная из предмета кувшина. Признак —
     * запись в jugs.yml: её создаёт только установка кувшина, поэтому состояние
     * блока (нижняя/верхняя плита) не обязательное условие. Так блок остаётся
     * кувшином, даже если состояние сбили отладкой или поршнем: каноническое
     * возвращается {@link #guardTick()} и событиями защиты.
     */
    private boolean isJugBlock(Block block) {
        return block != null && block.getType() == JUG_BLOCK
                && jugsData.contains(blockKey(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Шифт с наполненным кувшином — это всегда выливание. Даже если клик
        // дошёл до постановки блока (событие взаимодействия не пришло), блок не
        // ставим: выливаем порцию на место клика.
        Player placing = event.getPlayer();
        if (placing.isSneaking()) {
            EquipmentSlot jugSlot = filledJugHand(placing, event.getHand());
            if (jugSlot != null) {
                ItemStack jug = handItem(placing, jugSlot);
                Contents contents = contentsOf(jug);
                if (contents.count > 0) {
                    event.setCancelled(true);
                    spillPortion(placing, jugSlot, jug, contents, event.getBlockPlaced());
                    return;
                }
            }
        }
        ItemStack hand = event.getItemInHand();
        if (!isJug(hand)) return;
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.NOTE_BLOCK) return;
        Contents contents = contentsOf(hand);

        // Предмет — нот-блок (ставится на любую грань), а кувшином становится
        // плита-носитель: она не сплошная, поэтому соседние блоки не «пропадают».
        setJugBlock(block, typeOf(contents));

        writeContents(blockKey(block), contents);
        storage.markDirty();
        refresh(blockKey(block));
    }

    /**
     * Кувшин, поставленный до 10.2: у пустого кувшина тогда не было записи в
     * jugs.yml, и признаком остаётся только состояние (нота 24 + флейта).
     * Ванильная флейта бывает лишь над глиной, поэтому без явного жеста
     * (liquid) блок над глиной не «усыновляем» — там мог быть обычный
     * нот-блок, который игрок настраивает.
     */
    private boolean isLegacyJug(Block block, boolean liquid) {
        if (block == null || block.getType() != Material.NOTE_BLOCK) return false;
        if (jugsData.contains(blockKey(block))) return false;
        if (!(block.getBlockData() instanceof NoteBlock data)) return false;
        if (data.getNote().getId() != MARKER_NOTE || data.getInstrument() != EMPTY_INSTRUMENT) return false;
        return liquid || block.getRelative(0, -1, 0).getType() != Material.CLAY;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String key = blockKey(block);
        // Кувшин старого образца (нот-блок без записи) тоже забирается: считаем
        // его пустым — записи о содержимом у таких кувшинов нет.
        if (!isJugBlock(block) && !isLegacyJug(block, false)) return;
        Contents contents = readContents(key);
        jugsData.set(key, null);
        pins.remove(key);
        storage.markDirty();
        // Сам блок-носитель не выпадает: вместо него — кувшин с содержимым.
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
            pins.remove(key);
            changed = true;
        }
        if (changed) storage.markDirty();
    }

    // ===== ЗАЩИТА КУВШИНА =====
    // Плита-носитель должна оставаться одиночной: двойная плита — сплошной
    // блок, а сплошные блоки снова скрывали бы грани соседей (вернулись бы
    // щели). Состояние и физика возвращаются в том же тике: до отправки
    // блок-апдейта клиент чужой плиты не увидит.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (!isJugBlock(block)) return;
        // Кувшин стоит сам по себе: ни опоры, ни срастания в двойную плиту.
        event.setCancelled(true);
        enforceAt(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNeighborPlace(BlockPlaceEvent event) {
        enforceAround(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNeighborBreak(BlockBreakEvent event) {
        enforceAround(event.getBlock());
    }

    /**
     * Загрузился чанк — проверяем кувшины в нём: состояние плиты могло
     * сбиться, пока чанк был выгружен (например, кто-то поставил второй
     * полублок), а кувшины из версии 10.10 и раньше вообще стоят нот-блоками
     * и как раз здесь переносятся на плиту.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (jugsData.getKeys(false).isEmpty()) return;
        String prefix = event.getWorld().getName() + "_";
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (String key : jugsData.getKeys(false)) {
            if (!key.startsWith(prefix)) continue;
            Location location = locationOf(key);
            if (location == null) continue;
            if ((location.getBlockX() >> 4) != chunkX || (location.getBlockZ() >> 4) != chunkZ) continue;
            refresh(key);
        }
    }

    // ===== ВЫЛИВАНИЕ ЖИДКОСТЕЙ (ПКМ по кувшину) =====

    /** Жидкость для выливания: зелье, мёд или предмет с любым PDC-ключом "liquid". */
    private static final class Liquid {
        final String kind;
        final String potion;
        final byte[] custom;
        /** Сколько порций наливает один предмет: бутылочка — 1, ведро — 3. */
        final int amount;
        /** Что вернуть вместо опустевшей тары: бутылочку, ведро или ничего. */
        final Material leftover;

        Liquid(String kind, String potion, byte[] custom, int amount, Material leftover) {
            this.kind = kind;
            this.potion = potion;
            this.custom = custom;
            this.amount = amount;
            this.leftover = leftover;
        }

        String identity() {
            if (HONEY_KIND.equals(kind)) return "HONEY";
            if (MILK_KIND.equals(kind)) return "MILK";
            if (WATER_KIND.equals(kind)) return "WATER";
            if (CUSTOM_KIND.equals(kind)) return "CUSTOM:" + (custom == null ? "" : Base64.getEncoder().encodeToString(custom));
            return "POTION:" + (potion == null ? "" : potion);
        }
    }

    private Liquid classifyLiquid(ItemStack item) {
        if (item.getType() == Material.POTION && item.getItemMeta() instanceof PotionMeta meta) {
            PotionType type = meta.getBasePotionType();
            if (type == null) return null;
            return new Liquid(POTION_KIND, type.name(), null, 1, Material.GLASS_BOTTLE);
        }
        if (item.getType() == Material.HONEY_BOTTLE) return new Liquid(HONEY_KIND, null, null, 1, Material.GLASS_BOTTLE);
        // Вёдра: одно ведро воды или молока — сразу три порции кувшина.
        if (item.getType() == Material.WATER_BUCKET) return new Liquid(WATER_KIND, null, null, BUCKET_SLOTS, Material.BUCKET);
        if (item.getType() == Material.MILK_BUCKET) return new Liquid(MILK_KIND, null, null, BUCKET_SLOTS, Material.BUCKET);
        if (item.hasItemMeta()) {
            // Универсальный маркер для жидкостей из других плагинов: любой
            // неймспейс, имя ключа "liquid".
            boolean marked = item.getItemMeta().getPersistentDataContainer().getKeys().stream()
                    .anyMatch(key -> key.getKey().equals("liquid"));
            if (marked) {
                ItemStack one = item.clone();
                one.setAmount(1);
                return new Liquid(CUSTOM_KIND, null, one.serializeAsBytes(), 1, null);
            }
        }
        return null;
    }

    /** В руке, которой кликнули, лежит жидкость для кувшина. */
    private boolean carriesLiquid(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        return classifyLiquid(item) != null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null
                || (block.getType() != JUG_BLOCK && block.getType() != Material.NOTE_BLOCK)) return;
        Player player = event.getPlayer();
        if (!isJugBlock(block)) {
            // Кувшин старого образца узнаём по состоянию и заводим ему запись.
            if (!isLegacyJug(block, carriesLiquid(player, event.getHand()))) return;
            writeContents(blockKey(block), new Contents(null, null, null, 0));
            storage.markDirty();
            refresh(blockKey(block));
        }
        EquipmentSlot slot = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        ItemStack hand = handItem(player, slot);
        if (hand.getType() == Material.GLASS_BOTTLE) {
            // Пустой бутылочкой жидкость забирается из кувшина обратно.
            if (takeBottle(player, block, hand, slot)) event.setCancelled(true);
            return;
        }
        if (hand.getType() == Material.BUCKET) {
            // Ведро черпает воду или молоко: одна порция ведра — три порции кувшина.
            if (takeBucket(player, block, hand, slot)) event.setCancelled(true);
            return;
        }
        if (ContainerInteraction.bypassMenu(player.isSneaking(),
                player.getInventory().getItemInMainHand().getType().isAir(),
                player.getInventory().getItemInOffHand().getType().isAir())) {
            // Шифт с предметом в руке — ванильный вторичный жест
            // (например, поставить блок о кувшин). Выливание пропускаем.
            return;
        }
        event.setCancelled(true);
        Liquid liquid = classifyLiquid(hand);
        if (liquid == null) return;
        pour(player, block, hand, slot, liquid);
    }

    private void pour(Player player, Block block, ItemStack source, EquipmentSlot slot, Liquid liquid) {
        String key = blockKey(block);
        Contents stored = readContents(key);

        if (stored.count + liquid.amount > MAX_BOTTLES) {
            player.sendActionBar(liquid.amount > 1
                    ? "§cВ кувшине нет места на ведро: нужно " + liquid.amount + " порции."
                    : "§cКувшин полон! Больше " + MAX_BOTTLES + " порций не влить.");
            return;
        }
        if (stored.count > 0 && !stored.identity().equals(liquid.identity())) {
            player.sendActionBar("§cКажется, в кувшине уже есть другая жидкость...");
            return;
        }

        int newCount = stored.count + liquid.amount;
        writeContents(key, new Contents(liquid.kind, liquid.potion, liquid.custom, newCount));
        storage.markDirty();
        // Модель выбирает состояние плиты — его выставляет refresh().
        refresh(key);

        if (liquid.leftover != null) {
            // Тара возвращается пустой: бутылочка или ведро.
            replaceOne(player, source, new ItemStack(liquid.leftover), slot);
        } else if (source.getAmount() > 1) {
            source.setAmount(source.getAmount() - 1);
        } else {
            setHandItem(player, slot, new ItemStack(Material.AIR));
        }
        player.updateInventory();
        player.playSound(player.getLocation(),
                liquid.amount > 1 ? Sound.ITEM_BUCKET_EMPTY : Sound.ITEM_BOTTLE_EMPTY, 0.8f, 1.0f);
    }

    /** Заменяет один предмет в указанной руке на другой, не затирая остальной стек. */
    private static void replaceOne(Player player, ItemStack source, ItemStack reward, EquipmentSlot slot) {
        if (source.getAmount() > 1) {
            source.setAmount(source.getAmount() - 1);
            player.getInventory().addItem(reward).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        } else {
            setHandItem(player, slot, reward);
        }
    }

    /**
     * Пустая бутылочка забирает из кувшина одну порцию: зелье, мёд, вода или
     * сторонняя жидкость возвращаются тем же предметом, каким их наливали.
     * Молоко в бутылочку не наливается — его черпают ведром.
     */
    private boolean takeBottle(Player player, Block block, ItemStack bottle, EquipmentSlot slot) {
        String key = blockKey(block);
        Contents stored = readContents(key);
        if (stored.count <= 0) return false;
        if (MILK_KIND.equals(stored.kind)) {
            player.sendActionBar("§cМолоко из кувшина набирают ведром: нужно " + BUCKET_SLOTS + " порции.");
            return true;
        }
        ItemStack filled = bottleOf(stored);
        if (filled == null) return false;

        writeContents(key, stored.count <= 1
                ? new Contents(null, null, null, 0)
                : new Contents(stored.kind, stored.potion, stored.custom, stored.count - 1));
        storage.markDirty();
        refresh(key);

        replaceOne(player, bottle, filled, slot);
        player.updateInventory();
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 0.8f, 1.0f);
        return true;
    }

    /**
     * Пустое ведро черпает из кувшина воду или молоко: одно ведро — три порции.
     */
    private boolean takeBucket(Player player, Block block, ItemStack bucket, EquipmentSlot slot) {
        String key = blockKey(block);
        Contents stored = readContents(key);
        if (stored.count <= 0) return false;
        ItemStack filled = bucketOf(stored);
        if (filled == null) {
            player.sendActionBar("§cВедром из кувшина набирают только воду и молоко.");
            return true;
        }
        if (stored.count < BUCKET_SLOTS) {
            player.sendActionBar("§cВ кувшине меньше ведра — жидкости не хватает.");
            return true;
        }
        writeContents(key, stored.count <= BUCKET_SLOTS
                ? new Contents(null, null, null, 0)
                : new Contents(stored.kind, stored.potion, stored.custom, stored.count - BUCKET_SLOTS));
        storage.markDirty();
        refresh(key);

        replaceOne(player, bucket, filled, slot);
        player.updateInventory();
        player.playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL, 0.8f, 1.0f);
        return true;
    }

    /** Ведро воды или молока на три порции из кувшина. */
    private ItemStack bucketOf(Contents contents) {
        if (WATER_KIND.equals(contents.kind)) return new ItemStack(Material.WATER_BUCKET);
        if (MILK_KIND.equals(contents.kind)) return new ItemStack(Material.MILK_BUCKET);
        return null;
    }

    /** Предмет с одной порцией жидкости из кувшина. */
    private ItemStack bottleOf(Contents contents) {
        if (POTION_KIND.equals(contents.kind)) {
            PotionType type = parseType(contents.potion);
            if (type == null) return null;
            ItemStack potion = new ItemStack(Material.POTION);
            if (potion.getItemMeta() instanceof PotionMeta meta) {
                meta.setBasePotionType(type);
                potion.setItemMeta(meta);
            }
            return potion;
        }
        if (HONEY_KIND.equals(contents.kind)) return new ItemStack(Material.HONEY_BOTTLE);
        if (WATER_KIND.equals(contents.kind)) {
            ItemStack water = new ItemStack(Material.POTION);
            if (water.getItemMeta() instanceof PotionMeta meta) {
                meta.setBasePotionType(PotionType.WATER);
                water.setItemMeta(meta);
            }
            return water;
        }
        if (CUSTOM_KIND.equals(contents.kind) && contents.custom != null) {
            try { return ItemStack.deserializeBytes(contents.custom); } catch (Throwable ignored) { return null; }
        }
        return null;
    }

    // ===== ВЫЛИВАНИЕ НА ЗЕМЛЮ И ПРОПИТКА РАСТЕНИЙ =====

    /**
     * Шифт + ПКМ с наполненным кувшином — выливается одна порция. Вылить можно
     * ХОТЬ КУДА: в воздух, в любой блок, во что угодно — кувшин при шифте никогда
     * не ставится и ничего не открывает, всегда только выливается. Сообщение
     * всегда одно и то же. Слушаем самым ранним приоритетом и без фильтра
     * «отменено», чтобы жест не мог перехватить никто другой.
     * Пропитка растения — побочный эффект выливания: если порция попала на
     * грядку или куст, растение запоминает зелье (или молоко).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSpill(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        // Кувшин может быть в любой руке: сначала смотрим ту, которой кликнули,
        // потом вторую — выливание не должно зависеть от того, куда он положен.
        EquipmentSlot slot = filledJugHand(player, event.getHand());
        if (slot == null) return;
        ItemStack jug = handItem(player, slot);
        Contents contents = contentsOf(jug);
        if (contents.count <= 0) return;
        event.setCancelled(true);
        spillPortion(player, slot, jug, contents,
                action == Action.RIGHT_CLICK_BLOCK ? event.getClickedBlock() : null);
    }

    /**
     * Выливает одну порцию из кувшина: брызги, красное сообщение, если под
     * прицелом растение — оно попутно пропитывается.
     */
    private void spillPortion(Player player, EquipmentSlot slot, ItemStack jug, Contents contents, Block clicked) {
        String infusion = infusionOf(contents);
        if (infusion != null && clicked != null) {
            Block plant = plantAt(clicked);
            if (plant != null) putInfusion(blockKey(plant), infusion);
        }
        spillFx(player, clicked, contents);
        player.sendActionBar("§cВы вылили жидкость из кувшина...");
        swapJugInHand(player, jug, slot, contents.count <= 1
                ? new Contents(null, null, null, 0)
                : new Contents(contents.kind, contents.potion, contents.custom, contents.count - 1));
    }

    /** Предмет в указанной руке игрока (null-рука считается главной). */
    private static ItemStack handItem(Player player, EquipmentSlot slot) {
        return slot == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    /**
     * В какой руке наполненный кувшин для этого клика: сначала рука клика, затем
     * вторая (клик по воздуху всегда приходит главной рукой, даже если кувшин в
     * оффхенде). Возвращает null, если выливать нечего.
     */
    private EquipmentSlot filledJugHand(Player player, EquipmentSlot clicked) {
        EquipmentSlot first = clicked == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        EquipmentSlot second = first == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        for (EquipmentSlot slot : new EquipmentSlot[]{first, second}) {
            ItemStack item = handItem(player, slot);
            if (isJug(item) && bottles(item) > 0) return slot;
        }
        return null;
    }

    /**
     * Чем пропитается растение, если вылить на него эту жидкость: молоко —
     * молочной пропиткой, зелье — только если у него есть эффекты. Мёд, вода и
     * чужие жидкости просто впитываются в землю (без пропитки и без ругани).
     */
    private static String infusionOf(Contents contents) {
        if (MILK_KIND.equals(contents.kind)) return MILK_INFUSION;
        if (!POTION_KIND.equals(contents.kind)) return null;
        PotionType type = parseType(contents.potion);
        return type != null && !type.getPotionEffects().isEmpty() ? contents.potion : null;
    }

    /** Растение под прицелом: сам куст/грядка или блок над вспаханной землёй. */
    private static Block plantAt(Block clicked) {
        if (clicked == null) return null;
        if (isPlant(clicked)) return plantHead(clicked);
        if (clicked.getType() == Material.FARMLAND) {
            Block above = clicked.getRelative(0, 1, 0);
            if (isPlant(above)) return plantHead(above);
        }
        return null;
    }

    /** Грядка, куст сладких ягод или лоза светящихся ягод. */
    private static boolean isPlant(Block block) {
        if (block.getType() == Material.CAVE_VINES || block.getType() == Material.CAVE_VINES_PLANT) return true;
        return block.getBlockData() instanceof Ageable;
    }

    /** У лозы светящихся ягод плоды на верхушке: поднимаемся до неё. */
    private static Block plantHead(Block block) {
        Block current = block;
        for (int i = 0; i < 40 && current.getType() == Material.CAVE_VINES_PLANT; i++) {
            current = current.getRelative(0, 1, 0);
        }
        return current.getType() == Material.CAVE_VINES ? current : block;
    }

    private static void spillFx(Player player, Block target, Contents contents) {
        Location spot = target == null
                ? player.getLocation().add(0, 0.15, 0)
                : target.getLocation().add(0.5, 1.05, 0.5);
        World world = spot.getWorld();
        world.spawnParticle(Particle.SPLASH, spot, 25, 0.35, 0.15, 0.35, 0.0);
        if (POTION_KIND.equals(contents.kind)) {
            try {
                world.spawnParticle(Particle.ENTITY_EFFECT, spot, 15, 0.3, 0.2, 0.3, 0.0, Color.fromRGB(0x9B5DE5));
            } catch (Throwable ignored) {
                // Ядро не приняло цвет частицы — остаются брызги.
            }
        }
        world.playSound(spot, Sound.ITEM_BOTTLE_EMPTY, 0.9f, 0.8f);
        world.playSound(spot, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.2f);
    }

    /** Меняет кувшин в указанной руке на такой же, но с другим количеством порций. */
    private void swapJugInHand(Player player, ItemStack jug, EquipmentSlot slot, Contents next) {
        Component customName = jug.getItemMeta() == null ? null : jug.getItemMeta().displayName();
        ItemStack updated = create(next.count, next.kind, next.potion, next.custom);
        if (customName != null && !customName.equals(ProfileItems.text(TITLE, NamedTextColor.GOLD))) {
            ItemMeta meta = updated.getItemMeta();
            meta.displayName(customName);
            updated.setItemMeta(meta);
        }
        if (jug.getAmount() > 1) {
            jug.setAmount(jug.getAmount() - 1);
            player.getInventory().addItem(updated).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        } else {
            setHandItem(player, slot, updated);
        }
        // Кувшин сменил вид (открытый/закрытый): заставляем клиент перерисовать предмет.
        player.updateInventory();
    }

    /** Кладёт предмет в указанную руку игрока. */
    private static void setHandItem(Player player, EquipmentSlot slot, ItemStack item) {
        if (slot == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    // ===== ПРОПИТАННЫЙ УРОЖАЙ =====

    /** Пропитка растений хранится рядом с кувшинами: infusions.<ключ> = зелье. */
    private void loadInfusions() {
        ConfigurationSection section = jugsData.getConfigurationSection("infusions");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String potion = section.getString(key);
            if (potion != null && !potion.isEmpty()) infusions.put(key, potion);
        }
    }

    private void putInfusion(String key, String potion) {
        infusions.put(key, potion);
        jugsData.set("infusions." + key, potion);
        storage.markDirty();
    }

    private void dropInfusion(String key) {
        if (infusions.remove(key) == null) return;
        jugsData.set("infusions." + key, null);
        storage.markDirty();
    }

    /** Ключ записи о пропитке для блока (у лозы светящихся ягод ягоды на верхушке). */
    private String infusionKey(Block block) {
        if (block == null) return null;
        String key = blockKey(block);
        if (infusions.containsKey(key)) return key;
        if (block.getType() == Material.CAVE_VINES_PLANT) {
            String headKey = blockKey(plantHead(block));
            if (infusions.containsKey(headKey)) return headKey;
        }
        return null;
    }

    /** Снимает пропитку: урожай с растения можно снять только один раз. */
    private String takeInfusion(Block block) {
        String key = infusionKey(block);
        if (key == null) return null;
        String potion = infusions.get(key);
        dropInfusion(key);
        return potion;
    }

    /**
     * Помечает еду меткой «пропитано»; не еду не трогаем. Молочная пропитка
     * подписана «Пропитано молоком» тем же цветом, что и зельевая.
     */
    private ItemStack infusedProduce(ItemStack stack, String infusion) {
        if (stack == null || stack.getType().isAir() || !isEdible(stack)) return null;
        ItemStack marked = stack.clone();
        ItemMeta meta = marked.getItemMeta();
        meta.getPersistentDataContainer().set(infuseKey, PersistentDataType.STRING, infusion);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(ProfileItems.text(MILK_INFUSION.equals(infusion) ? "Пропитано молоком" : "Пропитано зельем",
                NamedTextColor.LIGHT_PURPLE));
        meta.lore(lore);
        marked.setItemMeta(meta);
        return marked;
    }

    private static boolean isEdible(ItemStack stack) {
        try {
            return stack.getData(DataComponentTypes.FOOD) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Сломанное пропитанное растение: еда в дропе запоминает зелье. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInfusedHarvest(BlockDropItemEvent event) {
        if (infusions.isEmpty()) return;
        BlockState state = event.getBlockState();
        String potion = takeInfusion(state.getWorld().getBlockAt(state.getX(), state.getY(), state.getZ()));
        if (potion == null) return;
        for (Item drop : event.getItems()) {
            ItemStack marked = infusedProduce(drop.getItemStack(), potion);
            if (marked != null) drop.setItemStack(marked);
        }
    }

    /** Ягоды собирают и ПКМ (лоза светящихся ягод, куст сладких ягод) — это тоже урожай. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInfusedPick(PlayerHarvestBlockEvent event) {
        if (infusions.isEmpty()) return;
        Block block = event.getHarvestedBlock();
        String key = infusionKey(block);
        if (key == null) return;
        String potion = infusions.get(key);
        List<ItemStack> harvested = event.getItemsHarvested();
        try {
            for (int i = 0; i < harvested.size(); i++) {
                ItemStack marked = infusedProduce(harvested.get(i), potion);
                if (marked != null) harvested.set(i, marked);
            }
        } catch (UnsupportedOperationException error) {
            // Ядро отдало неизменяемый список: пропитку не тратим, ягоды пойдут как есть.
            return;
        }
        dropInfusion(key);
    }

    /**
     * Пропитанная еда при съедании даёт эффект зелья на 8 секунд, а молочная —
     * срезает по 8 секунд со всех активных эффектов игрока.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInfusedEat(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        String infusion = item.getItemMeta().getPersistentDataContainer().get(infuseKey, PersistentDataType.STRING);
        if (infusion == null) return;
        Player player = event.getPlayer();
        if (MILK_INFUSION.equals(infusion)) {
            cutEffectTimers(player);
        } else {
            PotionType type = parseType(infusion);
            if (type == null) return;
            for (PotionEffect effect : type.getPotionEffects()) {
                player.addPotionEffect(new PotionEffect(effect.getType(), INFUSED_EFFECT_TICKS,
                        effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon()));
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.5f, 1.3f);
    }

    /** Молочная пропитка: всем активным эффектам минус 8 секунд (короткие заканчиваются). */
    private void cutEffectTimers(Player player) {
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            // Эффект без срока (бесконечный) не трогаем — у него нет таймера.
            if (effect.getDuration() < 0) continue;
            int left = effect.getDuration() - MILK_CUT_TICKS;
            player.removePotionEffect(effect.getType());
            if (left > 0) player.addPotionEffect(effect.withDuration(left));
        }
    }

    // ===== ПИТЬЁ ПРЯМО ИЗ ИНВЕНТАРЯ =====
    // Держим ПКМ — ваниль сама крутит анимацию «поднести ко рту», играет глотки
    // и доводит использование до конца; на финише порцию считаем мы.

    /**
     * Ваниль закончила использование кувшина: предмет не отдаём — сами вычитаем
     * порции (молоко — сразу ведро) и накладываем эффекты жидкости.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onJugDrink(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!isJug(item)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        // Шифт с кувшином в руке — это выливание, а не питьё.
        if (player.isSneaking()) return;
        finishDrink(player, item, event.getHand());
    }

    /** Молоко из кувшина пьют только «ведром»: подсказываем, если порций мало. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMilkSipHint(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (player.isSneaking()) return;
        EquipmentSlot slot = filledJugHand(player, event.getHand());
        if (slot == null) return;
        ItemStack jug = handItem(player, slot);
        Contents contents = contentsOf(jug);
        if (!MILK_KIND.equals(contents.kind) || contents.count >= BUCKET_SLOTS) return;
        player.sendActionBar("§cМолока меньше ведра — пить нечего. Нужно " + BUCKET_SLOTS + " порции.");
    }

    private void finishDrink(Player player, ItemStack snapshot, EquipmentSlot hand) {
        ItemStack main = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!main.isSimilar(snapshot)) return;
        Contents contents = contentsOf(main);
        if (contents.count <= 0) return;

        applyDrinkEffects(player, contents);
        player.playSound(player.getEyeLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.0f);

        Component customName = main.getItemMeta().displayName();
        Component defaultName = ProfileItems.text(TITLE, NamedTextColor.GOLD);
        int portion = MILK_KIND.equals(contents.kind) ? BUCKET_SLOTS : 1;
        ItemStack updated = create(Math.max(0, contents.count - portion),
                contents.kind, contents.potion, contents.custom);
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
        } else if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(updated);
        } else {
            player.getInventory().setItemInMainHand(updated);
        }
        player.updateInventory();
    }

    private void applyDrinkEffects(Player player, Contents contents) {
        if (POTION_KIND.equals(contents.kind)) {
            PotionType type = parseType(contents.potion);
            if (type != null) for (PotionEffect effect : type.getPotionEffects()) player.addPotionEffect(effect);
        } else if (MILK_KIND.equals(contents.kind)) {
            // Молоко снимает все эффекты, как ванильное ведро молока.
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
        } else if (HONEY_KIND.equals(contents.kind)) {
            // Мёд: лечит отравление и восстанавливает голод, как бутылочка мёда.
            player.removePotionEffect(PotionEffectType.POISON);
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + 6));
            player.setSaturation(Math.min((float) player.getFoodLevel(), player.getSaturation() + 1.2f));
        }
        // CUSTOM: эффекты сторонней жидкости неизвестны — просто выпивается.
    }

    // ===== ЭФФЕКТЫ ПЕРЕНОСКИ НАПОЛНЕННОГО КУВШИНА =====

    /** Наполненный кувшин в инвентаре или оффхенде — вес мешает в полёте на элитрах. */
    private boolean carriesFilledJug(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isJug(item) && bottles(item) > 0) return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return isJug(offhand) && bottles(offhand) > 0;
    }

    /** Наполненный кувшин надет на голову: тогда вес не давит. */
    private boolean wearsFilledJug(Player player) {
        ItemStack head = player.getInventory().getHelmet();
        return isJug(head) && bottles(head) > 0;
    }

    /**
     * Вес кувшина давит на игрока, пока тот в переноске (инвентарь, оффхенд).
     * Надел кувшин в слот нагрудника — вес распределён, ограничения спадают.
     */
    private boolean feelsJugWeight(Player player) {
        return carriesFilledJug(player) && !wearsFilledJug(player);
    }

    private void applyCarryEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean heavy = feelsJugWeight(player);
            applyWeight(player, heavy);
            if (heavy && player.isGliding()) damageElytra(player);
        }
    }

    /** Замедление II без частиц и прыжок примерно в половину блока. */
    private void applyWeight(Player player, boolean heavy) {
        if (heavy) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, true, false, false));
        }
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump == null) return;
        if (heavy) {
            if (jump.getBaseValue() != WEIGHTED_JUMP_STRENGTH) jump.setBaseValue(WEIGHTED_JUMP_STRENGTH);
        } else if (jump.getBaseValue() == WEIGHTED_JUMP_STRENGTH) {
            jump.setBaseValue(VANILLA_JUMP_STRENGTH);
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
        storage.flushBlocking();
    }
}
