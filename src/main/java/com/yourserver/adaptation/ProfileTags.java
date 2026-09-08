package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Замена ванильного ника над головой на «картинка префикса + ник цветом префикса».
 * Ванильный тег прячется командой на основной таблице (каждая команда — один игрок),
 * а поверх головы игрока держится один TextDisplay: его видно всем, кроме самого владельца
 * (как и ванильный тег). Без префикса ничего не создаётся — игрок остаётся с обычным ником.
 */
final class ProfileTags {
    private static final double TAG_HEIGHT = 2.24; // над ногами, как у ванильного ника
    private static final Color CLEAR = Color.fromARGB(0, 0, 0, 0);
    private static final Display.Brightness LIGHT = new Display.Brightness(15, 15);

    private final JavaPlugin plugin;
    private final Function<Player, PrefixCatalog.Prefix> equipped;
    private final Map<UUID, Entry> tags = new HashMap<>();
    private final BukkitTask task;
    private boolean disabled;

    private static final class Entry {
        TextDisplay display;
        Team team;
        String entryName;
    }

    ProfileTags(JavaPlugin plugin, Function<Player, PrefixCatalog.Prefix> equipped) {
        this.plugin = plugin;
        this.equipped = equipped;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    /** Обновить тег после смены/снятия префикса; null убирает замену полностью. */
    void apply(Player player, PrefixCatalog.Prefix prefix) {
        if (disabled || player == null) return;
        UUID id = player.getUniqueId();
        if (prefix == null) {
            remove(id);
            return;
        }
        try {
            Entry entry = tags.get(id);
            if (entry == null) {
                entry = new Entry();
                tags.put(id, entry);
            }
            ensureTeam(entry, player);
            TextDisplay display = entry.display;
            if (display == null || !display.isValid() || !display.getWorld().equals(player.getWorld())) {
                removeDisplay(entry);
                display = spawn(player);
                entry.display = display;
                player.hideEntity(plugin, display); // Свой ник над головой, как и в ваниле, не виден владельцу.
            }
            Component line = ProfileIcons.prefixedName(prefix, player.getName());
            if (!line.equals(display.text())) display.text(line);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось показать префикс над головой " + player.getName(), ex);
            remove(id);
        }
    }

    void quit(Player player) { remove(player.getUniqueId()); }

    void disable() {
        disabled = true;
        task.cancel();
        for (UUID id : new java.util.ArrayList<>(tags.keySet())) remove(id);
    }

    private void tick() {
        if (tags.isEmpty()) return;
        for (Map.Entry<UUID, Entry> entry : new java.util.ArrayList<>(tags.entrySet())) {
            Entry tag = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                remove(entry.getKey());
                continue;
            }
            try {
                if (tag.display == null || !tag.display.isValid()) {
                    // Тега нет: создаём для живого видимого игрока; иначе просто ждём.
                    if (!player.isDead() && !player.isInvisible()) apply(player, equipped.apply(player));
                    continue;
                }
                if (!player.getWorld().equals(tag.display.getWorld())) {
                    // Переход между мирами: тег переносим в новый мир.
                    apply(player, equipped.apply(player));
                    continue;
                }
                if (player.isDead() || player.isInvisible()) {
                    // Мёртвым/невидимым ванильный ник и так не показывается: убираем и тег.
                    removeDisplay(tag);
                    continue;
                }
                Location at = player.getLocation();
                at.setYaw(0); at.setPitch(0);
                at.add(0, TAG_HEIGHT, 0);
                if (tag.display.getLocation().distanceSquared(at) > 0.000001) tag.display.teleport(at);
            } catch (RuntimeException ex) {
                remove(entry.getKey());
            }
        }
    }

    private void ensureTeam(Entry entry, Player player) {
        Team team = entry.team;
        if (team != null && team.isRegistered() && entry.entryName != null) {
            if (!team.hasEntry(entry.entryName)) team.addEntry(entry.entryName);
            return;
        }
        if (team != null) team = null;
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String uuidHex = player.getUniqueId().toString().replace("-", "");
            String teamName = "f8p" + uuidHex.substring(0, 12); // ≤16 символов
            team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            String name = player.getName();
            if (!team.hasEntry(name)) team.addEntry(name);
            entry.team = team;
            entry.entryName = name;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось скрыть ванильный ник " + player.getName(), ex);
        }
    }

    private TextDisplay spawn(Player player) {
        Location at = player.getLocation();
        at.setYaw(0); at.setPitch(0);
        at.add(0, TAG_HEIGHT, 0);
        return player.getWorld().spawn(at, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(true);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setBackgroundColor(CLEAR);
            display.setTextOpacity((byte) 255);
            display.setLineWidth(200);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setTeleportDuration(2);
            display.setInterpolationDuration(0);
            display.setShadowRadius(0);
            display.setShadowStrength(0);
            display.setBrightness(LIGHT);
            display.setInvulnerable(true);
            display.setSilent(true);
        });
    }

    private static void removeDisplay(Entry entry) {
        if (entry.display != null && entry.display.isValid()) entry.display.remove();
        entry.display = null;
    }

    private void remove(UUID id) {
        Entry entry = tags.remove(id);
        if (entry == null) return;
        try {
            removeDisplay(entry);
            if (entry.team != null && entry.team.isRegistered()) {
                if (entry.entryName != null) entry.team.removeEntry(entry.entryName);
                entry.team.unregisterTeam();
            }
        } catch (RuntimeException ignored) { }
    }
}
