package com.yourserver.adaptation;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Косметика на голове игрока — предмет, который буквально лежит в слоте шлема.
 *
 * Никаких сущностей и никаких пакетов: косметика ставится в
 * {@code inventory.setHelmet(...)} обычным предметом, и клиент рисует её ровно
 * так, как она выглядит в Blockbench в разделе {@code display.head}, — потому
 * что это и есть настоящий надетый предмет. Посадку правят в самой модели.
 *
 * Слот при этом занят по-настоящему, и из этого следуют три правила:
 * <ul>
 *   <li>предмет узнаётся по двум признакам сразу — метке в PDC и модели
 *       {@code f8resurs:*}, поэтому плагин отличает косметику от любого другого
 *       предмета и не даёт её вытащить, выкинуть, перетащить или потерять
 *       при смерти;</li>
 *   <li>настоящий шлем и косметика в один слот не помещаются: при надевании
 *       косметики прежний шлем возвращается в инвентарь (или падает под ноги,
 *       если места нет), а пока косметика надета, шлем не надеть;</li>
 *   <li>косметику видно всем, включая владельца, — это обычный надетый
 *       предмет.</li>
 * </ul>
 *
 * Раз в секунду слот проверяется: если косметику сняли чем-то посторонним
 * (команда, чужой плагин), она возвращается на место. Каждая запись в слот
 * проверяется — если слот предмет не принял, причина уходит в лог, а не
 * превращается в молчаливую пустую голову.
 *
 * Модель берётся из ресурспака: {@code file: kosmetika1} → предмет
 * {@code f8resurs:kosmetika1} на базе обычного листа бумаги (сам лист не видно —
 * рисуется только модель косметики).
 */
final class Cosmetics implements Listener {
    /** База для косметики: её модель целиком задаёт ресурспак. */
    private static final Material BASE_ITEM = Material.PAPER;
    /** Пространство имён моделей косметики в ресурспаке. */
    private static final String MODEL_NAMESPACE = "f8resurs";

    private final JavaPlugin plugin;
    /** Метка «это косметика» в PersistentDataContainer предмета. */
    private final NamespacedKey marker;
    /** Косметика игрока (или null, если ничего не надето). */
    private final Function<Player, CosmeticCatalog.Cosmetic> equipped;
    /** Какая косметика надета. */
    private final Map<UUID, String> worn = new HashMap<>();
    /** Кому уже сказали про переложенный шлем — чтобы не повторять каждый тик. */
    private final Set<UUID> helmetMoved = new HashSet<>();
    private final BukkitTask task;
    private boolean disabled;

    Cosmetics(JavaPlugin plugin, Function<Player, CosmeticCatalog.Cosmetic> equipped) {
        this.plugin = plugin;
        this.equipped = equipped;
        this.marker = new NamespacedKey(plugin, "cosmetic");
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, 20L);
    }

    /**
     * Предмет косметики: обычный лист бумаги, у которого модель заменена на
     * {@code f8resurs:<file>} из ресурспака.
     */
    static ItemStack item(JavaPlugin plugin, CosmeticCatalog.Cosmetic cosmetic) {
        ItemStack item = new ItemStack(BASE_ITEM);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(cosmetic.name(), NamedTextColor.GOLD));
        meta.setItemModel(new NamespacedKey(MODEL_NAMESPACE, cosmetic.file()));
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "cosmetic"), PersistentDataType.STRING, cosmetic.id());
        if (!item.setItemMeta(meta)) {
            throw new IllegalStateException("Предмет косметики не принял метаданные: " + cosmetic.id());
        }
        return item;
    }

    /**
     * Наш ли это предмет. Признаков два, и достаточно любого: метка в PDC или
     * модель из пространства имён ресурспака. Второй признак — страховка: если
     * метка по какой-то причине не переживёт round-trip через инвентарь,
     * косметика всё равно будет узнана, а не принята за настоящий шлем.
     */
    private boolean isCosmetic(ItemStack item) {
        if (item == null || item.getType() != BASE_ITEM || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().has(marker, PersistentDataType.STRING)) return true;
        NamespacedKey model = meta.getItemModel();
        return model != null && MODEL_NAMESPACE.equals(model.getNamespace());
    }

    /** Какая косметика помечена на предмете (или null). */
    private String cosmeticId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }

    /** Надеть/снять косметику; null снимает её совсем. */
    void apply(Player player, CosmeticCatalog.Cosmetic cosmetic) {
        if (player == null || !player.isOnline()) return;
        UUID id = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        ItemStack helmet = inventory.getHelmet();

        if (cosmetic == null) {
            worn.remove(id);
            helmetMoved.remove(id);
            if (isCosmetic(helmet)) inventory.setHelmet(null);
            return;
        }
        if (isCosmetic(helmet) && cosmetic.id().equals(cosmeticId(helmet))) {
            worn.put(id, cosmetic.id());
            return; // уже надета именно эта
        }
        // Настоящий шлем в один слот с косметикой не помещается — возвращаем его.
        if (helmet != null && !isCosmetic(helmet)) {
            inventory.setHelmet(null);
            if (!inventory.addItem(helmet).isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), helmet);
            }
            // Говорим один раз: иначе проверка раз в секунду превратила бы это в спам.
            if (helmetMoved.add(id)) {
                player.sendMessage("§7Шлем переложен в инвентарь: слот заняла косметика. "
                        + "Пока косметика надета, настоящий шлем не надеть.");
            }
        }

        ItemStack item = item(plugin, cosmetic);
        inventory.setHelmet(item);
        worn.put(id, cosmetic.id());

        // Проверяем собственную запись. Если слот предмет не принял, косметика
        // молча не появится — об этом надо сказать сразу и внятно.
        ItemStack after = inventory.getHelmet();
        if (!isCosmetic(after)) {
            plugin.getLogger().warning("Косметика не встала в слот шлема " + player.getName()
                    + ": после записи в слоте " + describe(after));
        }
    }

    private static String describe(ItemStack item) {
        return item == null ? "ничего" : item.getType() + " x" + item.getAmount();
    }

    void quit(Player player) {
        worn.remove(player.getUniqueId());
        helmetMoved.remove(player.getUniqueId());
    }

    void disable() {
        disabled = true;
        task.cancel();
        worn.clear();
        helmetMoved.clear();
    }

    /** Что сейчас надето — для /profile cosmetic status. */
    String status(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (isCosmetic(helmet)) {
            return "в слоте шлема косметика " + cosmeticId(helmet)
                    + ", модель " + helmet.getItemMeta().getItemModel();
        }
        return "в слоте шлема косметики нет"
                + (helmet == null ? " (слот пуст)" : " (там настоящий предмет: " + describe(helmet) + ")");
    }

    private CosmeticCatalog.Cosmetic resolve(Player player) {
        try {
            return equipped.apply(player);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Раз в секунду: вернуть косметику, если её сняли в обход плагина. */
    private void check() {
        if (disabled) return;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            try {
                apply(player, resolve(player));
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Не применена косметика " + player.getName(), ex);
            }
        }
    }

    // ===== ЗАЩИТА ПРЕДМЕТА В СЛОТЕ =====
    // Косметика — настоящий предмет в слоте брони, поэтому её надо охранять от
    // всего, что умеет делать игрок с предметами: вытащить, выкинуть,
    // перетащить, потерять при смерти.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!isCosmetic(event.getCurrentItem()) && !isCosmetic(event.getCursor())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (isCosmetic(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item dropped = event.getItemDrop();
        if (isCosmetic(dropped.getItemStack())) event.setCancelled(true);
    }

    /** При смерти косметика не выпадает: она вернётся на голову после возрождения. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> drops = event.getDrops();
        drops.removeIf(this::isCosmetic);
    }

    /** После возрождения инвентарь пуст — косметику надо надеть заново. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (disabled) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) apply(player, resolve(player));
        });
    }
}
