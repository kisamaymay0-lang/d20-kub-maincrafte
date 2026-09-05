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
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Vector3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Matrix4f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private final ProfileManager profiles;
    private final File constellationsDir;
    private final File playerDataFile;
    private YamlConfiguration playerData;
    private final BatchedYamlFile progressStorage;

    private final Map<String, Constellation> constellations =
            new LinkedHashMap<>();

    private BukkitTask renderTask;
    private BukkitTask animationTask;
    private double starSpinRadians;
    private boolean renderErrorReported;
    private final Map<String, StarTemplate> starTemplates = new LinkedHashMap<>();
    private final Map<String, Vector3f> starOffsets = new LinkedHashMap<>();
    private final Map<String, BeamTemplate> lineTemplates = new HashMap<>();
    private ItemStack solidBeamItem;
    private ItemStack previewBeamItem;
    private double radius;
    private float starSize;
    private float lineWidth;
    private double tolerance;
    private double rotationSpeed;
    private int fadeTicks;
    private int followInterpolationTicks;
    private int rotationIntervalTicks;
    private int lastRotationTick;
    private static final Display.Brightness[] LIGHTS = java.util.stream.IntStream.rangeClosed(0, 15)
            .mapToObj(level -> new Display.Brightness(level, level)).toArray(Display.Brightness[]::new);

    private static final class StarTemplate {
        final Vector3f offset;
        final Quaternionf faceObserver;
        final ItemStack item;
        Quaternionf rotation;
        StarTemplate(Vector3f direction, float radius, ItemStack item) {
            Transformation base = SkyGeometry.starTransform(direction, radius, 1f);
            offset = base.getTranslation();
            faceObserver = base.getLeftRotation();
            rotation = new Quaternionf(faceObserver);
            this.item = item;
        }
        Transformation transform(float scale) {
            return new Transformation(offset, rotation, new Vector3f(scale), new Quaternionf());
        }
    }

    private static final class BeamTemplate {
        final Vector3f translation;
        final Quaternionf rotation;
        final Vector3f scale;
        final Vector3f a;
        final Vector3f b;
        BeamTemplate(Matrix4f matrix, Vector3f a, Vector3f b) {
            this.a = a;
            this.b = b;
            translation = matrix.getTranslation(new Vector3f());
            rotation = matrix.getUnnormalizedRotation(new Quaternionf());
            scale = matrix.getScale(new Vector3f());
        }
        Transformation transform(float visibility) {
            return new Transformation(translation, rotation, new Vector3f(scale.x * visibility, scale.y, scale.z),
                    new Quaternionf());
        }
    }

    // Пер-игровое состояние отрисовки
    private static class PlayerView {

        final Map<String, ItemDisplay> stars = new LinkedHashMap<>();
        final Map<String, ItemDisplay> lines = new LinkedHashMap<>();
        ItemDisplay preview = null;
        String previewTargetKey = null;
        String selectedKey = null;
        BeamTemplate previewTemplate;
        final Map<String, BeamTemplate> projectedLines = new HashMap<>();
        Vector3f beamObserver = new Vector3f();
        int lastProjectionTick;
        final SkyFade fade = new SkyFade();
        SkyFollow follow;
        float lastAppearance = -1;
        int lastBrightness = -1;
        boolean relocate;
        // Общая нейтральная целевая позиция, сглаживаемая клиентом.
        Location anchor;
    }

    private final Map<UUID, PlayerView> views = new HashMap<>();

    // Прогресс игрока
    private static class PlayerProgress {

        final Set<String> drawn = new HashSet<>();
        final Set<String> completed = new HashSet<>();
        long profileMedalEarnedAt;
    }

    private final Map<UUID, PlayerProgress> progress = new HashMap<>();

    public ConstellationManager(JavaPlugin plugin, AsyncTextWriter writer, ProfileManager profiles) {
        this.plugin = plugin;
        this.profiles = profiles;

        this.constellationsDir =
                new File(plugin.getDataFolder(), "constellations");

        if (!constellationsDir.exists()) {
            constellationsDir.mkdirs();
        }

        this.playerDataFile =
                new File(plugin.getDataFolder(), "playerdata.yml");

        this.playerData =
                YamlConfiguration.loadConfiguration(playerDataFile);
        progressStorage = new BatchedYamlFile(plugin, writer, playerDataFile.toPath(), () -> playerData.saveToString());

        createExampleFile();
        loadConstellations();
        loadProgress();
        readRenderSettings();
        rebuildRenderCache();
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
        lastRotationTick = Bukkit.getCurrentTick();
        animationTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::animateStars, STAR_ANIMATION_TICKS, STAR_ANIMATION_TICKS
        );
    }

    private double positiveSetting(String key, double fallback) {
        double value = plugin.getConfig().getDouble("constellations." + key, fallback);
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private void readRenderSettings() {
        radius = Math.clamp(positiveSetting("render-distance", 80.0), 4.0, 256.0);
        starSize = (float) Math.min(32.0, positiveSetting("star-scale", 2.0));
        lineWidth = (float) Math.min(16.0, positiveSetting("line-thickness", 0.4));
        tolerance = Math.min(45.0, positiveSetting("click-tolerance-degrees", 2.0));
        double speed = plugin.getConfig().getDouble("constellations.star-rotation-degrees-per-second", 4.0);
        rotationSpeed = Double.isFinite(speed) ? Math.clamp(speed, -90.0, 90.0) : 4.0;
        fadeTicks = Math.clamp(plugin.getConfig().getInt("constellations.fade-duration-ticks", 40), 1, 200);
        followInterpolationTicks = Math.clamp(plugin.getConfig().getInt("constellations.follow-interpolation-ticks", 3), 1, 5);
        int interval = Math.clamp(plugin.getConfig().getInt("constellations.rotation-update-ticks", 4), 2, 20);
        rotationIntervalTicks = (interval + 1) / 2 * 2;
    }

    private double renderDistance() { return radius; }
    private float starScale() { return starSize; }
    private float lineThickness() { return lineWidth; }
    private double clickToleranceDeg() { return tolerance; }

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
                        if (!(l.get(0) instanceof Number azimuth) || !(l.get(1) instanceof Number elevation)) {
                            plugin.getLogger().warning("Некорректные координаты звезды " + id + ":" + sid);
                            continue;
                        }
                        az = azimuth.doubleValue();
                        el = elevation.doubleValue();
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

                    if (!Double.isFinite(az) || !Double.isFinite(el)) {
                        plugin.getLogger().warning("Неконечные координаты звезды " + id + ":" + sid);
                        continue;
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
                    pp.profileMedalEarnedAt = Math.max(0, sec.getLong("profile-medal-earned-at", 0));
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

    private void saveProgress(UUID uuid) {
        PlayerProgress pp = progress.get(uuid);
        String path = "players." + uuid;
        if (pp == null) {
            playerData.set(path, null);
        } else {
            playerData.set(path + ".edges", new ArrayList<>(pp.drawn));
            playerData.set(path + ".completed", new ArrayList<>(pp.completed));
            if (pp.profileMedalEarnedAt > 0) playerData.set(path + ".profile-medal-earned-at", pp.profileMedalEarnedAt);
        }
        progressStorage.markDirty();
    }

    // ===== РЕНДЕР: КЭШИ И ПЛАВНЫЙ ЖИЗНЕННЫЙ ЦИКЛ =====

    private void rebuildRenderCache() {
        starTemplates.clear();
        starOffsets.clear();
        lineTemplates.clear();
        solidBeamItem = beamItem(false);
        previewBeamItem = beamItem(true);
        for (Constellation c : constellations.values()) {
            if (!c.pinned) continue;
            ItemStack model = starItem(c);
            for (var entry : c.stars.entrySet()) {
                Vector3f direction = entry.getValue().direction();
                if (!direction.isFinite()) continue;
                String key = c.id + ":" + entry.getKey();
                StarTemplate template = new StarTemplate(direction, (float) renderDistance(), model);
                starTemplates.put(key, template);
                starOffsets.put(key, template.offset);
            }
            for (Constellation.EdgeDef edge : c.edges) {
                Vector3f a = starOffsets.get(c.id + ":" + edge.a);
                Vector3f b = starOffsets.get(c.id + ":" + edge.b);
                if (a == null || b == null) continue;
                Matrix4f matrix = beamTransform(a, b);
                if (matrix != null) lineTemplates.put(c.id + ":" + edge.key(), new BeamTemplate(matrix, a, b));
            }
        }
        updateRotations();
    }

    private void updateRotations() {
        for (StarTemplate template : starTemplates.values()) {
            template.rotation = new Quaternionf(template.faceObserver).rotateZ((float) starSpinRadians);
        }
    }

    private PlayerView viewFor(Player player) {
        return views.computeIfAbsent(player.getUniqueId(), id -> {
            PlayerView view = new PlayerView();
            view.anchor = motionReference(player);
            view.follow = new SkyFollow(point(view.anchor), Bukkit.getCurrentTick());
            return view;
        });
    }

    private static Location motionReference(Player player) {
        // ignorePose=true: Shift/плавание меняют камеру, но не высоту небесной сферы.
        return SkyGeometry.anchor(player.getLocation().add(0, player.getEyeHeight(true), 0));
    }

    private static Vector3d point(Location location) {
        return new Vector3d(location.getX(), location.getY(), location.getZ());
    }

    private void renderTick() {
        if (starTemplates.isEmpty() && views.isEmpty()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!shouldRender(player)) continue; // Уход уже созданного неба завершает visualTick.
            viewFor(player);
            ensureStars(player);
            updateLines(player);
            updatePreview(player);
        }
    }

    private void animateStars() {
        if (views.isEmpty()) return; // Днём нет обхода всех игроков каждые два тика.
        int tick = Bukkit.getCurrentTick();
        long elapsed = Integer.toUnsignedLong(tick - lastRotationTick);
        boolean rotated = elapsed >= rotationIntervalTicks && rotationSpeed != 0.0;
        if (elapsed >= rotationIntervalTicks) {
            starSpinRadians = Math.IEEEremainder(starSpinRadians + Math.toRadians(rotationSpeed) * elapsed / 20.0,
                    Math.PI * 2.0);
            lastRotationTick = tick;
            if (rotated) updateRotations(); // Один расчёт на звезду для ВСЕХ игроков.
        }
        var iterator = views.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            PlayerView view = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead()
                    || !player.getWorld().equals(view.anchor.getWorld())) {
                clearEntities(view);
                iterator.remove();
                continue;
            }
            if (view.selectedKey != null && !hasSpyglass(player)) clearSelection(player, view, true);
            boolean visible = shouldRender(player);
            if (!visible) clearSelection(player, view, false);
            Location eye = motionReference(player);
            Vector3d eyePoint = point(eye);
            if (view.relocate || view.follow.isJump(eyePoint)) {
                // Большой скачок/телепорт не растягиваем через весь мир:
                // удаляем старое изображение и проявляем новое на месте прибытия.
                clearEntities(view);
                view.fade.reset();
                view.lastAppearance = -1;
                view.anchor = SkyGeometry.anchor(eye);
                view.follow.reset(eyePoint, tick);
                view.relocate = false;
                if (visible) {
                    ensureStars(player);
                    updateLines(player);
                }
            } else if (view.follow.follow(eyePoint, tick, followInterpolationTicks,
                    Math.min(6.0, renderDistance() * 0.08), player.isOnGround() || player.isFlying() || player.isGliding())) {
                Vector3d target = view.follow.target();
                view.anchor = new Location(player.getWorld(), target.x, target.y, target.z, 0f, 0f);
                for (ItemDisplay display : view.stars.values()) if (display.isValid()) display.teleport(view.anchor);
                for (ItemDisplay display : view.lines.values()) if (display.isValid()) display.teleport(view.anchor);
                if (view.preview != null && view.preview.isValid()) view.preview.teleport(view.anchor);
            }
            float appearance = (float) view.fade.advance(visible, (int) STAR_ANIMATION_TICKS, fadeTicks);
            if (!visible && view.fade.hidden()) {
                clearEntities(view);
                iterator.remove();
                continue;
            }
            updateBeamProjection(player, view, tick, Math.max(0.0001f, appearance));
            boolean fading = Float.compare(appearance, view.lastAppearance) != 0;
            if (rotated || fading) applyAppearance(view, appearance, fading);
            view.lastAppearance = appearance;
        }
    }

    private void applyAppearance(PlayerView view, float appearance, boolean fading) {
        float visibility = Math.max(0.0001f, appearance);
        int duration = fading ? (int) STAR_ANIMATION_TICKS : rotationIntervalTicks;
        int brightness = Math.clamp(Math.round(15f * appearance), 0, 15);
        for (var entry : view.stars.entrySet()) {
            StarTemplate template = starTemplates.get(entry.getKey());
            ItemDisplay display = entry.getValue();
            if (template == null || !display.isValid()) continue;
            if (display.getInterpolationDuration() != duration) display.setInterpolationDuration(duration);
            display.setInterpolationDelay(0);
            display.setTransformation(template.transform(starScale() * visibility));
            if (brightness != view.lastBrightness) display.setBrightness(LIGHTS[brightness]);
        }
        if (fading) {
            for (var entry : view.lines.entrySet()) {
                BeamTemplate template = view.projectedLines.getOrDefault(entry.getKey(), lineTemplates.get(entry.getKey()));
                if (template != null && entry.getValue().isValid()) fadeBeam(entry.getValue(), template, visibility);
            }
            if (view.preview != null && view.preview.isValid() && view.previewTemplate != null) {
                fadeBeam(view.preview, view.previewTemplate, visibility);
            }
        }
        view.lastBrightness = brightness;
    }

    private BeamTemplate project(BeamTemplate base, Vector3f observer) {
        // a/b всегда исходные центры: при возвращении observer к нулю
        // нельзя оставлять проекцию от предыдущего кадра превью.
        Matrix4f matrix = SkyGeometry.beamTransform(base.a, base.b, lineThickness() / 8f, starScale() * 2f, observer);
        return matrix == null ? base : new BeamTemplate(matrix, base.a, base.b);
    }

    private void updateBeamProjection(Player player, PlayerView view, int tick, float visibility) {
        if (Integer.toUnsignedLong(tick - view.lastProjectionTick) < 4) return;
        view.lastProjectionTick = tick;
        Location eye = player.getEyeLocation();
        Vector3d anchor = view.follow.sample(tick);
        Vector3f observer = new Vector3f((float) (eye.getX() - anchor.x), (float) (eye.getY() - anchor.y), (float) (eye.getZ() - anchor.z));
        if (observer.distanceSquared(view.beamObserver) < 0.0001f) return;
        view.beamObserver = observer;
        for (var entry : view.lines.entrySet()) {
            BeamTemplate base = lineTemplates.get(entry.getKey());
            if (base == null || !entry.getValue().isValid()) continue;
            BeamTemplate projected = project(base, observer);
            view.projectedLines.put(entry.getKey(), projected);
            fadeBeam(entry.getValue(), projected, visibility);
        }
        if (view.preview != null && view.preview.isValid() && view.previewTemplate != null) {
            view.previewTemplate = project(view.previewTemplate, observer);
            fadeBeam(view.preview, view.previewTemplate, visibility);
        }
    }

    private void fadeBeam(ItemDisplay display, BeamTemplate template, float visibility) {
        if (display.getInterpolationDuration() != STAR_ANIMATION_TICKS) {
            display.setInterpolationDuration((int) STAR_ANIMATION_TICKS);
        }
        display.setInterpolationDelay(0);
        display.setTransformation(template.transform(visibility));
    }

    private boolean shouldRender(Player player) {
        return !starTemplates.isEmpty() && !player.isDead()
                && player.getWorld().getEnvironment() == World.Environment.NORMAL
                && SkyGeometry.isNight(player.getWorld().getTime());
    }

    private ItemStack starItem(Constellation c) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            NamespacedKey key = NamespacedKey.fromString(c.starModel);
            meta.setItemModel(key == null ? new NamespacedKey("f8resurs", "star") : key);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void configureDisplay(ItemDisplay display) {
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        display.setBillboard(Display.Billboard.FIXED);
        display.setTeleportDuration(followInterpolationTicks);
        display.setInterpolationDuration(0);
        display.setShadowRadius(0f);
        display.setShadowStrength(0f);
        display.setViewRange(160f);
        display.setPersistent(false);
        display.setVisibleByDefault(false);
    }

    private void ensureStars(Player player) {
        PlayerView view = viewFor(player);
        float appearance = (float) view.fade.value();
        for (var entry : starTemplates.entrySet()) {
            ItemDisplay existing = view.stars.get(entry.getKey());
            if (existing != null && existing.isValid()) continue;
            StarTemplate template = entry.getValue();
            try {
                ItemDisplay display = player.getWorld().spawn(view.anchor, ItemDisplay.class, e -> {
                    configureDisplay(e);
                    e.setItemStack(template.item);
                    e.setTransformation(template.transform(starScale() * Math.max(0.0001f, appearance)));
                    e.setBrightness(LIGHTS[Math.clamp(Math.round(appearance * 15f), 0, 15)]);
                });
                player.showEntity(plugin, display);
                view.stars.put(entry.getKey(), display);
            } catch (Exception ex) { reportRenderError(ex); }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerView view = views.get(event.getPlayer().getUniqueId());
        if (view != null && event.getTo() != null
                && (!event.getFrom().getWorld().equals(event.getTo().getWorld())
                || event.getFrom().distanceSquared(event.getTo()) > 16.0 * 16.0)) {
            view.relocate = true;
        }
    }

    private void reportRenderError(Exception ex) {
        if (!renderErrorReported) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Не удалось отрисовать созвездия (повторные ошибки скрыты до /stars reload)", ex);
            renderErrorReported = true;
        }
    }

    private Matrix4f beamTransform(Vector3f a, Vector3f b) {
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

    private ItemDisplay spawnLine(Player player, PlayerView view, BeamTemplate template, boolean preview) {
        try {
            ItemDisplay display = player.getWorld().spawn(view.anchor, ItemDisplay.class, e -> {
                configureDisplay(e);
                e.setItemStack(preview ? previewBeamItem : solidBeamItem);
                e.setBrightness(LIGHTS[15]);
                e.setTransformation(template.transform(Math.max(0.0001f, (float) view.fade.value())));
            });
            player.showEntity(plugin, display);
            return display;
        } catch (Exception ex) {
            reportRenderError(ex);
            return null;
        }
    }

    private BeamTemplate lineTemplate(String key) {
        if (lineTemplates.containsKey(key)) return lineTemplates.get(key);
        // Сохраняем прежнее поведение: уже проведённая связь остаётся
        // видимой, пока существуют обе звезды, даже после правки списка рёбер.
        BeamTemplate result = null;
        int colon = key.indexOf(':');
        if (colon > 0) {
            String[] ids = key.substring(colon + 1).split("\\|", -1);
            if (ids.length == 2) {
                String prefix = key.substring(0, colon + 1);
                Vector3f a = starOffsets.get(prefix + ids[0]);
                Vector3f b = starOffsets.get(prefix + ids[1]);
                if (a != null && b != null) {
                    Matrix4f matrix = beamTransform(a, b);
                    if (matrix != null) result = new BeamTemplate(matrix, a, b);
                }
            }
        }
        lineTemplates.put(key, result);
        return result;
    }

    private void updateLines(Player player) {
        PlayerView view = views.get(player.getUniqueId());
        if (view == null) return;
        PlayerProgress pp = progress.get(player.getUniqueId());
        if (pp != null) {
            for (String key : pp.drawn) {
                ItemDisplay existing = view.lines.get(key);
                if (existing != null && existing.isValid()) continue;
                BeamTemplate template = lineTemplate(key);
                if (template == null) continue;
                template = project(template, view.beamObserver);
                view.projectedLines.put(key, template);
                ItemDisplay line = spawnLine(player, view, template, false);
                if (line != null) view.lines.put(key, line);
            }
        }
        var iterator = view.lines.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (pp == null || !pp.drawn.contains(entry.getKey()) || lineTemplates.get(entry.getKey()) == null) {
                entry.getValue().remove();
                view.projectedLines.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    private void updatePreview(Player player) {
        PlayerView view = views.get(player.getUniqueId());
        if (view == null) return;
        if (view.selectedKey == null) {
            removePreview(player, view);
            return;
        }
        if (!hasSpyglass(player)) {
            clearSelection(player, view, true);
            return;
        }
        Vector3f source = starOffsets.get(view.selectedKey);
        if (source == null) {
            clearSelection(player, view, false);
            return;
        }
        String hover = findAimedStar(player, view.selectedKey);
        if (hover == null) {
            removePreview(player, view);
            return;
        }
        if (hover.equals(view.previewTargetKey) && view.preview != null && view.preview.isValid()) return;
        Matrix4f matrix = beamTransform(source, starOffsets.get(hover));
        if (matrix == null) {
            removePreview(player, view);
            return;
        }
        view.previewTemplate = project(new BeamTemplate(matrix, source, starOffsets.get(hover)), view.beamObserver);
        if (view.preview == null || !view.preview.isValid()) {
            view.preview = spawnLine(player, view, view.previewTemplate, true);
        } else {
            view.preview.setInterpolationDuration(0); // Наведение не растягиваем через чужие звёзды.
            view.preview.setTransformation(view.previewTemplate.transform(Math.max(0.0001f, (float) view.fade.value())));
        }
        view.previewTargetKey = hover;
    }

    private void removePreview(Player player, PlayerView view) {
        if (view.preview != null) view.preview.remove();
        view.preview = null;
        view.previewTemplate = null;
        view.previewTargetKey = null;
    }

    private boolean hasSpyglass(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.SPYGLASS;
    }

    private void clearSelection(Player player, PlayerView view, boolean notify) {
        boolean selected = view.selectedKey != null;
        view.selectedKey = null;
        removePreview(player, view);
        if (selected && notify) action(player, "§7Выбор сброшен.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        PlayerView view = views.get(event.getPlayer().getUniqueId());
        ItemStack next = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (view != null && (next == null || next.getType() != Material.SPYGLASS)) {
            clearSelection(event.getPlayer(), view, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        PlayerView view = views.get(event.getPlayer().getUniqueId());
        ItemStack next = event.getMainHandItem();
        if (view != null && (next == null || next.getType() != Material.SPYGLASS)) {
            clearSelection(event.getPlayer(), view, true);
        }
    }

    private void clearEntities(PlayerView view) {
        for (ItemDisplay display : view.stars.values()) display.remove();
        for (ItemDisplay display : view.lines.values()) display.remove();
        if (view.preview != null) view.preview.remove();
        view.stars.clear();
        view.lines.clear();
        view.projectedLines.clear();
        view.beamObserver = new Vector3f();
        view.lastProjectionTick = 0;
        view.preview = null;
        view.previewTemplate = null;
        view.previewTargetKey = null;
    }

    private void hideAll(Player player) {
        PlayerView view = views.remove(player.getUniqueId());
        if (view != null) clearEntities(view);
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
        ensureStars(p);
        String hitKey = findAimedStar(p, null);

        PlayerView v = viewFor(p);

        if (hitKey == null) {
            // Промах без выбора тихий; активный выбор сбрасывается с сообщением.
            clearSelection(p, v, true);
            return;
        }

        if (v.selectedKey == null) {
            v.selectedKey = hitKey;
            action(p, "§eЗвезда выбрана.");
            playSound(p, Sound.UI_BUTTON_CLICK);
            return;
        }

        if (v.selectedKey.equals(hitKey)) {
            clearSelection(p, v, true);
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
        saveProgress(p.getUniqueId());

        playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING);
        action(p, "§aЛиния проведена.");

        v.selectedKey = null;
        removePreview(p, v);

        updateLines(p);
        checkCompletion(p, sc, pp);
    }

    private String findAimedStar(Player player, String excludedKey) {
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        PlayerView view = views.get(player.getUniqueId());
        Vector3d anchor = view == null ? point(eye) : view.follow.sample(Bukkit.getCurrentTick());
        return StarTargeting.closestOffsets(starOffsets,
                new Vector3f((float) look.getX(), (float) look.getY(), (float) look.getZ()),
                new Vector3f((float) (eye.getX() - anchor.x), (float) (eye.getY() - anchor.y), (float) (eye.getZ() - anchor.z)),
                clickToleranceDeg(), excludedKey);
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
        // Маркер создаётся только при НОВОМ завершении 9.3+, не из старого списка completed.
        // Сохраняется вместе с прогрессом: выдачу медали можно восстановить после сбоя.
        if (pp.profileMedalEarnedAt == 0) pp.profileMedalEarnedAt = System.currentTimeMillis();
        saveProgress(p.getUniqueId());
        // Редкое завершение сохраняем ДО выдачи награды, как и раньше.
        progressStorage.flushBlocking();
        profiles.constellationCompleted(p);
        grantReward(p, c);
    }

    long profileMedalEarnedAt(UUID player) {
        PlayerProgress data = progress.get(player);
        return data == null ? 0 : data.profileMedalEarnedAt;
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

        progressStorage.flush();
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
        progressStorage.flush();
        loadConstellations();
        readRenderSettings();
        rebuildRenderCache();
        startRenderTask();
        // progress уже актуален в памяти; не пересобираем всех игроков при каждой админ-команде.

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
        saveProgress(t.getUniqueId());
        progressStorage.flushBlocking();
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
