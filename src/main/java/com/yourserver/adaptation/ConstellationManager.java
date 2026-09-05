package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.joml.Matrix4f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Система созвездий.
 *
 * Как это работает:
 *  - Каждая звезда — направление на небесной сфере (азимут + высота), оно
 *    одинаково для всех игроков.
 *  - Для отображения на каждого игрока создаются display-сущности (ItemDisplay),
 *    привязанные к координатам глаз игрока, но НЕ к его yaw/pitch.
 *  - Billboard.FIXED и нейтральный поворот сущности сохраняют мировое
 *    направление. Картинка отдельно развёрнута лицом к наблюдателю.
 *    Поворот камеры никогда не поворачивает смещение звезды.
 *  - Сущности скрыты от всех, кроме владельца (setVisibleByDefault(false) +
 *    showEntity) — игроки не видят звёзды друг друга.
 *  - Линии — плоские незатенённые ItemDisplay-полосы за сферой звёзд.
 *    У превью та же геометрия, но пунктирная текстура; наведение не зависит
 *    от принадлежности звёзд к созвездиям.
 *
 * Админ создаёт созвездия файлами в папке plugins/<плагин>/constellations/*.yml
 * или командами (/stars add — по направлению взгляда, /stars edge, /stars pin).
 */
public class ConstellationManager implements Listener, CommandExecutor {

    private static final String DEFAULT_STAR_MODEL = "f8resurs:star";
    private static final long STAR_ANIMATION_TICKS = 2L;

    private final JavaPlugin plugin;
    private final File constellationsDir;
    private final File playerDataFile;
    private YamlConfiguration playerData;

    private final Map<String, Constellation> constellations =
            new LinkedHashMap<>();

    private BukkitTask renderTask;
    private BukkitTask animationTask;
    private double starSpinRadians;
    private boolean renderErrorReported;

    // Пер-игровое состояние отрисовки
    private static class PlayerView {

        final Map<String, ItemDisplay> stars = new LinkedHashMap<>();
        final Map<String, ItemDisplay> lines = new LinkedHashMap<>();
        ItemDisplay preview = null;
        String previewTargetKey = null;
        String selectedKey = null;

        // Следует за координатами глаз, но всегда имеет yaw=pitch=0.
        // Направления звёзд относительно игрока постоянны всю ночь.
        Location anchor = null;
    }

    private final Map<UUID, PlayerView> views = new HashMap<>();

    // Прогресс игрока
    private static class PlayerProgress {

        final Set<String> drawn = new HashSet<>();
        final Set<String> completed = new HashSet<>();
    }

    private final Map<UUID, PlayerProgress> progress = new HashMap<>();

    public ConstellationManager(JavaPlugin plugin) {
        this.plugin = plugin;

        this.constellationsDir =
                new File(plugin.getDataFolder(), "constellations");

        if (!constellationsDir.exists()) {
            constellationsDir.mkdirs();
        }

        this.playerDataFile =
                new File(plugin.getDataFolder(), "playerdata.yml");

        this.playerData =
                YamlConfiguration.loadConfiguration(playerDataFile);

        createExampleFile();
        loadConstellations();
        loadProgress();

        startRenderTask();
    }

    // ===== КОНФИГ =====

    private void startRenderTask() {
        if (renderTask != null) {
            renderTask.cancel();
        }
        long interval = Math.clamp(plugin.getConfig().getLong(
                "constellations.update-interval-ticks", 10L
        ), 1L, 200L);
        renderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderTick, 1L, interval);
        if (animationTask != null) {
            animationTask.cancel();
        }
        animationTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::animateStars, STAR_ANIMATION_TICKS, STAR_ANIMATION_TICKS
        );
    }

    private double positiveSetting(String key, double fallback) {
        double value = plugin.getConfig().getDouble("constellations." + key, fallback);
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private double renderDistance() {
        return Math.clamp(positiveSetting("render-distance", 80.0), 4.0, 256.0);
    }

    private float starScale() {
        return (float) Math.min(32.0, positiveSetting("star-scale", 2.0));
    }

    private float lineThickness() {
        return (float) Math.min(16.0, positiveSetting("line-thickness", 0.4));
    }

    private double clickToleranceDeg() {
        return Math.min(45.0, positiveSetting("click-tolerance-degrees", 2.0));
    }

    private double starRotationSpeed() {
        double speed = plugin.getConfig().getDouble("constellations.star-rotation-degrees-per-second", 4.0);
        return Double.isFinite(speed) ? Math.clamp(speed, -90.0, 90.0) : 4.0;
    }

    // ===== ЗАГРУЗКА =====

    private void createExampleFile() {
        File ex = new File(constellationsDir, "example.yml");

        if (ex.exists()) {
            return;
        }

        YamlConfiguration y = new YamlConfiguration();

        y.set("name", "Пример созвездия");
        y.set("pinned", false);
        y.set("star-model", DEFAULT_STAR_MODEL);

        y.set("stars.Alpha", List.of(30.0, 60.0));
        y.set("stars.Beta", List.of(60.0, 55.0));
        y.set("stars.Gamma", List.of(45.0, 40.0));

        y.set("edges", List.of(
                List.of("Alpha", "Beta"),
                List.of("Beta", "Gamma"),
                List.of("Gamma", "Alpha")
        ));

        y.set("reward.title", "Вы собрали созвездие!");
        y.set("reward.subtitle", "Это пример награды");
        y.set("reward.commands", List.of("give {player} diamond 1"));

        try {
            y.save(ex);
        } catch (IOException ignored) {
        }
    }

    private void loadConstellations() {
        constellations.clear();

        File[] files = constellationsDir.listFiles(
                (d, n) -> n.endsWith(".yml")
        );

        if (files == null) {
            return;
        }

        for (File f : files) {
            String id = f.getName().substring(
                    0,
                    f.getName().length() - 4
            );

            YamlConfiguration y =
                    YamlConfiguration.loadConfiguration(f);

            Constellation c = new Constellation(id);
            c.name = y.getString("name", id);
            c.pinned = y.getBoolean("pinned", false);
            c.starModel = y.getString(
                    "star-model",
                    DEFAULT_STAR_MODEL
            );

            ConfigurationSection starsSec =
                    y.getConfigurationSection("stars");

            if (starsSec != null) {
                for (String sid : starsSec.getKeys(false)) {
                    double az = 0;
                    double el = 45;

                    Object raw = starsSec.get(sid);

                    if (raw instanceof List<?> l && l.size() >= 2) {
                        az = ((Number) l.get(0)).doubleValue();
                        el = ((Number) l.get(1)).doubleValue();
                    } else if (raw instanceof String s) {
                        String[] parts = s.split("[, ]+");
                        if (parts.length >= 2) {
                            try {
                                az = Double.parseDouble(parts[0]);
                                el = Double.parseDouble(parts[1]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    c.stars.put(
                            sid,
                            new Constellation.StarDef(sid, az, el)
                    );
                }
            }

            List<?> edgesList = y.getList("edges");

            if (edgesList != null) {
                for (Object o : edgesList) {
                    if (o instanceof List<?> pair && pair.size() >= 2) {
                        String a = String.valueOf(pair.get(0));
                        String b = String.valueOf(pair.get(1));

                        if (c.stars.containsKey(a)
                                && c.stars.containsKey(b)) {

                            c.edges.add(
                                    new Constellation.EdgeDef(a, b)
                            );
                        }
                    }
                }
            }

            ConfigurationSection rewardSec =
                    y.getConfigurationSection("reward");

            if (rewardSec != null) {
                c.reward.title = rewardSec.getString("title", "");
                c.reward.subtitle = rewardSec.getString("subtitle", "");
                c.reward.commands.addAll(
                        rewardSec.getStringList("commands")
                );
                c.reward.item = parseItem(
                        rewardSec.getConfigurationSection("item")
                );
            }

            constellations.put(id, c);
        }
    }

    private ItemStack parseItem(ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }

        String matName = sec.getString("material");
        Material mat = Material.getMaterial(
                matName == null ? "" : matName.toUpperCase()
        );

        if (mat == null) {
            return null;
        }

        int amount = Math.max(
                1,
                Math.min(64, sec.getInt("amount", 1))
        );

        ItemStack is = new ItemStack(mat, amount);
        ItemMeta im = is.getItemMeta();

        if (im != null) {
            if (sec.contains("name")) {
                im.setDisplayName(color(sec.getString("name")));
            }

            if (sec.contains("lore")) {
                List<String> lore = sec.getStringList("lore");
                List<String> colored = new ArrayList<>();

                for (String l : lore) {
                    colored.add(color(l));
                }

                im.setLore(colored);
            }

            if (sec.contains("model")) {
                NamespacedKey k = NamespacedKey.fromString(
                        sec.getString("model")
                );

                if (k != null) {
                    im.setItemModel(k);
                }
            }

            is.setItemMeta(im);
        }

        return is;
    }

    private String color(String s) {
        if (s == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ===== ПРОГРЕСС ИГРОКОВ =====

    private void loadProgress() {
        progress.clear();

        ConfigurationSection playersSec =
                playerData.getConfigurationSection("players");

        if (playersSec == null) {
            return;
        }

        for (String uuidStr : playersSec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection sec =
                        playersSec.getConfigurationSection(uuidStr);

                PlayerProgress pp = new PlayerProgress();

                if (sec != null) {
                    pp.drawn.addAll(sec.getStringList("edges"));
                    pp.completed.addAll(
                            sec.getStringList("completed")
                    );
                }

                progress.put(uuid, pp);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveProgress() {
        for (Map.Entry<UUID, PlayerProgress> e :
                progress.entrySet()) {

            String path = "players." + e.getKey();

            playerData.set(
                    path + ".edges",
                    new ArrayList<>(e.getValue().drawn)
            );
            playerData.set(
                    path + ".completed",
                    new ArrayList<>(e.getValue().completed)
            );
        }

        try {
            playerData.save(playerDataFile);
        } catch (IOException ignored) {
        }
    }

    // ===== ЦИКЛ ОТРИСОВКИ =====

    private void renderTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!shouldRender(p)) {
                hideAll(p);
                continue;
            }

            updateAnchor(p);
            ensureStars(p);
            updateLines(p);
            updatePreview(p);
        }
    }

    private void animateStars() {
        double speed = starRotationSpeed();
        starSpinRadians = Math.IEEEremainder(
                starSpinRadians + Math.toRadians(speed) * STAR_ANIMATION_TICKS / 20.0,
                Math.PI * 2.0
        );
        float radius = (float) renderDistance();
        float scale = starScale();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerView view = views.get(player.getUniqueId());
            if (view == null) {
                continue;
            }
            if (!shouldRender(player)) {
                hideAll(player);
                continue;
            }
            if (speed == 0.0) {
                continue;
            }
            for (var entry : view.stars.entrySet()) {
                ItemDisplay display = entry.getValue();
                Constellation constellation = constellationOf(entry.getKey());
                Constellation.StarDef star = constellation == null
                        ? null : constellation.stars.get(starIdOf(entry.getKey()));
                if (!display.isValid() || star == null) {
                    continue;
                }
                // Клиент интерполирует поворот между редкими обновлениями.
                // Translation и положение сущности при анимации не меняются.
                display.setInterpolationDuration((int) STAR_ANIMATION_TICKS);
                display.setInterpolationDelay(0);
                display.setTransformation(SkyGeometry.starTransform(
                        star.direction(), radius, scale, (float) starSpinRadians
                ));
            }
        }
    }

    private boolean shouldRender(Player p) {
        if (p.getWorld().getEnvironment()
                != World.Environment.NORMAL) {
            return false;
        }

        return !p.isDead() && SkyGeometry.isNight(p.getWorld().getTime()) && hasPinned();
    }

    private boolean hasPinned() {
        for (Constellation c : constellations.values()) {
            if (c.pinned) {
                return true;
            }
        }
        return false;
    }

    // ===== ЗВЁЗДЫ =====

    private ItemStack starItem(Constellation c) {
        ItemStack is = new ItemStack(Material.PAPER);
        ItemMeta im = is.getItemMeta();

        if (im != null) {
            NamespacedKey k = NamespacedKey.fromString(c.starModel);

            if (k == null) {
                k = new NamespacedKey("f8resurs", "star");
            }

            im.setItemModel(k);
            is.setItemMeta(im);
        }

        return is;
    }

    private Vector3f scaled(Vector3f dir) {
        double r = renderDistance();
        return new Vector3f(
                (float) (dir.x * r),
                (float) (dir.y * r),
                (float) (dir.z * r)
        );
    }

    private void ensureStars(Player p) {
        PlayerView v = views.computeIfAbsent(
                p.getUniqueId(),
                k -> new PlayerView()
        );

        Location eye = p.getEyeLocation();

        if (v.anchor == null) {
            v.anchor = SkyGeometry.anchor(eye);
        }

        Location spawnAt = v.anchor;

        for (Constellation c : constellations.values()) {
            if (!c.pinned) {
                continue;
            }

            for (Constellation.StarDef s : c.stars.values()) {
                String key = c.id + ":" + s.id;

                ItemDisplay existing = v.stars.get(key);

                if (existing != null && existing.isValid()) {
                    continue;
                }

                // Звезда могла быть выгружена (например, вышла за радиус
                // отслеживания) — пересоздаём её.
                v.stars.remove(key);

                try {
                    ItemDisplay d = p.getWorld().spawn(
                            spawnAt,
                            ItemDisplay.class,
                            e -> {
                                e.setItemStack(starItem(c));
                                // CENTER поворачивал ещё и translation, уводя
                                // звезду за камерой. Поворачиваем только модель.
                                e.setBillboard(Display.Billboard.FIXED);
                                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                                e.setTransformation(SkyGeometry.starTransform(
                                        s.direction(), (float) renderDistance(), starScale(), (float) starSpinRadians
                                ));
                                e.setTeleportDuration(0);
                                e.setInterpolationDuration(0);
                                e.setShadowRadius(0f);
                                e.setShadowStrength(0f);
                                e.setBrightness(
                                        new Display.Brightness(15, 15)
                                );
                                e.setViewRange(160f);
                                e.setPersistent(false);
                                e.setVisibleByDefault(false);
                            }
                    );

                    p.showEntity(plugin, d);
                    v.stars.put(key, d);
                } catch (Exception ex) {
                    reportRenderError(ex);
                }
            }
        }
    }

    private void updateAnchor(Player p) {
        updateAnchor(p, p.getEyeLocation());
    }

    private void updateAnchor(Player p, Location eye) {
        PlayerView v = views.get(p.getUniqueId());
        if (v == null) {
            return;
        }
        Location anchor = SkyGeometry.anchor(eye);
        if (v.anchor != null && !v.anchor.getWorld().equals(anchor.getWorld())) {
            hideAll(p);
            return;
        }
        if (v.anchor != null && v.anchor.distanceSquared(anchor) < 1e-10) {
            return; // Только поворот головы — никаких телепортаций/поворотов неба.
        }
        v.anchor = anchor;
        for (ItemDisplay d : v.stars.values()) {
            if (d.isValid()) {
                d.teleport(anchor);
            }
        }
        for (ItemDisplay d : v.lines.values()) {
            if (d.isValid()) {
                d.teleport(anchor);
            }
        }
        if (v.preview != null && v.preview.isValid()) {
            v.preview.teleport(anchor);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        followMovement(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        followMovement(event);
    }

    private void followMovement(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !views.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        if (!shouldRender(event.getPlayer()) || !event.getFrom().getWorld().equals(to.getWorld())) {
            hideAll(event.getPlayer());
            return;
        }
        // PlayerMoveEvent вызывается ДО обновления координат Player:
        // используем to, а не старый getEyeLocation().
        updateAnchor(event.getPlayer(), to.clone().add(0, event.getPlayer().getEyeHeight(), 0));
    }

    private void reportRenderError(Exception ex) {
        if (!renderErrorReported) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Не удалось отрисовать созвездия (повторные ошибки скрыты до /stars reload)", ex);
            renderErrorReported = true;
        }
    }

    // ===== ЛИНИИ =====

    private Vector3f[] edgeEndpoints(
            Constellation c,
            String edgeKey
    ) {
        String[] ids = edgeKey.split("\\|");

        if (ids.length != 2) {
            return null;
        }

        Constellation.StarDef a = c.stars.get(ids[0]);
        Constellation.StarDef b = c.stars.get(ids[1]);

        if (a == null || b == null) {
            return null;
        }

        return new Vector3f[]{
                scaled(a.direction()),
                scaled(b.direction())
        };
    }

    private Matrix4f beamTransform(Vector3f a, Vector3f b) {
        // В старой настройке line-thickness хранился масштаб END_ROD,
        // стержень которого имеет ширину 2/16. Сохраняем привычную толщину,
        // убирая основание, боковые грани и тени самого END_ROD.
        return SkyGeometry.beamTransform(a, b, lineThickness() / 8f, starScale() * 2f);
    }

    private ItemStack beamItem(boolean preview) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setItemModel(new NamespacedKey("f8resurs", preview ? "star_beam_preview" : "star_beam"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemDisplay spawnLine(Player p, Location spawnAt, Matrix4f transform, boolean preview) {
        if (transform == null) {
            return null;
        }
        try {
            ItemDisplay display = p.getWorld().spawn(
                    SkyGeometry.anchor(spawnAt), ItemDisplay.class, e -> {
                        e.setItemStack(beamItem(preview));
                        e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                        e.setBillboard(Display.Billboard.FIXED);
                        e.setTeleportDuration(0);
                        e.setInterpolationDuration(0);
                        e.setShadowRadius(0f);
                        e.setShadowStrength(0f);
                        e.setBrightness(new Display.Brightness(15, 15));
                        e.setViewRange(160f);
                        e.setPersistent(false);
                        e.setVisibleByDefault(false);
                        e.setTransformationMatrix(transform);
                    }
            );
            p.showEntity(plugin, display);
            return display;
        } catch (Exception ex) {
            reportRenderError(ex);
            return null;
        }
    }

    private void updateLines(Player p) {
        PlayerView v = views.get(p.getUniqueId());

        if (v == null) {
            return;
        }

        PlayerProgress pp = progress.get(p.getUniqueId());

        if (pp != null) {
            for (String drawnKey : new ArrayList<>(pp.drawn)) {
                int idx = drawnKey.indexOf(':');

                if (idx <= 0) {
                    continue;
                }

                String cid = drawnKey.substring(0, idx);
                String edgeKey = drawnKey.substring(idx + 1);

                Constellation c = constellations.get(cid);

                if (c == null || !c.pinned) {
                    continue;
                }

                Vector3f[] ends = edgeEndpoints(c, edgeKey);

                if (ends == null) {
                    continue;
                }

                ItemDisplay line = v.lines.get(drawnKey);

                if (line == null || !line.isValid()) {
                    line = spawnLine(
                            p,
                            v.anchor != null
                                    ? v.anchor
                                    : p.getEyeLocation(),
                            beamTransform(ends[0], ends[1]),
                            false
                    );

                    if (line == null) {
                        continue;
                    }

                    v.lines.put(drawnKey, line);
                }
                // Геометрия завершённой линии постоянна до /stars reload;
                // при ходьбе переносится только общая точка привязки.

            }
        }

        Iterator<Map.Entry<String, ItemDisplay>> it =
                v.lines.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, ItemDisplay> en = it.next();

            boolean keep = pp != null
                    && pp.drawn.contains(en.getKey());

            if (!keep) {
                if (en.getValue().isValid()) {
                    en.getValue().remove();
                }
                it.remove();
            }
        }
    }

    // ===== ПРЕВЬЮ-ЛИНИЯ =====

    private void updatePreview(Player p) {
        PlayerView v = views.get(p.getUniqueId());
        if (v == null) {
            return;
        }
        if (v.selectedKey == null) {
            removePreview(p, v);
            return;
        }
        Constellation source = constellationOf(v.selectedKey);
        String sourceId = starIdOf(v.selectedKey);
        if (source == null || !source.pinned || sourceId == null || !source.stars.containsKey(sourceId)) {
            v.selectedKey = null;
            removePreview(p, v);
            return;
        }

        // Не ограничиваем наведение исходным созвездием или списком рёбер.
        // Иначе наличие/отсутствие превью заранее выдаёт ответ игроку.
        String hoverKey = findAimedStar(p, v.selectedKey);
        if (hoverKey == null) {
            removePreview(p, v);
            return;
        }
        Constellation target = constellationOf(hoverKey);
        String targetId = starIdOf(hoverKey);
        if (target == null || targetId == null || !target.stars.containsKey(targetId)) {
            removePreview(p, v);
            return;
        }
        if (hoverKey.equals(v.previewTargetKey) && v.preview != null && v.preview.isValid()) {
            return;
        }
        Matrix4f transform = beamTransform(
                scaled(source.stars.get(sourceId).direction()),
                scaled(target.stars.get(targetId).direction())
        );
        if (transform == null) {
            removePreview(p, v);
            return;
        }
        if (v.preview == null || !v.preview.isValid()) {
            v.preview = spawnLine(p, v.anchor != null ? v.anchor : p.getEyeLocation(), transform, true);
        } else {
            v.preview.setTransformationMatrix(transform);
        }
        v.previewTargetKey = hoverKey;
    }

    private void removePreview(Player p, PlayerView v) {
        if (v.preview != null) {
            if (v.preview.isValid()) {
                v.preview.remove();
            }
            v.preview = null;
        }
        v.previewTargetKey = null;
    }

    private void hideAll(Player p) {
        PlayerView v = views.remove(p.getUniqueId());

        if (v == null) {
            return;
        }

        for (ItemDisplay d : v.stars.values()) {
            if (d.isValid()) {
                d.remove();
            }
        }

        for (ItemDisplay d : v.lines.values()) {
            if (d.isValid()) {
                d.remove();
            }
        }

        if (v.preview != null && v.preview.isValid()) {
            v.preview.remove();
        }
    }

    // ===== ВЗАИМОДЕЙСТВИЕ ИГРОКА =====

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player p = event.getPlayer();

        if (p.getInventory().getItemInMainHand().getType()
                != Material.SPYGLASS) {
            return;
        }

        if (!shouldRender(p)) {
            return;
        }

        // Прицеливание использует ту же точку привязки, что и картинка.
        updateAnchor(p);
        ensureStars(p);
        String hitKey = findAimedStar(p, null);

        PlayerView v = views.computeIfAbsent(
                p.getUniqueId(),
                k -> new PlayerView()
        );

        if (hitKey == null) {
            // Обычная подзорная труба (например, в шахте) не должна спамить
            // actionbar и затирать компас/HUD других плагинов. Даже сброс
            // незавершённого выбора при промахе происходит совершенно тихо.
            v.selectedKey = null;
            removePreview(p, v);
            return;
        }

        if (v.selectedKey == null) {
            v.selectedKey = hitKey;
            action(p, "§eЗвезда выбрана.");
            playSound(p, Sound.UI_BUTTON_CLICK);
            return;
        }

        if (v.selectedKey.equals(hitKey)) {
            v.selectedKey = null;
            removePreview(p, v);
            action(p, "§7Выбор сброшен.");
            playSound(p, Sound.UI_BUTTON_CLICK);
            return;
        }

        Constellation sc = constellationOf(v.selectedKey);
        Constellation hc = constellationOf(hitKey);

        if (sc == null || hc == null || sc != hc) {
            action(p, "§cЭти звезды находятся в разных созвездиях.");
            return;
        }

        String s1 = starIdOf(v.selectedKey);
        String s2 = starIdOf(hitKey);

        if (s1 == null || s2 == null) {
            action(p, "§cОшибка: некорректное имя звезды.");
            return;
        }

        Constellation.EdgeDef edge =
                new Constellation.EdgeDef(s1, s2);

        boolean validEdge = false;

        for (Constellation.EdgeDef ed : sc.edges) {
            if (ed.key().equals(edge.key())) {
                validEdge = true;
                break;
            }
        }

        if (!validEdge) {
            action(p, "§cЭти звёзды не соединены линией в созвездии.");
            return;
        }

        PlayerProgress pp = progress.computeIfAbsent(
                p.getUniqueId(),
                k -> new PlayerProgress()
        );

        String drawnKey = sc.id + ":" + edge.key();

        if (pp.drawn.contains(drawnKey)) {
            action(p, "§7Эта линия уже проведена.");
            v.selectedKey = null;
            removePreview(p, v);
            return;
        }

        pp.drawn.add(drawnKey);
        saveProgress();

        playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING);
        action(p, "§aЛиния проведена.");

        v.selectedKey = null;
        removePreview(p, v);

        updateLines(p);
        checkCompletion(p, sc, pp);
    }

    private Vector3f directionToStar(Player p, Constellation.StarDef star) {
        PlayerView view = views.get(p.getUniqueId());
        Location eye = p.getEyeLocation();
        Location anchor = view != null && view.anchor != null ? view.anchor : eye;
        Vector3f eyeFromAnchor = new Vector3f(
                (float) (eye.getX() - anchor.getX()),
                (float) (eye.getY() - anchor.getY()),
                (float) (eye.getZ() - anchor.getZ())
        );
        return SkyGeometry.directionToStar(eyeFromAnchor, scaled(star.direction()));
    }

    private String findAimedStar(Player p, String excludedKey) {
        Vector look = p.getEyeLocation().getDirection();
        return StarTargeting.closest(
                constellations.values(),
                new Vector3f((float) look.getX(), (float) look.getY(), (float) look.getZ()),
                clickToleranceDeg(), excludedKey, star -> directionToStar(p, star)
        );
    }

    private void checkCompletion(
            Player p,
            Constellation c,
            PlayerProgress pp
    ) {
        if (pp.completed.contains(c.id)) {
            return;
        }

        for (Constellation.EdgeDef ed : c.edges) {
            if (!pp.drawn.contains(c.id + ":" + ed.key())) {
                return;
            }
        }

        pp.completed.add(c.id);
        saveProgress();

        grantReward(p, c);
    }

    private void grantReward(Player p, Constellation c) {
        playSound(p, Sound.ENTITY_PLAYER_LEVELUP);

        if (!c.reward.title.isEmpty()) {
            p.sendTitle(
                    color(c.reward.title),
                    color(c.reward.subtitle)
            );
        }

        for (String cmd : c.reward.commands) {
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    cmd.replace("{player}", p.getName())
            );
        }

        if (c.reward.item != null) {
            giveItem(p, c.reward.item.clone());
        }
    }

    private void giveItem(Player p, ItemStack item) {
        var leftovers = p.getInventory().addItem(item);

        for (ItemStack l : leftovers.values()) {
            p.getWorld().dropItemNaturally(
                    p.getLocation(),
                    l
            );
        }
    }

    private void action(Player p, String msg) {
        p.sendActionBar(msg);
    }

    private void playSound(Player p, Sound s) {
        p.playSound(p.getLocation(), s, 0.8f, 1.0f);
    }

    private String starIdOf(String key) {
        int i = key.indexOf(':');
        return i <= 0 ? null : key.substring(i + 1);
    }

    private Constellation constellationOf(String key) {
        int i = key.indexOf(':');
        return i <= 0 ? null : constellations.get(key.substring(0, i));
    }

    // ===== СОБЫТИЯ ВЫХОДА =====

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hideAll(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        hideAll(event.getPlayer());
    }

    public void disable() {
        if (renderTask != null) {
            renderTask.cancel();
        }
        if (animationTask != null) {
            animationTask.cancel();
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            hideAll(p);
        }

        saveProgress();
    }

    // ===== КОМАНДЫ =====

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("stars.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав.");
            return true;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadAll();
                sender.sendMessage(
                        ChatColor.GREEN + "Созвездия перезагружены."
                );
            }
            case "list" -> listConstellations(sender);
            case "add" -> cmdAdd(sender, args);
            case "edge" -> cmdEdge(sender, args);
            case "pin" -> cmdPin(sender, args, true);
            case "unpin" -> cmdPin(sender, args, false);
            case "model" -> cmdModel(sender, args);
            case "reset" -> cmdReset(sender, args);
            case "give" -> cmdGive(sender, args);
            default -> help(sender);
        }

        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(ChatColor.YELLOW + "=== Созвездия ===");
        s.sendMessage(ChatColor.WHITE + "/stars add <созвездие> <звезда> — добавить звезду по взгляду");
        s.sendMessage(ChatColor.WHITE + "/stars edge <созвездие> <з1> <з2> — задать линию");
        s.sendMessage(ChatColor.WHITE + "/stars pin|unpin <созвездие> — закрепить/снять с неба");
        s.sendMessage(ChatColor.WHITE + "/stars model <созвездие> <model> — текстура звёзд");
        s.sendMessage(ChatColor.WHITE + "/stars list — список созвездий");
        s.sendMessage(ChatColor.WHITE + "/stars reload — перезагрузить файлы");
        s.sendMessage(ChatColor.WHITE + "/stars reset <игрок> — сбросить прогресс");
        s.sendMessage(ChatColor.WHITE + "/stars give <игрок> — выдать подзорную трубу");
    }

    private void reloadAll() {
        plugin.reloadConfig();
        renderErrorReported = false;
        startRenderTask();
        loadConstellations();
        loadProgress();

        for (Player p : Bukkit.getOnlinePlayers()) {
            hideAll(p);
        }
    }

    private void listConstellations(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "=== Созвездия ===");

        if (constellations.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY
                    + "(пусто — создайте файл в папке constellations или используйте /stars add)");
            return;
        }

        for (Constellation c : constellations.values()) {
            sender.sendMessage(
                    (c.pinned
                            ? ChatColor.GREEN + "● "
                            : ChatColor.GRAY + "○ ")
                    + c.id
                    + " — " + c.name
                    + " (звёзд: " + c.stars.size()
                    + ", линий: " + c.edges.size() + ")"
            );
        }
    }

    private boolean validId(String s) {
        return s != null
                && !s.isBlank()
                && !s.contains(":")
                && !s.contains("|");
    }

    private Constellation getOrCreate(String id) {
        Constellation c = constellations.get(id);

        if (c == null) {
            c = new Constellation(id);
            constellations.put(id, c);
        }

        return c;
    }

    private void saveConstellation(Constellation c) {
        File f = new File(constellationsDir, c.id + ".yml");

        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

        y.set("name", c.name);
        y.set("pinned", c.pinned);
        y.set("star-model", c.starModel);

        Map<String, List<Double>> starsMap = new LinkedHashMap<>();

        for (Map.Entry<String, Constellation.StarDef> e :
                c.stars.entrySet()) {

            starsMap.put(
                    e.getKey(),
                    List.of(e.getValue().azimuth, e.getValue().elevation)
            );
        }

        y.set("stars", starsMap);

        List<List<String>> edgesList = new ArrayList<>();

        for (Constellation.EdgeDef ed : c.edges) {
            edgesList.add(List.of(ed.a, ed.b));
        }

        y.set("edges", edgesList);

        // Раздел reward не трогаем — администратор правит его в файле.

        try {
            y.save(f);
        } catch (IOException ignored) {
        }
    }

    private void cmdAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Только игрок.");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED
                    + "/stars add <созвездие> <имя_звезды>");
            return;
        }

        String cid = args[1];
        String sid = args[2];

        if (!validId(cid) || !validId(sid)) {
            sender.sendMessage(ChatColor.RED
                    + "Имена не должны содержать ':' и '|'.");
            return;
        }

        Constellation c = getOrCreate(cid);

        Vector dir = p.getEyeLocation().getDirection();

        double az = Math.toDegrees(
                Math.atan2(dir.getX(), -dir.getZ())
        );
        double el = Math.toDegrees(Math.asin(dir.getY()));

        az = (az + 360.0) % 360.0;

        c.stars.put(
                sid,
                new Constellation.StarDef(sid, az, el)
        );

        saveConstellation(c);
        reloadAll();

        sender.sendMessage(ChatColor.GREEN
                + "Звезда " + sid + " добавлена в " + cid
                + " (азимут " + String.format("%.1f", az)
                + "°, высота " + String.format("%.1f", el) + "°).");
    }

    private void cmdEdge(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED
                    + "/stars edge <созвездие> <звезда1> <звезда2>");
            return;
        }

        String cid = args[1];
        String a = args[2];
        String b = args[3];

        Constellation c = constellations.get(cid);

        if (c == null) {
            sender.sendMessage(ChatColor.RED + "Созвездие не найдено.");
            return;
        }

        if (!c.stars.containsKey(a) || !c.stars.containsKey(b)) {
            sender.sendMessage(ChatColor.RED
                    + "Одна из звёзд не найдена.");
            return;
        }

        Constellation.EdgeDef ed = new Constellation.EdgeDef(a, b);

        for (Constellation.EdgeDef e : c.edges) {
            if (e.key().equals(ed.key())) {
                sender.sendMessage(ChatColor.YELLOW
                        + "Эта линия уже задана.");
                return;
            }
        }

        c.edges.add(ed);
        saveConstellation(c);
        reloadAll();

        sender.sendMessage(ChatColor.GREEN
                + "Линия " + a + " — " + b + " добавлена.");
    }

    private void cmdPin(
            CommandSender sender,
            String[] args,
            boolean pin
    ) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED
                    + "/stars " + (pin ? "pin" : "unpin")
                    + " <созвездие>");
            return;
        }

        Constellation c = constellations.get(args[1]);

        if (c == null) {
            sender.sendMessage(ChatColor.RED + "Созвездие не найдено.");
            return;
        }

        c.pinned = pin;
        saveConstellation(c);
        reloadAll();

        sender.sendMessage(ChatColor.GREEN
                + "Созвездие " + c.id
                + (pin ? " закреплено на небе." : " снято с неба."));
    }

    private void cmdModel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED
                    + "/stars model <созвездие> <model> (например f8resurs:star)");
            return;
        }

        Constellation c = constellations.get(args[1]);

        if (c == null) {
            sender.sendMessage(ChatColor.RED + "Созвездие не найдено.");
            return;
        }

        NamespacedKey k = NamespacedKey.fromString(args[2]);

        if (k == null) {
            sender.sendMessage(ChatColor.RED
                    + "Неверный формат модели (нужно namespace:key).");
            return;
        }

        c.starModel = args[2];
        saveConstellation(c);
        reloadAll();

        sender.sendMessage(ChatColor.GREEN
                + "Модель звёзд задана: " + args[2]);
    }

    private void cmdReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "/stars reset <игрок>");
            return;
        }

        Player t = Bukkit.getPlayer(args[1]);

        if (t == null) {
            sender.sendMessage(ChatColor.RED
                    + "Игрок не найден или оффлайн.");
            return;
        }

        progress.remove(t.getUniqueId());
        playerData.set("players." + t.getUniqueId(), null);
        saveProgress();
        hideAll(t);

        sender.sendMessage(ChatColor.GREEN
                + "Прогресс игрока " + t.getName() + " сброшен.");
    }

    private void cmdGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "/stars give <игрок>");
            return;
        }

        Player t = Bukkit.getPlayer(args[1]);

        if (t == null) {
            sender.sendMessage(ChatColor.RED
                    + "Игрок не найден или оффлайн.");
            return;
        }

        t.getInventory().addItem(new ItemStack(Material.SPYGLASS));

        sender.sendMessage(ChatColor.GREEN
                + "Подзорная труба выдана " + t.getName() + ".");
    }
}
