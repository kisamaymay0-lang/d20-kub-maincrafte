package com.yourserver.adaptation;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Тотем медали»: при получении медали перед глазами игрока на полторы секунды
 * всплывает изображение ИМЕННО этой медали — её модель из ресурспака (медная,
 * серебряная или золотая), а не стандартный тотем.
 *
 * <p>Как это устроено: настоящую анимацию тотема клиент показывает только со своей
 * картинкой тотема, поменять её на лету нельзя. Поэтому медаль показывается как
 * {@link ItemDisplay} прямо перед лицом игрока: она всплывает, крутится, пускает
 * искры и растворяется. Сущность видит только получатель: она скрыта по умолчанию
 * и выдаётся через {@code showEntity}.
 *
 * <p>В мире ничего не остаётся: сущность не сохраняется на диск и удаляется через
 * {@link #TICKS} тиков, на выключении плагина — {@link #disable()}.
 */
final class MedalAnimation {

    /** Сколько тиков живёт картинка: 1.5 секунды. */
    static final int TICKS = 30;

    private final Plugin plugin;
    private final Map<UUID, ItemDisplay> active = new ConcurrentHashMap<>();

    MedalAnimation(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Размер картинки по кадру: ноль в начале и в конце, максимум в середине. */
    static float scaleAt(int tick) {
        if (tick < 0 || tick > TICKS) return 0f;
        double progress = tick / (double) TICKS;
        return (float) (1.45 * Math.sin(Math.PI * progress));
    }

    /** Показать медаль игроку. Ошибки анимации не должны ломать выдачу медали. */
    void play(Player player, ItemStack picture) {
        if (player == null || picture == null || plugin == null || !plugin.isEnabled()) return;
        stop(player);
        Location start = start(player);
        ItemDisplay display;
        try {
            display = player.getWorld().spawn(start, ItemDisplay.class, entity -> {
                entity.setItemStack(picture);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setSilent(true);
                // Картинку видит только получатель медали.
                entity.setVisibleByDefault(false);
                entity.setTransformation(transform(0.01f, 0f));
            });
            player.showEntity(plugin, display);
        } catch (RuntimeException ex) {
            return; // сервер без display-сущностей: медаль выдаётся без анимации
        }
        active.put(player.getUniqueId(), display);

        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, start, 45, 0.35, 0.35, 0.35, 0.15);
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.9f, 1.15f);

        new BukkitRunnable() {
            private int tick;
            private boolean done;

            @Override
            public void run() {
                if (done) return;
                if (!player.isOnline() || !display.isValid()) {
                    finish();
                    return;
                }
                // Картинка едет за лицом игрока: смотреть на неё не надо, она сама перед глазами.
                display.teleport(start(player));
                display.setTransformation(transform(scaleAt(tick), tick / (float) TICKS));
                if (tick % 5 == 0) {
                    player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, display.getLocation(),
                            3, 0.25, 0.25, 0.25, 0.02);
                }
                tick++;
                if (tick > TICKS) finish();
            }

            private void finish() {
                done = true;
                active.remove(player.getUniqueId(), display);
                if (display.isValid()) display.remove();
                cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Убрать картинку досрочно (например, игрок вышел). */
    void stop(Player player) {
        if (player == null) return;
        ItemDisplay display = active.remove(player.getUniqueId());
        if (display != null && display.isValid()) display.remove();
    }

    /** Выключение плагина: ничего не оставляем в мире. */
    void disable() {
        for (ItemDisplay display : active.values()) {
            if (display.isValid()) display.remove();
        }
        active.clear();
    }

    private static Location start(Player player) {
        Location eyes = player.getEyeLocation();
        return eyes.add(eyes.getDirection().multiply(1.35)).add(0, -0.18, 0);
    }

    /** Масштаб и поворот вокруг вертикали: картинка покачивается, как тотем в руке. */
    private static Transformation transform(float scale, float spin) {
        float size = Math.max(0.0001f, scale);
        return new Transformation(new Vector3f(), new Quaternionf().rotateY((float) (Math.PI * 2.0 * spin)),
                new Vector3f(size, size, size), new Quaternionf());
    }
}
