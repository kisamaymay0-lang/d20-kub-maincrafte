package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Рюмка из RP Engine: налить в неё готовый напиток прямо из шейкера.
 *
 * <h2>Что происходит</h2>
 *
 * <p>ПКМ по поставленной рюмке, когда в шейкере есть готовый напиток: рюмка
 * исчезает, а на её месте появляется бутылочка — тот самый напиток. Напиток
 * уходит из шейкера, стекло не возвращается.
 *
 * <h2>Как это связано с RP Engine</h2>
 *
 * <p>Рюмка — это piece RP Engine (у предмета есть {@code place:}), поэтому
 * клик приходит его событием
 * {@code ai.resourcepack.engine.api.event.ModelInteractEvent}. Плагин не
 * зависит от RP Engine при сборке: класс события ищется по имени, а
 * {@code model()}, {@code placement()}, {@code location()} и
 * {@code remove(boolean)} вызываются отражением. Нет RP Engine — нет рюмки,
 * всё остальное работает как раньше.
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

    /** Полное имя класса события RP Engine. */
    static final String INTERACT_EVENT = "ai.resourcepack.engine.api.event.ModelInteractEvent";

    /** Если ключа в config.yml нет: рюмка из пака testpack. */
    static final List<String> DEFAULT_MODELS = List.of("testpack:statue");

    private final Plugin plugin;
    /** id рюмок: «namespace:path». */
    private final Set<String> models = new LinkedHashSet<>();
    /** Зарегистрировали ли событие RP Engine. */
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
            return;
        }
        models.addAll(DEFAULT_MODELS);
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

    /** Есть ли вообще что слушать: без RP Engine событие не регистрируется. */
    boolean hooked() {
        return hooked;
    }

    /** Подписаться на клик по piece RP Engine. Без плагина — тихо пропускаем. */
    void register() {
        Class<?> eventClass;

        try {
            eventClass = Class.forName(INTERACT_EVENT);
        } catch (ClassNotFoundException | LinkageError error) {
            plugin.getLogger().info("RP Engine не найден: наливать в рюмку некуда, "
                    + "шейкер работает как раньше.");
            return;
        }

        try {
            Bukkit.getPluginManager().registerEvent(
                    eventClass.asSubclass(Event.class),
                    this,
                    EventPriority.HIGH,
                    (listener, event) -> onModelInteract(event),
                    plugin
            );
            hooked = true;
        } catch (RuntimeException error) {
            plugin.getLogger().warning("RP Engine нашёлся, но событие клика не подписано: "
                    + error.getMessage());
        }
    }

    private void onModelInteract(Event event) {
        if (!(event instanceof Cancellable cancellable) || cancellable.isCancelled()) {
            return;
        }

        String id = idOf(call(event, "model"));

        if (id == null || !models.contains(id.toLowerCase(Locale.ROOT))) {
            return;
        }

        Object placement = call(event, "placement");

        if (placement == null) {
            return;
        }

        if (!(player(event) instanceof Player player)) {
            return;
        }

        if (pour(player, placement)) {
            // Рюмка уже убрана и напиток выдан: остальное по этому клику не нужно.
            cancellable.setCancelled(true);
        }
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
            return false;
        }

        List<ItemStack> contents = Shaker.getContents(shaker);
        int index = drinkIndex(contents);

        if (index < 0) {
            return false;
        }

        if (!(call(placement, "location") instanceof Location at) || at.getWorld() == null) {
            return false;
        }

        ItemStack drink = contents.get(index).clone();

        // Сначала убираем рюмку: если RP Engine отказал, напиток остаётся в шейкере.
        if (!remove(placement)) {
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

    /** {@code placement.remove(false)} — рюмка исчезает без выпадения предмета. */
    private static boolean remove(Object placement) {
        try {
            Method remove = placement.getClass().getMethod("remove", boolean.class);
            Object result = remove.invoke(placement, false);
            return !(result instanceof Boolean done) || done;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    /** ContentId → «namespace:path». */
    private static String idOf(Object contentId) {
        if (contentId == null) {
            return null;
        }

        Object namespace = call(contentId, "namespace");
        Object path = call(contentId, "path");

        if (namespace instanceof String ns && path instanceof String p) {
            return ns + ":" + p;
        }

        String text = String.valueOf(contentId);
        return text.isEmpty() ? null : text;
    }

    /**
     * Автор клика. В RP Engine это {@code player()} — обычное имя бина, поэтому
     * {@code getPlayer()} не подходит и вернул бы null.
     */
    private static Object player(Event event) {
        return call(event, "player");
    }

    private static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }

        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }
}
