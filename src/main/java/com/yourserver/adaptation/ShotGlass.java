package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Рюмка из RP Engine: налить в неё готовый напиток прямо из шейкера.
 *
 * <h2>Что происходит</h2>
 *
 * <p>ПКМ по поставленной рюмке, когда в шейкере есть готовый напиток: рюмка
 * исчезает, а на её месте появляется бутылочка — тот самый напиток. Напиток
 * уходит из шейкера, стекло не возвращается.
 *
 * <h2>Почему своё событие, а не {@code ModelInteractEvent}</h2>
 *
 * <p>RP Engine сам ловит {@code PlayerInteractAtEntityEvent} и уже из него
 * рассылает {@code ModelInteractEvent}. Слушать первое надёжнее: не зависит от
 * того, дошёл ли внутренний обработчик до рассылки, и работает, даже если у
 * рюмки есть свои действия на клик. Поэтому здесь {@code PlayerInteractAtEntityEvent}
 * на {@link EventPriority#LOWEST}: RP Engine ловит его на {@code NORMAL} и
 * уважает отмену, так что наш отменённый клик до него уже не дойдёт.
 *
 * <h2>Как связаться с RP Engine без сборки против него</h2>
 *
 * <p>{@code ai.resourcepack.engine.api.Models} и
 * {@code ai.resourcepack.engine.api.Placement} — публичные интерфейсы, а
 * экземпляр {@code Models} отдаёт метод {@code models()} самого плагина.
 * Ищем их по имени, поэтому плагин не зависит от RP Engine при сборке: нет
 * движка — нет рюмки, всё остальное работает как раньше.
 *
 * <p>Важно: методы вызываются через <b>интерфейсы</b>, а не через класс
 * объекта. Реализация {@code Placement} внутри RP Engine — приватный класс,
 * и вызов через {@code placement.getClass().getMethod(...)} падает с
 * {@code IllegalAccessException}; именно на этом и стояла первая версия.
 *
 * <p>Идентификаторы рюмок берутся из config.yml, ключ
 * {@code shaker.glass-models}: список «папка:предмет» ровно в том виде, в каком
 * рюмка описана в паке RP Engine ({@code plugins/RPEngine/content/<папка>/items/...}).
 *
 * <h2>Файл рюмки</h2>
 *
 * <p>Готовый и проверенный по FORMAT.md файл лежит в {@code docs/rpengine/shot-glass.yml}.
 */
final class ShotGlass implements Listener {

    /** Публичный интерфейс RP Engine: модели, стоящие в мире. */
    static final String MODELS_CLASS = "ai.resourcepack.engine.api.Models";

    /** Публичный интерфейс RP Engine: одна поставленная модель. */
    static final String PLACEMENT_CLASS = "ai.resourcepack.engine.api.Placement";

    /** Если ключа в config.yml нет: рюмка из пака testpack. */
    static final List<String> DEFAULT_MODELS = List.of("testpack:statue");

    /** Не чаще одной строки в консоль на игрока за этот промежуток. */
    private static final long DEBUG_INTERVAL_MS = 3000L;

    /** Ключ в persistent data, которым RP Engine помечает свой хитбокс. */
    private static final String MODEL_TAG = "model";

    private final Plugin plugin;
    /** id рюмок: «namespace:path». */
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
    /** Есть ли с чем работать. */
    private boolean hooked;

    ShotGlass(Plugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    /** Перечитать список рюмок (/f8 reload и старт). */
    void reloadSettings() {
        models.clear();
        if (plugin.getConfig().isSet("shaker.glass-models")) {
            models.addAll(cleanIds(plugin.getConfig().getStringList("shaker.glass-models")));
        }
        if (models.isEmpty()) {
            models.addAll(DEFAULT_MODELS);
        }
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

    /** Есть ли с чем работать: без API RP Engine наливать некуда. */
    boolean hooked() {
        return hooked;
    }

    /** Подписаться на клик по хитбоксу и найти API RP Engine. */
    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        hooked = resolveApi();

        if (hooked) {
            plugin.getLogger().info("Рюмка RP Engine: налив включён, id " + models);
        } else {
            plugin.getLogger().info("RP Engine не найден: наливать в рюмку некуда, "
                    + "шейкер работает как раньше.");
        }
    }

    /** Найти классы и экземпляр API. Всё публичное, приватных классов не трогаем. */
    private boolean resolveApi() {
        try {
            Class<?> modelsClass = Class.forName(MODELS_CLASS);
            Class<?> placementClass = Class.forName(PLACEMENT_CLASS);

            atMethod = modelsClass.getMethod("at", Entity.class);
            nearMethod = modelsClass.getMethod("near", Location.class, double.class);
            modelIdMethod = placementClass.getMethod("modelId");
            locationMethod = placementClass.getMethod("location");
            removeMethod = placementClass.getMethod("remove", boolean.class);

            modelsApi = modelsInstance();

            return modelsApi != null;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError | RuntimeException error) {
            plugin.getLogger().warning("Рюмка RP Engine: API не подошёл — " + error);
            return false;
        }
    }

    /** Экземпляр {@code Models}: его отдаёт сам плагин RP Engine. */
    private Object modelsInstance() {
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other == plugin) continue;
            if (!other.getClass().getName().startsWith("ai.resourcepack.engine.")) continue;

            try {
                return other.getClass().getMethod("models").invoke(other);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Не тот класс — смотрим следующий.
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (!hooked || models.isEmpty()) {
            return;
        }

        // Клик по хитбоксу модели — это Interaction. Ничего другого не трогаем.
        if (!(event.getRightClicked() instanceof Interaction hitbox)) {
            return;
        }

        Object placement = placementOf(hitbox);

        if (placement == null) {
            return;
        }

        String id = modelId(placement);

        if (id == null || !models.contains(id.toLowerCase(Locale.ROOT))) {
            return;
        }

        if (!pour(event.getPlayer(), placement)) {
            return;
        }

        // Рюмка убрана и напиток выдан: остальное по этому клику не нужно.
        // RP Engine ловит событие на NORMAL и отмену уважает.
        event.setCancelled(true);
    }

    /** Поставленная модель, к которой относится этот хитбокс. */
    private Object placementOf(Entity hitbox) {
        Object placement = call(atMethod, modelsApi, hitbox);

        if (placement instanceof Optional<?> found && found.isPresent()) {
            return found.get();
        }

        // Прямое попадание не вышло: ищем по месту и сверяем id из persistent data.
        String tagged = taggedModelId(hitbox);

        if (tagged == null) {
            return null;
        }

        Object nearby = call(nearMethod, modelsApi, hitbox.getLocation(), 0.75D);

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

    /** id модели из самого хитбокса: RP Engine пишет его в persistent data. */
    private static String taggedModelId(Entity entity) {
        for (NamespacedKey key : entity.getPersistentDataContainer().getKeys()) {
            if (!MODEL_TAG.equals(key.getKey())) continue;

            String value = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);

            if (value != null && value.contains(":")) {
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
     * Перелить напиток из шейкера в рюмку. Возвращает false, если наливать
     * нечего (нет шейкера, нет напитка, рюмка не убралась) — тогда клик
     * остаётся за RP Engine.
     */
    private boolean pour(Player player, Object placement) {
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

        Object where = call(locationMethod, placement);

        if (!(where instanceof Location at) || at.getWorld() == null) {
            debug(player, "не удалось узнать место рюмки");
            return false;
        }

        ItemStack drink = contents.get(index).clone();

        // Сначала убираем рюмку: если RP Engine отказал, напиток остаётся в шейкере.
        if (!remove(placement)) {
            debug(player, "RP Engine отказался убирать рюмку");
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
     * {@code placement.remove(false)} — рюмка исчезает без выпадения предмета.
     * Метод возвращает boolean; если вызвать не удалось — считаем отказом.
     */
    private boolean remove(Object placement) {
        Object result = call(removeMethod, placement, false);
        return result instanceof Boolean done && done;
    }

    /** Причина отказа — в консоль, но не чаще раза в 3 секунды на игрока. */
    private void debug(Player player, String reason) {
        long now = System.currentTimeMillis();
        long last = debugAt.getOrDefault(player.getUniqueId(), 0L);

        if (now - last < DEBUG_INTERVAL_MS) {
            return;
        }

        debugAt.put(player.getUniqueId(), now);
        plugin.getLogger().info("Рюмка: клик был, но налить не вышло — " + reason);
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
