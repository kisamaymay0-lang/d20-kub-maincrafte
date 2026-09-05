package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Частная панель у тела игрока. Наведение на панель/медаль/подсказку удерживает цель. */
final class ProfileCards {
    private static final Color CLEAR = Color.fromARGB(0, 0, 0, 0);
    private static final Display.Brightness LIGHT = new Display.Brightness(15, 15);

    private static final class Card {
        UUID target;
        ProfilePanelGeometry.Frame frame;
        ProfilePanelGeometry.Frame rendered;
        ProfilePanelGeometry.Rect tooltipRect;
        TextDisplay background, body, footer, tooltip;
        ItemDisplay medal;
        ProfileMedal latest;
        final SkyFade fade = new SkyFade();
        final SkyFade tipFade = new SkyFade();
        long revision = -1;
        long style = -1;
        int lastFocused;
        int bodyLines;
        int tipLines;
        boolean visible;
        boolean showTip;
        double lastOpacity = -1;
        double lastTipOpacity = -1;
        double tooltipScale;
    }

    private final JavaPlugin plugin;
    private final Function<Player, ProfileData> profiles;
    private final ProfileItems items;
    private final Set<UUID> sneaking = new HashSet<>();
    private final Map<UUID, Card> cards = new HashMap<>();
    private final BukkitTask task;
    private final double range;
    private int frames;
    private boolean errorReported;

    ProfileCards(JavaPlugin plugin, Function<Player, ProfileData> profiles, ProfileItems items) {
        this.plugin = plugin; this.profiles = profiles; this.items = items;
        double configured = plugin.getConfig().getDouble("profiles.quick-card-distance", 8);
        range = Double.isFinite(configured) ? Math.clamp(configured, 2, 16) : 8;
        for (Player player : Bukkit.getOnlinePlayers()) if (player.isSneaking()) sneaking.add(player.getUniqueId());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    void sneaking(UUID viewer, boolean value) { if (value) sneaking.add(viewer); else sneaking.remove(viewer); }

    static boolean canInspect(Player viewer, Player target) {
        return !viewer.getUniqueId().equals(target.getUniqueId()) && target.isOnline() && !target.isDead()
                && viewer.getWorld().equals(target.getWorld()) && viewer.canSee(target)
                && Bukkit.getPlayer(target.getUniqueId()) == target && !target.isInvisible();
    }

    private boolean ready(Player player) {
        return player != null && player.isOnline() && !player.isDead() && player.isSneaking()
                && player.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING;
    }

    private boolean validTarget(Player viewer, Player target) {
        return target != null && canInspect(viewer, target)
                && viewer.getLocation().distanceSquared(target.getLocation()) <= (range + 1) * (range + 1);
    }

    private Player aimedPlayer(Player viewer, Location eye) {
        RayTraceResult hit = viewer.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.15,
                entity -> entity instanceof Player target && canInspect(viewer, target));
        return hit != null && hit.getHitEntity() instanceof Player target ? target : null;
    }

    private static Vector3d point(Location location) { return new Vector3d(location.getX(), location.getY(), location.getZ()); }
    private static Vector3d point(Vector vector) { return new Vector3d(vector.getX(), vector.getY(), vector.getZ()); }

    private boolean clearRay(Player viewer, Location eye, Vector3d end) {
        Vector ray = new Vector(end.x - eye.getX(), end.y - eye.getY(), end.z - eye.getZ());
        double distance = ray.length();
        if (distance < 0.01) return true;
        return viewer.getWorld().rayTraceBlocks(eye, ray.multiply(1 / distance), Math.max(0, distance - 0.04),
                FluidCollisionMode.NEVER, true) == null;
    }

    private ProfilePanelGeometry.Frame frame(Player viewer, Player target) {
        return ProfilePanelGeometry.beside(point(viewer.getEyeLocation()), point(target.getLocation()), target.getWidth(), target.getHeight());
    }

    private void scan(Player viewer, int tick) {
        UUID id = viewer.getUniqueId();
        Card card = cards.get(id);
        if (!ready(viewer)) {
            if (!viewer.isSneaking()) sneaking.remove(id);
            if (card != null) { card.visible = false; card.showTip = false; }
            return;
        }
        Location eye = viewer.getEyeLocation();
        boolean retained = false;
        Player current = card == null ? null : Bukkit.getPlayer(card.target);
        if (card != null && validTarget(viewer, current)) {
            card.frame = frame(viewer, current);
            ProfilePanelGeometry.Hit hit = card.frame.intersect(point(eye), point(eye.getDirection()), range + 4);
            // Небольшая невидимая перемычка закрывает зазор между телом и карточкой.
            ProfilePanelGeometry.Rect panel = card.frame.focusBounds();
            boolean overPanel = hit != null && panel.contains(hit.x(), hit.y());
            boolean overTip = hit != null && card.tooltip != null && card.tooltipRect != null
                    && (card.tooltipRect.contains(hit.x(), hit.y()) || card.frame.tooltipBridge(card.tooltipRect).contains(hit.x(), hit.y()));
            if (hit != null && (overPanel || overTip) && clearRay(viewer, eye, hit.point())
                    && clearRay(viewer, eye, point(current.getEyeLocation()))) {
                retained = true;
                card.lastFocused = tick;
                card.visible = true;
                card.showTip = card.latest != null && (card.frame.medal().contains(hit.x(), hit.y()) || overTip);
            }
        }
        if (!retained) {
            Player target = aimedPlayer(viewer, eye);
            if (target != null) {
                if (card == null || !target.getUniqueId().equals(card.target)) {
                    if (card != null) remove(card);
                    card = new Card();
                    card.target = target.getUniqueId();
                    cards.put(id, card);
                }
                card.frame = frame(viewer, target);
                card.lastFocused = tick;
                card.visible = true;
                card.showTip = false;
            } else if (card != null) {
                card.visible = validTarget(viewer, current) && Integer.toUnsignedLong(tick - card.lastFocused) <= 6
                        && clearRay(viewer, eye, point(current.getEyeLocation()));
                card.showTip = false;
            }
        }
    }

    private void tick() {
        if (sneaking.isEmpty() && cards.isEmpty()) return;
        int tick = Bukkit.getCurrentTick();
        if ((frames++ & 1) == 0) {
            Set<UUID> viewers = new HashSet<>(sneaking);
            viewers.addAll(cards.keySet());
            for (UUID id : viewers) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer == null) { quit(id); continue; }
                try { scan(viewer, tick); }
                catch (RuntimeException ex) { report(ex); Card bad = cards.remove(id); if (bad != null) remove(bad); }
            }
        }
        var iterator = cards.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player viewer = Bukkit.getPlayer(entry.getKey());
            Card card = entry.getValue();
            Player target = Bukkit.getPlayer(card.target);
            if (viewer == null || target == null || !viewer.getWorld().equals(target.getWorld())) {
                remove(card); iterator.remove(); continue;
            }
            boolean visible = card.visible && ready(viewer) && validTarget(viewer, target);
            try {
                if (visible) {
                    card.frame = frame(viewer, target); // Только координаты, не поворот прицела.
                    ProfileData data = profiles.apply(target);
                    boolean recreated = card.background == null || !card.background.isValid() || card.body == null || !card.body.isValid() || card.footer == null || !card.footer.isValid();
                    if (recreated) {
                        remove(card);
                        card.background = text(viewer, card.frame, Component.text(" "));
                        card.body = text(viewer, card.frame, Component.empty());
                        card.footer = text(viewer, card.frame, Component.empty());
                        card.fade.reset(); card.tipFade.reset(); card.lastOpacity = -1; card.lastTipOpacity = -1;
                        card.revision = -1; card.rendered = null;
                    }
                    if (data.revision() != card.revision || card.style != items.styleRevision()) contents(viewer, card, data);
                    if (!card.frame.approximately(card.rendered)) layout(viewer, card);
                    if (card.showTip && card.latest != null && (card.tooltip == null || !card.tooltip.isValid())) {
                        List<Component> lines = items.tooltip(card.latest);
                        card.tooltip = text(viewer, card.frame, join(lines));
                        card.tooltip.setLineWidth(152);
                        card.tipLines = lines.stream().mapToInt(line -> Math.max(1, (ProfileText.length(PlainTextComponentSerializer.plainText().serialize(line)) + 19) / 20)).sum();
                        card.tooltipScale = Math.min(card.frame.height() * 0.23, card.frame.height() / (card.tipLines * 0.25 + 0.03));
                        double tipWidth = 154 * 0.025 * card.tooltipScale;
                        double tipHeight = (card.tipLines * 10 + 1) * 0.025 * card.tooltipScale;
                        card.tooltipRect = card.frame.tooltip(tipWidth, tipHeight);
                        card.lastTipOpacity = -1;
                        layout(viewer, card);
                    }
                }
                double alpha = card.fade.advance(visible, 2, 8);
                if (!visible && card.fade.hidden()) { remove(card); iterator.remove(); continue; }
                if (alpha != card.lastOpacity) {
                    card.lastOpacity = alpha;
                    opacity(card.body, alpha); opacity(card.footer, alpha);
                    if (card.background != null && card.background.isValid()) card.background.setBackgroundColor(Color.fromARGB((int) (alpha * 190), 18, 20, 24));
                    if (card.medal != null && card.medal.isValid()) poseIcon(viewer, card, Math.max(0.001, alpha));
                }
                double tipAlpha = card.tipFade.advance(visible && card.showTip && card.latest != null, 2, 4);
                if (card.tooltip != null && card.tooltip.isValid() && tipAlpha != card.lastTipOpacity) {
                    card.lastTipOpacity = tipAlpha;
                    opacity(card.tooltip, tipAlpha);
                    card.tooltip.setBackgroundColor(Color.fromARGB((int) (tipAlpha * 220), 18, 20, 24));
                    if (card.tipFade.hidden()) { card.tooltip.remove(); card.tooltip = null; card.tooltipRect = null; }
                }
            } catch (RuntimeException ex) { remove(card); iterator.remove(); report(ex); }
        }
    }

    private void contents(Player viewer, Card card, ProfileData data) {
        List<String> description = ProfileText.wrap(data.displayedDescription(), 20);
        List<Component> body = new ArrayList<>();
        body.add(ProfileItems.text(data.name(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
        for (String line : description) body.add(ProfileItems.text(line, NamedTextColor.GRAY));
        card.bodyLines = body.size();
        card.body.text(join(body));
        ProfileMedal latest = data.latestMedal();
        Component footer = ProfileItems.text("Лайки ", NamedTextColor.GRAY).append(ProfileItems.text(Integer.toString(data.likes()), NamedTextColor.GREEN))
                .append(ProfileItems.text("  ·  Дизлайки ", NamedTextColor.GRAY)).append(ProfileItems.text(Integer.toString(data.dislikes()), NamedTextColor.RED))
                .append(Component.text("\n\n\n"))
                .append(ProfileItems.text(latest == null ? "Последняя медаль: нет" : "Последняя медаль:", NamedTextColor.GRAY));
        card.footer.text(footer);
        if (!java.util.Objects.equals(card.latest, latest) || card.style != items.styleRevision()
                || (latest != null && (card.medal == null || !card.medal.isValid()))) {
            if (card.tooltip != null) card.tooltip.remove();
            card.tooltip = null; card.tooltipRect = null; card.tipFade.reset(); card.lastTipOpacity = -1;
            card.latest = latest;
            if (latest == null) {
                if (card.medal != null) card.medal.remove();
                card.medal = null;
            } else {
                if (card.medal == null || !card.medal.isValid()) {
                    card.medal = viewer.getWorld().spawn(location(viewer, card.frame.point(0, 0, 0.025)), ItemDisplay.class, display -> {
                        configure(display);
                        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
                        display.setItemStack(items.medal(latest, List.of()));
                        display.setTransformation(new Transformation(new Vector3f(), card.frame.rotation(), new Vector3f(0.001f), new Quaternionf()));
                    });
                    viewer.showEntity(plugin, card.medal);
                } else card.medal.setItemStack(items.medal(latest, List.of()));
            }
        }
        card.revision = data.revision(); card.style = items.styleRevision();
        card.rendered = null;
    }

    private void layout(Player viewer, Card card) {
        ProfilePanelGeometry.Frame frame = card.frame;
        double h = frame.height(), w = frame.width();
        // У пробела ванильного шрифта ширина 4 px. Фон TextDisplay: (width+1) × (lines*10+1).
        // Это отдельная ровная панель фиксированного размера, а не растянутое содержимое текста.
        pose(viewer, card.background, frame, -w / 10, -h / 2, 0, new Vector3f((float) (w / 0.125), (float) (h / 0.275), 1));
        double bodyScale = Math.min(h * 0.265, h * 0.63 / Math.max(0.25, card.bodyLines * 0.25));
        pose(viewer, card.body, frame, -bodyScale * 0.025, h / 2 - h * 0.045 - card.bodyLines * 0.25 * bodyScale,
                0.015, new Vector3f((float) bodyScale));
        double footerScale = h * 0.20;
        pose(viewer, card.footer, frame, -footerScale * 0.025, -h / 2 + h * 0.075, 0.015, new Vector3f((float) footerScale));
        poseIcon(viewer, card, Math.max(0.001, card.fade.value()));
        if (card.tooltip != null && card.tooltip.isValid() && card.tooltipRect != null) {
            var rect = card.tooltipRect;
            pose(viewer, card.tooltip, frame, rect.x() - card.tooltipScale * 0.025,
                    rect.y() - rect.height() / 2, 0.025, new Vector3f((float) card.tooltipScale));
        }
        card.rendered = frame;
    }

    private void poseIcon(Player viewer, Card card, double visibility) {
        if (card.medal == null || !card.medal.isValid()) return;
        var rect = card.frame.medal();
        pose(viewer, card.medal, card.frame, rect.x(), rect.y(), 0.025, new Vector3f((float) (card.frame.iconSize() * visibility)));
    }

    private void pose(Player viewer, Display display, ProfilePanelGeometry.Frame frame, double x, double y, double depth, Vector3f scale) {
        if (display == null || !display.isValid()) return;
        Location at = location(viewer, frame.point(x, y, depth));
        if (display.getLocation().distanceSquared(at) > 0.000001) display.teleport(at);
        display.setInterpolationDelay(0);
        display.setTransformation(new Transformation(new Vector3f(), frame.rotation(), scale, new Quaternionf()));
    }

    private TextDisplay text(Player viewer, ProfilePanelGeometry.Frame frame, Component value) {
        TextDisplay display = viewer.getWorld().spawn(location(viewer, frame.center()), TextDisplay.class, entity -> {
            configure(entity);
            entity.setDefaultBackground(false); entity.setBackgroundColor(CLEAR);
            entity.setTextOpacity((byte) 4); // Значения 0..3 некоторые версии клиента трактуют как непрозрачные.
            entity.setShadowed(false); entity.setSeeThrough(false);
            entity.setLineWidth(160); entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.text(value);
        });
        viewer.showEntity(plugin, display);
        return display;
    }

    private void configure(Display display) {
        display.setVisibleByDefault(false); display.setPersistent(false);
        display.setBillboard(Display.Billboard.FIXED);
        display.setShadowRadius(0); display.setShadowStrength(0); display.setBrightness(LIGHT);
        display.setTeleportDuration(2); display.setInterpolationDuration(2);
    }

    private static Location location(Player viewer, Vector3d point) { return new Location(viewer.getWorld(), point.x, point.y, point.z, 0, 0); }
    private static Component join(List<Component> lines) {
        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) { if (i > 0) result = result.append(Component.newline()); result = result.append(lines.get(i)); }
        return result;
    }
    private static void opacity(TextDisplay display, double value) {
        if (display != null && display.isValid()) display.setTextOpacity((byte) Math.clamp((int) Math.round(value * 255), 4, 255));
    }
    private static void remove(Card card) {
        if (card.background != null) card.background.remove();
        if (card.body != null) card.body.remove();
        if (card.footer != null) card.footer.remove();
        if (card.medal != null) card.medal.remove();
        if (card.tooltip != null) card.tooltip.remove();
        card.background = null; card.body = null; card.footer = null; card.medal = null; card.tooltip = null;
        card.latest = null; card.tooltipRect = null;
    }
    private void report(RuntimeException ex) {
        if (!errorReported) { plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось показать карточку профиля", ex); errorReported = true; }
    }
    void quit(UUID player) {
        sneaking.remove(player);
        Card own = cards.remove(player); if (own != null) remove(own);
        var iterator = cards.values().iterator();
        while (iterator.hasNext()) { Card card = iterator.next(); if (player.equals(card.target)) { remove(card); iterator.remove(); } }
    }
    void refresh() {
        for (Card card : cards.values()) { card.revision = -1; card.style = -1; }
        errorReported = false;
    }
    void disable() {
        task.cancel(); cards.values().forEach(ProfileCards::remove); cards.clear(); sneaking.clear();
    }
}
