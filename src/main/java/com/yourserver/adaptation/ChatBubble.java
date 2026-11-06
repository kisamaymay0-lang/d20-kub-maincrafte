package com.yourserver.adaptation;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Надпись над головой игрока с его же сообщением из чата.
 *
 * <p>Выглядит и ведёт себя как реплики питомцев: тот же масштаб, тень и
 * посимвольное появление. Отличие одно — звука нет. Надпись держится три
 * секунды и следует за игроком, а если над головой висит корона, поднимается
 * выше неё, чтобы тексты не перекрывались.</p>
 */
final class ChatBubble implements Listener {
    private static final int LIFETIME_TICKS = 60;
    /** Обычная высота: сразу над макушкой. */
    private static final double HEIGHT_ABOVE_HEAD = 0.35D;
    /** Когда над головой корона, надпись уходит выше её реплики. */
    private static final double HEIGHT_ABOVE_CROWN = 1.35D;
    /** Слишком длинное сообщение не должно растягиваться на пол-экрана. */
    private static final int MAX_LENGTH = 180;
    private static final int MIN_INTERPOLATION_TICKS = 3;
    private static final int MAX_INTERPOLATION_TICKS = 8;

    private final AdaptationPlugin plugin;
    private final CrownPet crownPet;
    private final NamespacedKey markerKey;
    private final Map<UUID, SpeechText> bubbles = new HashMap<>();

    ChatBubble(AdaptationPlugin plugin, CrownPet crownPet) {
        this.plugin = plugin;
        this.crownPet = crownPet;
        this.markerKey = new NamespacedKey(plugin, "chat_bubble_text");
    }

    /** Один серверный тик: надписи допечатываются и едут за игроками. */
    void tick() {
        for (UUID uuid : new ArrayList<>(bubbles.keySet())) {
            SpeechText speech = bubbles.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (speech == null || player == null || !player.isOnline()) {
                remove(uuid);
                continue;
            }
            if (!speech.tick(anchor(player, crownsPlayer(uuid)))) {
                remove(uuid);
            }
        }
    }

    private boolean crownsPlayer(UUID uuid) {
        return crownPet != null && crownPet.isActive(uuid);
    }

    private static Location anchor(Player player, boolean hasCrown) {
        Location at = player.getLocation().clone();
        at.setY(player.getBoundingBox().getMaxY()
                + (hasCrown ? HEIGHT_ABOVE_CROWN : HEIGHT_ABOVE_HEAD));
        at.setPitch(0.0f);
        return at;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.isEmpty()) {
            return;
        }
        String text = message.length() > MAX_LENGTH ? message.substring(0, MAX_LENGTH) + "…" : message;
        try {
            // Сущности и их состояние трогаем только на основном серверном потоке.
            Bukkit.getScheduler().runTask(plugin, () -> show(uuid, text));
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // Плагин уже выключается.
        }
    }

    private void show(UUID uuid, String text) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        remove(uuid);
        SpeechText speech = SpeechText.spawn(anchor(player, crownsPlayer(uuid)), text, LIFETIME_TICKS,
                clientInterpolationTicks(player), markerKey);
        bubbles.put(uuid, speech);
    }

    private static int clientInterpolationTicks(Player player) {
        int ping = Math.max(0, player.getPing());
        return Math.min(MAX_INTERPOLATION_TICKS, MIN_INTERPOLATION_TICKS + ping / 150);
    }

    private void remove(UUID uuid) {
        SpeechText speech = bubbles.remove(uuid);
        if (speech != null) {
            speech.remove();
        }
    }

    /** Убрать все надписи: выключение плагина или ручная очистка. */
    int clearAll() {
        int removed = bubbles.size();
        for (UUID uuid : new ArrayList<>(bubbles.keySet())) {
            remove(uuid);
        }
        return removed;
    }

    public void stopAll() {
        clearAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }
}
