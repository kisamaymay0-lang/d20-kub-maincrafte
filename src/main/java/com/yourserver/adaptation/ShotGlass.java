package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Посуда из RP Engine: налить в неё готовый напиток прямо из шейкера.
 *
 * <h2>Что происходит</h2>
 *
 * <p>ПКМ по поставленной посуде, когда в шейкере есть готовый напиток: посуда
 * исчезает, а на её месте появляется бутылочка — тот самый напиток. Напиток
 * уходит из шейкера, посуда не возвращается. Посуду же ({@code stakan},
 * {@code bokal}, {@code ryumka}) можно держать в руке: тогда ей просто
 * забирают напиток из открытого шейкера, как пустой бутылочкой.
 *
 * <h2>Как узнаём, что это наша посуда</h2>
 *
 * <p>Сначала — по метке самого RP Engine в persistent data: id модели лежит
 * строкой на хитбоксе поставленной модели ({@code rpengine:model}) и на её
 * предмете. Это работает на любой версии движка и не требует ни одного его
 * класса. Только если метки нет, спрашиваем API: {@code Models.at(entity)} и
 * {@code Items.idOf(stack)} — оба публичные интерфейсы.
 *
 * <p>Вызывать их надо <b>через интерфейс</b>, а не через класс объекта:
 * реализация {@code Placement} внутри RP Engine — приватный класс, и
 * {@code getClass().getMethod(...)} падает с {@code IllegalAccessException}.
 *
 * <h2>Клик</h2>
 *
 * <p>Слушаем {@code PlayerInteractAtEntityEvent} на {@link EventPriority#LOWEST}:
 * из него же RP Engine рассылает свой {@code ModelInteractEvent}, и отмена
 * нашего клика до его обработчика уже не доходит. Хитбокс — это
 * {@code Interaction}, но проверка на него не обязательна: посуда ищется по
 * метке, так что подойдёт и сущность из другой версии движка.
 *
 * <p>Если API недоступен, посуду убираем сами: снимаем её отображения (их
 * UUID перечислены в persistent data хитбокса), барьер/свет на месте и сам
 * хитбокс.
 *
 * <p>Идентификаторы посуды берутся из config.yml, ключ
 * {@code shaker.glass-models}: список «папка:предмет» ровно в том виде, в каком
 * посуда описана в паке RP Engine ({@code plugins/RPEngine/content/<папка>/items/...}).
 *
 * <h2>Файлы посуды</h2>
 *
 * <p>Готовые и проверенные по FORMAT.md файлы лежат в {@code docs/rpengine/glassware.yml}.
 */
final class ShotGlass implements Listener {

    /** Публичный интерфейс RP Engine: модели, стоящие в мире. */
    static final String MODELS_CLASS = "ai.resourcepack.engine.api.Models";

    /** Публичный интерфейс RP Engine: одна поставленная модель. */
    static final String PLACEMENT_CLASS = "ai.resourcepack.engine.api.Placement";

    /** Публичный интерфейс RP Engine: предметы пака. */
    static final String ITEMS_CLASS = "ai.resourcepack.engine.api.Items";

    /** Публичный интерфейс RP Engine: id контента. */
    static final String CONTENT_ID_CLASS = "ai.resourcepack.engine.api.ContentId";

    /** Если ключа в config.yml нет: посуда из пака testpack. */
    static final List<String> DEFAULT_MODELS = List.of(
            "testpack:stakan", "testpack:bokal", "testpack:ryumka");

    /** Не чаще одной строки в консоль на игрока за этот промежуток. */
    private static final long DEBUG_INTERVAL_MS = 3000L;

    /**
     * Тот же список id, что и в {@link #models}, но доступный {@link Shaker}:
     * им шейкер понимает, что в руке держат стакан, а не что-то ещё. Один
     * список на обе стороны — чтобы «во что наливать» и «чем черпать» не
     * разъезжались.
     */
    private static volatile Set<String> sharedModels = Set.copyOf(DEFAULT_MODELS);

    /** {@code Items#idOf(ItemStack)} — поиск id предмета пака. */
    private static Method idOfMethod;
    /** {@code ContentId#namespace()} и {@code ContentId#path()}. */
    private static Method namespaceMethod;
    private static Method pathMethod;
    /** Экземпляр {@code Items} из RP Engine. */
    private static Object itemsApi;
    /** Когда игроку последний раз объясняли, что у него в руке. */
    private static final Map<UUID, Long> explainedAt = new HashMap<>();
    /** Искали ли уже API предметов: один раз на запуск. */
    private static boolean itemsTried;

    private final Plugin plugin;
    /** id посуды: «namespace:path». */
    private final Set<String> models = new LinkedHashSet<>();
    /** Когда игроку последний раз писали причину отказа: чтобы не спамить. */
    private final Map<UUID, Long> debugAt = new HashMap<>();

    /** {@code Models#at(Entity)}. */
    private Method atMethod;
    /** {@code Models#near(Location, double)} — запасной поиск по месту. */
    private Method nearMethod;
    /** {@code Placement#modelId()}. */
    private Method modelIdMethod;
    /** {@code Placement#location()}. */
    private Method locationMethod;
    /** {@code Placement#remove(boolean)}. */
    private Method removeMethod;
    /** Экземпляр {@code Models} из RP Engine. */
    private Object modelsApi;
    /** Есть ли API поставленных моделей. */
    private boolean hooked;
    /** Что именно не нашлось — для /f8 rp. */
    private String hookProblem = "ещё не искали";

    ShotGlass(Plugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    /** Перечитать список посуды (/f8 reload и старт). */
    void reloadSettings() {
        models.clear();
        if (plugin.getConfig().isSet("shaker.glass-models")) {
            models.addAll(cleanIds(plugin.getConfig().getStringList("shaker.glass-models")));
        }
        if (models.isEmpty()) {
            models.addAll(DEFAULT_MODELS);
        }
        sharedModels = Set.copyOf(models);
    }

    /**
     * Список id: обрезаем пробелы, приводим к нижнему регистру, выбрасываем
     * пустые строки и повторы. Отдельным методом — чтобы это можно было
     * проверить тестом без сервера.
     */
    static Set<String> cleanIds(List<String> raw) {
        Set<String> cleaned = new LinkedHashSet<>();
        if (raw == null) return cleaned;
        for (String id : raw) {
            if (id == null) continue;
            String trimmed = id.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) cleaned.add(trimmed);
        }
        return cleaned;
    }

    /** id посуды из config.yml — для проверок без экземпляра плагина. */
    static Set<String> configuredModels() {
        return sharedModels;
    }

    /** Относится ли id к нашей посуде. */
    static boolean isConfigured(String id) {
        return id != null && sharedModels.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Стакан/бокал/рюмка в руке. Сначала спрашиваем API предметов RP Engine,
     * затем — метки в самом предмете: там id тоже лежит строкой.
     */
    static boolean isGlass(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }

        if (isConfigured(engineItemId(stack))) {
            return true;
        }

        return storedGlassId(stack) != null;
    }

    /** id посуды, записанный в предмете RP Engine, иначе null. */
    static String storedGlassId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = stack.getItemMeta();

        if (meta == null) {
            return null;
        }

        for (String value : stringsOf(meta.getPersistentDataContainer())) {
            if (isConfigured(value)) {
                return value.toLowerCase(Locale.ROOT);
            }
        }

        return null;
    }

    /** id предмета пака RP Engine через API, иначе null. */
    static String engineItemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }

        Object items = itemsApi();

        if (items == null || idOfMethod == null) {
            return null;
        }

        Object found = call(idOfMethod, items, stack);

        if (!(found instanceof Optional<?> optional) || optional.isEmpty()) {
            return null;
        }

        return contentId(optional.get());
    }

    /** {@code ContentId} → «namespace:path». */
    private static String contentId(Object id) {
        Object namespace = call(namespaceMethod, id);
        Object path = call(pathMethod, id);

        if (namespace instanceof String ns && path instanceof String p) {
            return ns.toLowerCase(Locale.ROOT) + ":" + p.toLowerCase(Locale.ROOT);
        }

        String text = String.valueOf(id);
        return text.contains(":") ? text.toLowerCase(Locale.ROOT) : null;
    }

    /** Все строки из persistent data — и у предмета, и у сущности. */
    private static List<String> stringsOf(PersistentDataContainer data) {
        List<String> values = new ArrayList<>();

        for (NamespacedKey key : data.getKeys()) {
            try {
                String value = data.get(key, PersistentDataType.STRING);

                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            } catch (RuntimeException ignored) {
                // Ключ другого типа — не наше дело.
            }
        }

        return values;
    }

    /** Экземпляр {@code Items} из RP Engine; ищется один раз за запуск. */
    private static synchronized Object itemsApi() {
        if (itemsTried) {
            return itemsApi;
        }

        itemsTried = true;

        try {
            Class<?> itemsClass = Class.forName(ITEMS_CLASS);
            Class<?> idClass = Class.forName(CONTENT_ID_CLASS);

            idOfMethod = itemsClass.getMethod("idOf", ItemStack.class);
            namespaceMethod = idClass.getMethod("namespace");
            pathMethod = idClass.getMethod("path");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError error) {
            return null;
        }

        itemsApi = engineAccessor("items");
        return itemsApi;
    }

    /** Плагин RP Engine и любой его публичный метод без аргументов. */
    private static Object engineAccessor(String method) {
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (!other.getClass().getName().startsWith("ai.resourcepack.engine.")) continue;

            try {
                return other.getClass().getMethod(method).invoke(other);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Не тот класс — смотрим следующий.
            }
        }

        return null;
    }

    /** Есть ли API поставленных моделей. */
    boolean hooked() {
        return hooked;
    }

    /** Подписаться на клик по хитбоксу и найти API RP Engine. */
    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        hooked = resolveApi();

        if (hooked) {
            plugin.getLogger().info("Посуда RP Engine: API найден, налив включён, id " + models);
        } else {
            plugin.getLogger().warning("Посуда RP Engine: API не найден (" + hookProblem
                    + "). Посуда ищется по меткам самого движка; если и это не сработает,"
                    + " покажите вывод /f8 rp.");
        }
    }

    /** Найти классы и экземпляр API. Всё публичное, приватных классов не трогаем. */
    private boolean resolveApi() {
        String problem = null;

        try {
            Class<?> modelsClass = Class.forName(MODELS_CLASS);
            Class<?> placementClass = Class.forName(PLACEMENT_CLASS);

            atMethod = modelsClass.getMethod("at", Entity.class);
            nearMethod = modelsClass.getMethod("near", Location.class, double.class);
            modelIdMethod = placementClass.getMethod("modelId");
            locationMethod = placementClass.getMethod("location");
            removeMethod = placementClass.getMethod("remove", boolean.class);
        } catch (ClassNotFoundException error) {
            problem = "нет класса " + error.getMessage();
        } catch (NoSuchMethodException | LinkageError | RuntimeException error) {
            problem = "не тот API: " + error;
        }

        if (problem == null) {
            modelsApi = engineAccessor("models");

            if (modelsApi == null) {
                problem = "плагин RP Engine не отдал models()";
            }
        }

        hookProblem = problem == null ? "всё на месте" : problem;
        return problem == null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Entity clicked = event.getRightClicked();

        if (clicked == null) {
            return;
        }

        // 1. Сначала метки самого движка: работают без всякого API.
        String id = metadataModelId(clicked);
        Object placement = null;

        // 2. Если метки нет — спрашиваем API.
        if (id == null && hooked) {
            placement = placementOf(clicked);

            if (placement != null) {
                id = modelId(placement);
            }
        }

        if (id == null) {
            return;
        }

        if (!isConfigured(id)) {
            // Строка в консоли — чтобы было видно, как движок называет модель.
            debug(event.getPlayer(), "клик по модели " + id + " — это не посуда из списка "
                    + models);
            return;
        }

        if (placement == null && hooked) {
            placement = placementOf(clicked);
        }

        if (!pour(event.getPlayer(), placement, clicked)) {
            return;
        }

        // Посуда убрана и напиток выдан: остальное по этому клику не нужно.
        event.setCancelled(true);
    }

    /** Поставленная модель, к которой относится эта сущность (через API). */
    private Object placementOf(Entity entity) {
        Object found = call(atMethod, modelsApi, entity);

        if (found instanceof Optional<?> optional && optional.isPresent()) {
            return optional.get();
        }

        String tagged = metadataModelId(entity);

        if (tagged == null) {
            return null;
        }

        Object nearby = call(nearMethod, modelsApi, entity.getLocation(), 0.75D);

        if (!(nearby instanceof List<?> list)) {
            return null;
        }

        for (Object candidate : list) {
            if (tagged.equalsIgnoreCase(modelId(candidate))) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * id модели из меток сущности. RP Engine пишет его строкой на хитбокс
     * ({@code rpengine:model}), поэтому имя ключа не важно — важно, что
     * значение выглядит как «namespace:path».
     */
    static String metadataModelId(Entity entity) {
        if (entity == null) {
            return null;
        }

        for (String value : stringsOf(entity.getPersistentDataContainer())) {
            if (value.indexOf(':') > 0 && !value.contains(",") && !value.contains(" ")) {
                return value;
            }
        }

        return null;
    }

    private String modelId(Object placement) {
        Object id = call(modelIdMethod, placement);
        return id instanceof String text ? text : null;
    }

    /**
     * Перелить напиток из шейкера в посуду. Возвращает false, если наливать
     * нечего (нет шейкера, нет напитка, посуда не убралась) — тогда клик
     * остаётся за RP Engine.
     */
    private boolean pour(Player player, Object placement, Entity hitbox) {
        EquipmentSlot hand = null;
        ItemStack shaker = null;

        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack held = itemInHand(player, slot);

            if (Shaker.isShaker(held)) {
                shaker = held;
                hand = slot;
                break;
            }
        }

        if (shaker == null || hand == null) {
            debug(player, "в руке нет шейкера");
            return false;
        }

        List<ItemStack> contents = Shaker.getContents(shaker);
        int index = drinkIndex(contents);

        if (index < 0) {
            debug(player, "в шейкере нет готового напитка (содержимое: " + contents.size() + ")");
            return false;
        }

        Location at = locationOf(placement, hitbox);

        if (at == null || at.getWorld() == null) {
            debug(player, "не удалось узнать место посуды");
            return false;
        }

        ItemStack drink = contents.get(index).clone();

        // Сначала убираем посуду: если не вышло, напиток остаётся в шейкере.
        if (!removePiece(placement, hitbox)) {
            debug(player, "не удалось убрать посуду");
            return false;
        }

        contents.remove(index);
        Shaker.updateMeta(shaker, contents);
        setItemInHand(player, hand, shaker);

        Location drop = at.clone().add(0.0, 0.15, 0.0);
        drop.getWorld().dropItem(drop, drink);
        drop.getWorld().playSound(drop, Sound.ITEM_BOTTLE_FILL, SoundCategory.PLAYERS, 0.9f, 1.3f);
        drop.getWorld().spawnParticle(Particle.SPLASH, drop, 12, 0.12, 0.08, 0.12, 0.0);

        return true;
    }

    /** Где стоит посуда: у API, иначе у самого хитбокса. */
    private Location locationOf(Object placement, Entity hitbox) {
        Object where = call(locationMethod, placement);

        if (where instanceof Location at) {
            return at;
        }

        return hitbox == null ? null : hitbox.getLocation();
    }

    /**
     * Убрать посуду. Через API — если он есть; иначе своими руками: снимаем
     * отображения, барьер и свет, потом сам хитбокс.
     */
    private boolean removePiece(Object placement, Entity hitbox) {
        if (placement != null && removeMethod != null) {
            Object result = call(removeMethod, placement, false);

            if (result instanceof Boolean done && done) {
                return true;
            }
        }

        return hitbox != null && removeByMetadata(hitbox);
    }

    /**
     * Убрать поставленную модель по её же меткам: UUID отображений перечислены
     * в persistent data хитбокса, барьер и свет стоят в его блоке.
     */
    private static boolean removeByMetadata(Entity hitbox) {
        if (metadataModelId(hitbox) == null) {
            return false;
        }

        Location at = hitbox.getLocation();
        World world = at.getWorld();
        Set<UUID> displays = new LinkedHashSet<>();

        for (String value : stringsOf(hitbox.getPersistentDataContainer())) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();

                try {
                    displays.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                    // Не UUID — значит, и не отображение.
                }
            }
        }

        Block block = at.getBlock();

        if (block.getType() == Material.BARRIER || block.getType() == Material.LIGHT) {
            block.setType(Material.AIR, false);
        }

        boolean removed = hitbox.remove();

        if (world != null) {
            for (UUID id : displays) {
                Entity display = Bukkit.getEntity(id);

                if (display != null) {
                    display.remove();
                }
            }
        }

        return removed;
    }

    /** Первый готовый напиток в шейкере, иначе -1. */
    static int drinkIndex(List<ItemStack> contents) {
        if (contents == null) return -1;

        for (int i = 0; i < contents.size(); i++) {
            if (Shaker.isDrink(contents.get(i))) {
                return i;
            }
        }

        return -1;
    }

    private static ItemStack itemInHand(Player player, EquipmentSlot slot) {
        return slot == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
    }

    private static void setItemInHand(Player player, EquipmentSlot slot, ItemStack item) {
        if (slot == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(item);
        } else {
            player.getInventory().setItemInOffHand(item);
        }
    }

    /**
     * Что видит плагин — для /f8 rp. Строки готовы к отправке игроку.
     */
    List<String> report(Player player) {
        List<String> lines = new ArrayList<>();

        lines.add("§6Посуда RP Engine");
        lines.add("§7id из config.yml: §f" + models);
        lines.add("§7API моделей: §f" + (hooked ? "найден" : "НЕТ — " + hookProblem));
        lines.add("§7API предметов: §f" + (itemsApi() != null ? "найден" : "НЕТ"));

        Plugin engine = null;

        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other.getClass().getName().startsWith("ai.resourcepack.engine.")) {
                engine = other;
                break;
            }
        }

        lines.add("§7плагин движка: §f"
                + (engine == null ? "не найден среди загруженных" : engine.getName()));

        if (player != null) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            lines.add("§7в руке: §f" + hand.getType()
                    + " §7id: §f" + engineItemId(hand)
                    + " §7метка: §f" + storedGlassId(hand));

            int shown = 0;

            for (Entity entity : player.getNearbyEntities(8, 8, 8)) {
                String id = metadataModelId(entity);

                if (id == null) continue;

                lines.add("§7рядом: §f" + entity.getType() + " §7id: §f" + id
                        + (isConfigured(id) ? " §a— наша посуда" : " §c— НЕ из списка"));

                if (++shown >= 6) break;
            }

            if (shown == 0) {
                lines.add("§7рядом: §fпоставленных моделей RP Engine нет (8 блоков)");
            }
        }

        return lines;
    }

    /**
     * Строка в консоль, когда во второй руке держат что-то похожее на посуду,
     * но шейкер этого не узнал. Показывает, что именно видит плагин: тип
     * предмета, id от API и метку из самого предмета.
     */
    static void explain(Plugin plugin, Player player, ItemStack item) {
        long now = System.currentTimeMillis();
        long last = explainedAt.getOrDefault(player.getUniqueId(), 0L);

        if (now - last < DEBUG_INTERVAL_MS) {
            return;
        }

        explainedAt.put(player.getUniqueId(), now);
        plugin.getLogger().info("Шейкер: во второй руке " + item.getType()
                + " (id от API: " + engineItemId(item)
                + ", метка в предмете: " + storedGlassId(item) + ") — это не бутылочка и не посуда из "
                + sharedModels);
    }

    /** Причина отказа — в консоль, но не чаще раза в 3 секунды на игрока. */
    private void debug(Player player, String reason) {
        long now = System.currentTimeMillis();
        long last = debugAt.getOrDefault(player.getUniqueId(), 0L);

        if (now - last < DEBUG_INTERVAL_MS) {
            return;
        }

        debugAt.put(player.getUniqueId(), now);
        plugin.getLogger().info("Посуда: " + reason);
    }

    /**
     * Вызов метода через интерфейс RP Engine. {@code null}, если метода нет
     * или он отказал: одна ошибка не должна ронять обработчик клика.
     */
    private static Object call(Method method, Object target, Object... args) {
        if (method == null || target == null) {
            return null;
        }

        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }
}
