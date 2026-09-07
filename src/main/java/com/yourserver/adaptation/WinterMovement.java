package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Зацеп и временная неподвижность. Обрабатываются только игроки с активным состоянием. */
final class WinterMovement implements Listener {
    private enum Grip { NONE, HANG, JUMP }
    private static final class State {
        Grip grip = Grip.NONE;
        Block wall;
        boolean jumpDown;
        long launched;
        Location correction;
        Location hangAnchor;
        Location freezeAnchor;
        boolean correcting;
        long correctionTick;
        double previousY;
        long frozenUntil;
        long coldUntil;
        boolean controlled;
        float walkSpeed, flySpeed;
        boolean gravity;
        boolean previousColdLock;
        int previousColdTicks;
    }
    private final WinterItems items;
    private final Map<UUID, State> states = new HashMap<>();
    private final NamespacedKey walk, fly, gravity, coldLock, coldTicks;
    private final BukkitTask task;
    private long tick;

    WinterMovement(JavaPlugin plugin, WinterItems items) {
        this.items = items;
        walk = new NamespacedKey(plugin, "winter_saved_walk");
        fly = new NamespacedKey(plugin, "winter_saved_fly");
        gravity = new NamespacedKey(plugin, "winter_saved_gravity");
        coldLock = new NamespacedKey(plugin, "winter_saved_cold_lock");
        coldTicks = new NamespacedKey(plugin, "winter_saved_cold_ticks");
        for (Player player : Bukkit.getOnlinePlayers()) recover(player);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void freeze(Player player, boolean sandwich) {
        if (!player.isOnline() || player.isDead()) return;
        State state = states.computeIfAbsent(player.getUniqueId(), ignored -> new State());
        if (state.coldUntil == 0) {
            state.previousColdLock = player.isFreezeTickingLocked();
            state.previousColdTicks = player.getFreezeTicks();
            player.getPersistentDataContainer().set(coldLock, PersistentDataType.BYTE, (byte) (state.previousColdLock ? 1 : 0));
            player.getPersistentDataContainer().set(coldTicks, PersistentDataType.INTEGER, state.previousColdTicks);
        }
        if (state.frozenUntil <= tick) state.freezeAnchor = player.getLocation().clone();
        state.frozenUntil = Math.max(state.frozenUntil, tick + (sandwich ? WinterRules.SANDWICH_LOCK_TICKS : WinterRules.FISH_LOCK_TICKS));
        state.coldUntil = Math.max(state.coldUntil, tick + WinterRules.COLD_TICKS);
        state.grip = Grip.NONE;
        control(player, state);
        player.lockFreezeTicks(true);
        player.setFreezeTicks(player.getMaxFreezeTicks()); // Ванильный FREEZE урон и иммунитеты остаются у Minecraft.
        player.setFallDistance(0);
        player.setVelocity(new Vector());
    }

    private void control(Player player, State state) {
        if (!state.controlled) {
            state.walkSpeed = player.getWalkSpeed(); state.flySpeed = player.getFlySpeed(); state.gravity = player.hasGravity();
            player.getPersistentDataContainer().set(walk, PersistentDataType.FLOAT, state.walkSpeed);
            player.getPersistentDataContainer().set(fly, PersistentDataType.FLOAT, state.flySpeed);
            player.getPersistentDataContainer().set(gravity, PersistentDataType.BYTE, (byte) (state.gravity ? 1 : 0));
            state.controlled = true;
        }
        player.setWalkSpeed(0); player.setFlySpeed(0);
        player.setGravity(state.grip == Grip.JUMP && state.frozenUntil <= tick ? state.gravity : false);
    }

    private void restoreControl(Player player, State state) {
        if (!state.controlled) return;
        player.setWalkSpeed(state.walkSpeed); player.setFlySpeed(state.flySpeed); player.setGravity(state.gravity);
        var data = player.getPersistentDataContainer(); data.remove(walk); data.remove(fly); data.remove(gravity);
        state.controlled = false;
    }

    private void restoreCold(Player player, State state) {
        player.lockFreezeTicks(state.previousColdLock);
        if (state.previousColdLock) player.setFreezeTicks(state.previousColdTicks);
        else if (!player.isInPowderedSnow()) player.setFreezeTicks(0);
        player.getPersistentDataContainer().remove(coldLock); player.getPersistentDataContainer().remove(coldTicks);
        state.coldUntil = 0;
    }

    void release(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        state.grip = Grip.NONE; state.wall = null;
        if (state.frozenUntil <= tick) restoreControl(player, state);
    }

    private Block wall(Player player) {
        double reach = player.getWidth() / 2 + 0.18;
        Location feet = player.getLocation();
        double[] heights = {Math.min(1, player.getHeight() * 0.55), player.getEyeHeight()};
        for (double height : heights) {
            Location origin = feet.clone().add(0, height, 0);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                var hit = player.getWorld().rayTraceBlocks(origin, new Vector(x, 0, z).normalize(), reach,
                        org.bukkit.FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null) return hit.getHitBlock();
            }
        }
        return null;
    }

    private void latch(Player player, State state, Block wall) {
        state.grip = Grip.HANG; state.wall = wall;
        state.hangAnchor = player.getLocation().clone();
        state.jumpDown = player.getCurrentInput().isJump();
        control(player, state);
        player.setFallDistance(0); player.setVelocity(new Vector());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) { release(player); return; }
        State current = states.get(player.getUniqueId());
        // Только НОВОЕ нажатие Shift в воздухе. Удержанный заранее Shift не даёт автозацеп.
        if (player.isFlying() || player.isDead() || !WinterRules.canGrab(true, !player.isOnGround(), items.holdsTool(player),
                current != null && current.frozenUntil > tick, current != null && current.grip != Grip.NONE)) return;
        Block wall = wall(player);
        if (wall != null) latch(player, states.computeIfAbsent(player.getUniqueId(), ignored -> new State()), wall);
    }

    @EventHandler
    public void input(PlayerInputEvent event) {
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        boolean jump = event.getInput().isJump();
        boolean pressed = WinterRules.jumpPressed(state.jumpDown, jump);
        state.jumpDown = jump;
        if (!event.getInput().isSneak()) { release(player); return; }
        if (!pressed || state.grip != Grip.HANG || state.frozenUntil > tick || !items.holdsTool(player)) return;
        state.grip = Grip.JUMP; state.launched = tick; state.previousY = player.getLocation().getY();
        control(player, state);
        player.setFallDistance(0);
        player.setVelocity(new Vector(0, WinterRules.CLIMB_VELOCITY, 0));
        if (!items.useClimb(player)) release(player); // Последний прыжок остаётся, но сломанной киркой больше не зацепиться.
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void move(PlayerMoveEvent event) {
        State state = states.get(event.getPlayer().getUniqueId());
        if (state == null || event.getTo() == null) return;
        if (state.frozenUntil > tick) {
            state.correction = event.getFrom().clone(); state.correctionTick = tick;
            event.setCancelled(true); // Включая yaw/pitch. Не вызываем цепочку собственных PlayerTeleportEvent.
        } else if (state.grip == Grip.HANG && (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY() || event.getFrom().getZ() != event.getTo().getZ())) {
            state.correction = event.getFrom().clone(); state.correctionTick = tick;
            event.setCancelled(true); // Чистый поворот камеры в зацепе разрешён.
        }
    }

    private void tick() {
        tick++;
        for (var entry : new ArrayList<>(states.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey()); State state = entry.getValue();
            if (player == null) { states.remove(entry.getKey()); continue; }
            if (player.isDead()) { cleanup(player); continue; }
            if (state.coldUntil > tick) {
                if (!player.isFreezeTickingLocked()) player.lockFreezeTicks(true);
                if (player.getFreezeTicks() != player.getMaxFreezeTicks()) player.setFreezeTicks(player.getMaxFreezeTicks());
            } else if (state.coldUntil != 0) restoreCold(player, state);
            if (state.grip != Grip.NONE && (!player.isSneaking() || !items.holdsTool(player) || player.isFlying())) release(player);
            if (state.grip == Grip.HANG) {
                if (player.isOnGround() || state.wall == null || state.wall.getType().isAir()
                        || (tick % 4 == 0 && wall(player) == null)) release(player);
            } else if (state.grip == Grip.JUMP) {
                double y = player.getLocation().getY();
                if (WinterRules.descending(state.previousY, y, tick - state.launched)) {
                    Block nextWall = wall(player);
                    if (nextWall != null) latch(player, state, nextWall); else release(player);
                } else if (player.isOnGround() || tick - state.launched > 80) release(player);
                state.previousY = y;
            }
            if (state.frozenUntil > tick || state.grip == Grip.HANG) {
                holdPosition(player, state, state.frozenUntil > tick);
                player.setFallDistance(0);
                if (player.getVelocity().lengthSquared() > 1e-8) player.setVelocity(new Vector());
            } else if (state.grip == Grip.JUMP) {
                Vector velocity = player.getVelocity();
                if (Math.abs(velocity.getX()) + Math.abs(velocity.getZ()) > 1e-8) player.setVelocity(new Vector(0, velocity.getY(), 0));
            } else restoreControl(player, state);
            if (state.grip == Grip.NONE && state.frozenUntil <= tick && state.coldUntil == 0) states.remove(player.getUniqueId(), state);
        }
    }

    private void holdPosition(Player player, State state, boolean cameraLocked) {
        Location anchor = cameraLocked ? state.freezeAnchor : state.hangAnchor;
        if (anchor == null) return;
        Location actual = player.getLocation();
        if (!actual.getWorld().equals(anchor.getWorld())) {
            if (cameraLocked) state.freezeAnchor = actual; else release(player);
            return;
        }
        boolean positionChanged = actual.distanceSquared(anchor) > 1e-8;
        boolean lookChanged = cameraLocked && (actual.getYaw() != anchor.getYaw() || actual.getPitch() != anchor.getPitch());
        if (!positionChanged && !lookChanged) return;
        Location restore = anchor.clone();
        if (!cameraLocked) { restore.setYaw(actual.getYaw()); restore.setPitch(actual.getPitch()); }
        // PlayerMoveEvent имеет порог: мелкие движения камеры тоже исправляются,
        // но неподвижному игроку не отправляется телепорт каждый тик.
        state.correcting = true;
        try { player.teleport(restore); } finally { state.correcting = false; }
    }

    private void cleanup(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state != null) {
            restoreControl(player, state);
            if (state.coldUntil != 0) restoreCold(player, state);
        }
    }

    private void recover(Player player) {
        // После аварийного рестарта не оставлять сохранённые нулевую скорость/отключённую гравитацию.
        var data = player.getPersistentDataContainer();
        Float oldWalk = data.get(walk, PersistentDataType.FLOAT), oldFly = data.get(fly, PersistentDataType.FLOAT);
        Byte oldGravity = data.get(gravity, PersistentDataType.BYTE), oldCold = data.get(coldLock, PersistentDataType.BYTE);
        Integer oldTicks = data.get(coldTicks, PersistentDataType.INTEGER);
        if (oldWalk != null) player.setWalkSpeed(Float.isFinite(oldWalk) ? Math.clamp(oldWalk, -1f, 1f) : 0.2f);
        if (oldFly != null) player.setFlySpeed(Float.isFinite(oldFly) ? Math.clamp(oldFly, -1f, 1f) : 0.1f);
        if (oldGravity != null) player.setGravity(oldGravity != 0);
        if (oldCold != null) {
            player.lockFreezeTicks(oldCold != 0);
            player.setFreezeTicks(oldCold != 0 && oldTicks != null ? Math.max(0, oldTicks) : 0);
        }
        data.remove(walk); data.remove(fly); data.remove(gravity); data.remove(coldLock); data.remove(coldTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { release(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { release(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        State state = states.get(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (state != null && state.correcting) return;
        if (state != null && state.correction != null && tick - state.correctionTick <= 1 && to != null
                && to.getWorld().equals(state.correction.getWorld()) && to.distanceSquared(state.correction) < 1e-8) {
            state.correction = null;
            return;
        }
        if (state != null && state.frozenUntil > tick && to != null) state.freezeAnchor = to.clone();
        release(event.getPlayer());
    }
    @EventHandler public void world(PlayerChangedWorldEvent event) { release(event.getPlayer()); }
    @EventHandler public void join(PlayerJoinEvent event) { recover(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) { cleanup(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { cleanup(event.getEntity()); }

    void disable() {
        task.cancel();
        for (UUID id : new ArrayList<>(states.keySet())) { Player player = Bukkit.getPlayer(id); if (player != null) cleanup(player); }
        states.clear();
    }
}
