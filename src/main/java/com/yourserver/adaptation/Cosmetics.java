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
 * Свой предмет узнаётся по метке в PersistentDataContainer: её ставит только
 * {@link #item}, поэтому потерять её предмет не может, а перепутать косметику с
 * другим кастомным предметом плагина (у всех них модель {@code f8resurs:*}) —
 * нельзя. Именно по этой метке, а не по модели, работает охрана слота ниже.
 *
 * Настоящий шлем и косметика в один слот не помещаются: при надевании прежний
 * шлем возвращается в инвентарь (или падает под ноги, если места нет), и
 * сообщают об этом ровно один раз — иначе проверка раз в секунду превратила бы
 * это в спам. Пока косметика надета, настоящий шлем не надеть.
 *
 * Каждая запись в слот проверяется. Если слот предмет не принял, причина
 * уходит в лог строкой «Косметика не встала в слот шлема», а подробный разбор
 * по шагам делает {@link #diagnose} (команда {@code /profile cosmetic test}).
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
            throw new IllegalStateException("предмет не принял метаданные");
        }
        return item;
    }

    /** Модель предмета (или null). */
    private static NamespacedKey modelOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getItemModel();
    }

    /**
     * Метка косметики (её id) или null, если предмет не косметика.
     *
     * Признак — ТОЛЬКО метка в PersistentDataContainer, которую ставит
     * {@link #item}. Признак «модель из пространства имён f8resurs» для этого
     * не годится: так помечен любой кастомный предмет плагина — кувшин, икра,
     * колба, звезда, медаль, префикс, изморозь. Пока косметика узнавалась по
     * пространству имён модели, охрана слота отменяла клик, перетаскивание и
     * выброс у всех этих предметов сразу: их нельзя было ни переложить в
     * инвентаре, ни выкинуть, а при смерти они молча исчезали из дропа.
     */
    private String markOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        return id == null || id.isEmpty() ? null : id;
    }

    /** Наш ли это предмет: косметика, созданная этим плагином. */
    private boolean isCosmetic(ItemStack item) {
        return cosmetic(markOf(item));
    }

    /** Та ли это косметика, что нужна. */
    private boolean isCosmetic(ItemStack item, CosmeticCatalog.Cosmetic cosmetic) {
        if (item == null || cosmetic == null) return false;
        NamespacedKey model = modelOf(item);
        return sameCosmetic(markOf(item),
                model == null ? null : model.getNamespace(),
                model == null ? null : model.getKey(),
                cosmetic.id(), cosmetic.file());
    }

    /**
     * Косметика ли предмет — по метке. Модель предмета здесь не участвует
     * намеренно: она есть у каждого кастомного предмета плагина.
     */
    static boolean cosmetic(String mark) {
        return mark != null;
    }

    /**
     * Та ли это косметика, что нужна. Метка важнее модели: если метка есть,
     * сверяется только она. Модель смотрят лишь тогда, когда метки нет, —
     * и тогда предмет чужой, а совпадение модели ничего не значит.
     */
    static boolean sameCosmetic(String mark, String modelNamespace, String modelKey,
                                String cosmeticId, String cosmeticFile) {
        if (cosmetic(mark)) return mark.equals(cosmeticId);
        return MODEL_NAMESPACE.equals(modelNamespace) && modelKey != null
                && modelKey.equals(cosmeticFile);
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
        if (isCosmetic(helmet, cosmetic)) {
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

        inventory.setHelmet(item(plugin, cosmetic));
        worn.put(id, cosmetic.id());

        // Проверяем собственную запись. Если слот предмет не принял, косметика
        // молча не появится — об этом надо сказать сразу и внятно.
        ItemStack after = inventory.getHelmet();
        if (!isCosmetic(after, cosmetic)) {
            plugin.getLogger().warning("Косметика не встала в слот шлема " + player.getName()
                    + ": после записи в слоте " + describe(after)
                    + ", модель " + modelOf(after) + ". Подробности: /profile cosmetic test");
        }
    }

    private static String describe(ItemStack item) {
        return item == null ? "ничего" : item.getType() + " x" + item.getAmount();
    }

    /**
     * Пошаговый разбор надевания косметики — для {@code /profile cosmetic test}.
     * Показывает каждый шаг: чем слот был занят, какой предмет собрался, что
     * реально легло в слот. Если косметика не надевается, причина видна здесь.
     */
    List<String> diagnose(Player player, CosmeticCatalog.Cosmetic cosmetic) {
        List<String> out = new ArrayList<>();
        if (cosmetic == null) {
            out.add("§cКосметика не выбрана — надевать нечего. Профиль → Настроить → Косметика.");
            return out;
        }
        PlayerInventory inventory = player.getInventory();
        out.add("§7Косметика: §f" + cosmetic.id() + "§7, файл §ff8resurs:" + cosmetic.file());
        out.add("§7В слоте до: §f" + describe(inventory.getHelmet()));
        ItemStack item;
        try {
            item = item(plugin, cosmetic);
        } catch (RuntimeException ex) {
            out.add("§cПредмет косметики не собрался: " + ex.getMessage());
            return out;
        }
        ItemMeta meta = item.getItemMeta();
        out.add("§7Собран предмет: §f" + item.getType() + "§7, модель §f" + meta.getItemModel()
                + "§7, метка §f" + meta.getPersistentDataContainer().has(marker, PersistentDataType.STRING));
        inventory.setHelmet(item);
        ItemStack after = inventory.getHelmet();
        out.add("§7В слоте после записи: §f" + describe(after) + "§7, модель §f" + modelOf(after));
        out.add(isCosmetic(after, cosmetic)
                ? "§aСлот принял косметику. Если на голове её не видно — дело в ресурспаке: "
                        + "нет assets/f8resurs/items/" + cosmetic.file() + ".json"
                : "§cСлот НЕ принял косметику: предмет подменился или метаданные не легли.");
        return out;
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
            return "в слоте шлема косметика, модель " + modelOf(helmet);
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
    // Косметика — настоящий предмет в слоте брони, и забрать её оттуда нельзя:
    // проверка раз в секунду ставит в слот новый предмет, и вытащенный остался
    // бы у игрока вторым экземпляром. Отменяются только действия с самой
    // косметикой — остальные предметы, в том числе все прочие кастомные
    // предметы плагина, перекладываются и выбрасываются как обычно.

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
