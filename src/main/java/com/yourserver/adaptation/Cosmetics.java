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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Косметика на голове игрока — предмет, который буквально лежит в слоте шлема.
 *
 * Никаких сущностей и никаких пакетов: косметика ставится в
 * {@code inventory.setHelmet(...)} обычным предметом, и клиент рисует её ровно
 * так, как она выглядит в Blockbench в разделе {@code display.head}, — потому
 * что это и есть настоящий надетый предмет. Посадку правят в самой модели.
 *
 * Слот при этом занят по-настоящему, и из этого следуют три правила, которые
 * здесь и зашиты:
 * <ul>
 *   <li>предмет помечен меткой в PDC, поэтому плагин отличает косметику от
 *       любого другого предмета и не даёт её вытащить, выкинуть, перетащить
 *       или потерять при смерти;</li>
 *   <li>настоящий шлем и косметика в один слот не помещаются: при надевании
 *       косметики прежний шлем возвращается в инвентарь (или падает под ноги,
 *       если места нет), а пока косметика надета, шлем не надеть;</li>
 *   <li>косметику видно всем, включая владельца, — это обычный надетый
 *       предмет.</li>
 * </ul>
 *
 * Модель берётся из ресурспака: {@code file: kosmetika1} → предмет
 * {@code f8resurs:kosmetika1} на базе обычного листа бумаги (сам лист не видно —
 * рисуется только модель косметики).
 *
 * Раз в секунду слот проверяется: если косметику сняли чем-то посторонним
 * (команда, чужой плагин), она возвращается на место. Заодно из инвентаря
 * убираются случайные копии — например, если предмет всё же удалось куда-то
 * переложить.
 */
final class Cosmetics implements Listener {
    /** База для косметики: её модель целиком задаёт ресурспак. */
    private static final Material BASE_ITEM = Material.PAPER;

    private final JavaPlugin plugin;
    /** Метка «это косметика» в PersistentDataContainer предмета. */
    private final NamespacedKey marker;
    /** Косметика игрока (или null, если ничего не надето). */
    private final Function<Player, CosmeticCatalog.Cosmetic> equipped;
    /** Какая косметика надета — для повторной проверки и диагностики. */
    private final Map<UUID, String> worn = new HashMap<>();
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
     * {@code f8resurs:<file>} из ресурспака. Помечен меткой, чтобы плагин
     * узнавал его в слоте шлема.
     */
    static ItemStack item(JavaPlugin plugin, CosmeticCatalog.Cosmetic cosmetic) {
        ItemStack item = new ItemStack(BASE_ITEM);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(cosmetic.name(), NamedTextColor.GOLD));
        meta.setItemModel(new NamespacedKey("f8resurs", cosmetic.file()));
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "cosmetic"), PersistentDataType.STRING, cosmetic.id());
        item.setItemMeta(meta);
        return item;
    }

    /** Наш ли это предмет: косметика, а не настоящий шлем игрока. */
    private boolean isCosmetic(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.STRING);
    }

    /** Надеть/снять косметику; null снимает её совсем. */
    void apply(Player player, CosmeticCatalog.Cosmetic cosmetic) {
        if (player == null || !player.isOnline()) return;
        UUID id = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        if (cosmetic == null) {
            worn.remove(id);
            if (isCosmetic(inventory.getHelmet())) inventory.setHelmet(null);
            sweepStray(player);
            return;
        }
        ItemStack helmet = inventory.getHelmet();
        if (isCosmetic(helmet)) {
            String current = helmet.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
            if (cosmetic.id().equals(current)) {
                worn.put(id, cosmetic.id());
                return; // уже надета именно эта
            }
        } else if (helmet != null) {
            // Настоящий шлем в один слот с косметикой не помещается — возвращаем его.
            inventory.setHelmet(null);
            if (inventory.addItem(helmet).isEmpty()) {
                player.sendMessage("§7Ваш шлем переложен в инвентарь: слот заняла косметика.");
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), helmet);
                player.sendMessage("§7В инвентаре не было места, шлем упал под ноги: слот заняла косметика.");
            }
        }
        inventory.setHelmet(item(plugin, cosmetic));
        worn.put(id, cosmetic.id());
        sweepStray(player);
    }

    void quit(Player player) {
        worn.remove(player.getUniqueId());
    }

    void disable() {
        disabled = true;
        task.cancel();
        worn.clear();
    }

    /**
     * Что сейчас надето — для /profile cosmetic status.
     */
    String status(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (isCosmetic(helmet)) {
            return "в слоте шлема лежит косметика "
                    + helmet.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING)
                    + ", модель f8resurs:" + helmet.getItemMeta().getItemModel();
        }
        return "в слоте шлема косметики нет"
                + (helmet == null ? " (слот пуст)" : " (там настоящий предмет: " + helmet.getType() + ")");
    }

    private CosmeticCatalog.Cosmetic resolve(Player player) {
        try {
            return equipped.apply(player);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Раз в секунду: вернуть косметику, если её сняли в обход плагина, и убрать копии. */
    private void check() {
        if (disabled) return;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            try {
                apply(player, resolve(player));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Не применена косметика " + player.getName() + ": " + ex);
            }
        }
    }

    /** Копии косметики вне слота шлема — мусор: удаляем, иначе их можно размножить. */
    private void sweepStray(Player player) {
        for (ItemStack each : player.getInventory().getContents()) {
            if (isCosmetic(each)) each.setAmount(0);
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
