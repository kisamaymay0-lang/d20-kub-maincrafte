package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
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
        BlockDisplay iceLower, iceUpper;
    }
    private final WinterItems items;
    private final BlockData ice;
    private final Map<UUID, State> states = new HashMap<>();
    private final NamespacedKey walk, fly, gravity, coldLock, coldTicks;
    private final BukkitTask task;
    private long tick;

    WinterMovement(JavaPlugin plugin, WinterItems items) {
        this.items = items;
        ice = Bukkit.createBlockData(Material.ICE);
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
        // Заморозка запрещает движение и поворот, но не отключает гравитацию:
        // съевший рыбу в воздухе падает, а не зависает на месте.
        player.setGravity(state.frozenUntil > tick || state.grip == Grip.JUMP ? state.gravity : false);
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

    /** Падение с уже зажатым Shift: зацеп срабатывает сам, как только рядом появилась стена. */
    private void autoGrab() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || player.isFlying() || !player.isSneaking() || player.isOnGround()) continue;
            State existing = states.get(player.getUniqueId());
            if (!WinterRules.canAutoGrab(player.isSneaking(), !player.isOnGround() && !player.isFlying(),
                    items.holdsTool(player), existing != null && existing.frozenUntil > tick,
                    existing != null && existing.grip != Grip.NONE, player.getVelocity().getY() <= 0)) continue;
            Block wall = wall(player);
            if (wall == null) continue;
            State state = states.computeIfAbsent(player.getUniqueId(), ignored -> new State());
            if (state.grip == Grip.NONE && state.frozenUntil <= tick) latch(player, state, wall);
        }
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
        iceBreakEffects(player, state); // Осколки и звук ломающегося льда — только для красоты.
        if (!items.useClimb(player)) release(player); // Последний прыжок остаётся, но сломанной киркой больше не зацепиться.
    }

    /** Частицы «поломки льда» и звук в точке удара изморозью о стену. */
    private void iceBreakEffects(Player player, State state) {
        Location at = player.getLocation().add(0, 1.1, 0);
        Block wallBlock = state.wall;
        if (wallBlock != null && !wallBlock.getType().isAir()) at = nearestFace(wallBlock, player.getEyeLocation());
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.7f, 0.9f);
        player.getWorld().spawnParticle(Particle.BLOCK, at, 26, 0.35, 0.35, 0.35, 0.05, ice);
        player.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, at, 8, 0.3, 0.3, 0.3, 0.02);
    }

    /** Центр грани блока, обращённой к игроку. */
    private static Location nearestFace(Block block, Location eye) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        double dx = center.getX() - eye.getX(), dy = center.getY() - eye.getY(), dz = center.getZ() - eye.getZ();
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        Location face = center.clone();
        if (ax >= ay && ax >= az) face.setX(center.getX() - (dx < 0 ? -0.5 : 0.5));
        else if (ay >= az) face.setY(center.getY() - (dy < 0 ? -0.5 : 0.5));
        else face.setZ(center.getZ() - (dz < 0 ? -0.5 : 0.5));
        return face;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void move(PlayerMoveEvent event) {
        State state = states.get(event.getPlayer().getUniqueId());
        if (state == null || event.getTo() == null) return;
        if (state.frozenUntil > tick) {
            Location from = event.getFrom(), to = event.getTo();
            // Падение по гравитации заморозка не отменяет: разрешаем только строго
            // вертикальное движение вниз, при этом взгляд остаётся зафиксированным.
            boolean pureFall = to.getWorld().equals(from.getWorld())
                    && to.getY() < from.getY() - 1e-9
                    && Math.abs(to.getX() - from.getX()) <= 1e-9
                    && Math.abs(to.getZ() - from.getZ()) <= 1e-9;
            if (pureFall && state.freezeAnchor != null
                    && (to.getYaw() != state.freezeAnchor.getYaw() || to.getPitch() != state.freezeAnchor.getPitch())) {
                Location locked = to.clone();
                locked.setYaw(state.freezeAnchor.getYaw());
                locked.setPitch(state.freezeAnchor.getPitch());
                event.setTo(locked);
            } else if (!pureFall) {
                state.correction = event.getFrom().clone(); state.correctionTick = tick;
                event.setCancelled(true); // Включая yaw/pitch. Не вызываем цепочку собственных PlayerTeleportEvent.
            }
        } else if (state.grip == Grip.HANG && state.hangAnchor != null && !event.isCancelled()
                && event.getTo().getWorld().equals(state.hangAnchor.getWorld())
                && event.getTo().distanceSquared(state.hangAnchor) > 1e-8) {
            // Меняем только XYZ. Отмена всего события возвращала старый yaw/pitch,
            // из-за чего при движении мышью вместе с телом камера застревала.
            Location corrected = WinterRules.anchoredLook(state.hangAnchor, event.getTo());
            state.correction = corrected.clone(); state.correctionTick = tick;
            event.setTo(corrected);
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
            if (state.frozenUntil > tick) {
                // Пока заморозка действует: на земле держим позицию, в воздухе разрешаем
                // падение (гравитация) и следуем якорем за игроком, гася только горизонталь.
                player.setFallDistance(0);
                Location actual = player.getLocation();
                Location anchor = state.freezeAnchor;
                boolean anchored = anchor != null && anchor.getWorld().equals(actual.getWorld());
                boolean airborne = !player.isOnGround() && !player.isFlying() && !player.isInsideVehicle();
                if (!anchored || airborne || actual.getY() < anchor.getY() - 1e-8) {
                    state.freezeAnchor = actual.clone();
                    Vector velocity = player.getVelocity();
                    if (Math.abs(velocity.getX()) + Math.abs(velocity.getZ()) > 1e-8) player.setVelocity(new Vector(0, velocity.getY(), 0));
                } else {
                    holdFrozenPosition(player, state);
                    if (player.getVelocity().lengthSquared() > 1e-8) player.setVelocity(new Vector());
                }
            } else if (state.grip == Grip.HANG) {
                player.setFallDistance(0);
                if (player.getVelocity().lengthSquared() > 1e-8) player.setVelocity(new Vector());
            } else if (state.grip == Grip.JUMP) {
                Vector velocity = player.getVelocity();
                if (Math.abs(velocity.getX()) + Math.abs(velocity.getZ()) > 1e-8) player.setVelocity(new Vector(0, velocity.getY(), 0));
            } else restoreControl(player, state);
            if (state.frozenUntil > tick) updateIce(player, state);
            else clearIce(state);
            if (state.grip == Grip.NONE && state.frozenUntil <= tick && state.coldUntil == 0) states.remove(player.getUniqueId(), state);
        }
        autoGrab();
    }

    private void holdFrozenPosition(Player player, State state) {
        Location anchor = state.freezeAnchor;
        if (anchor == null) return;
        Location actual = player.getLocation();
        if (!actual.getWorld().equals(anchor.getWorld())) {
            state.freezeAnchor = actual;
            return;
        }
        boolean positionChanged = actual.distanceSquared(anchor) > 1e-8;
        boolean lookChanged = actual.getYaw() != anchor.getYaw() || actual.getPitch() != anchor.getPitch();
        if (!positionChanged && !lookChanged) return;
        Location restore = anchor.clone();
        // PlayerMoveEvent имеет порог: мелкие движения камеры тоже исправляются,
        // но неподвижному игроку не отправляется телепорт каждый тик.
        state.correcting = true;
        try { player.teleport(restore); } finally { state.correcting = false; }
    }

    /** Два декоративных «фантомных» блока льда на весь рост игрока: без хитбокса и урона. */
    private void updateIce(Player player, State state) {
        if (state.iceLower == null || !state.iceLower.isValid() || state.iceUpper == null || !state.iceUpper.isValid()) {
            clearIce(state);
            Location at = player.getLocation();
            state.iceLower = player.getWorld().spawn(at, BlockDisplay.class, display -> configureIce(display));
            state.iceUpper = player.getWorld().spawn(at, BlockDisplay.class, display -> configureIce(display));
        }
        Location base = state.freezeAnchor != null && state.freezeAnchor.getWorld().equals(player.getWorld())
                ? state.freezeAnchor : player.getLocation();
        placeIce(state.iceLower, base, 0.5);
        placeIce(state.iceUpper, base, 1.5);
    }

    private void configureIce(BlockDisplay display) {
        display.setBlock(ice);
        display.setPersistent(false);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setTeleportDuration(0);
        display.setInterpolationDelay(0);
    }

    private void placeIce(BlockDisplay display, Location base, double up) {
        Location target = base.clone();
        target.setY(target.getY() + up);
        target.setYaw(0); target.setPitch(0);
        if (!display.getLocation().getWorld().equals(target.getWorld())
                || display.getLocation().distanceSquared(target) > 1e-6) display.teleport(target);
    }

    private static void clearIce(State state) {
        if (state.iceLower != null) { state.iceLower.remove(); state.iceLower = null; }
        if (state.iceUpper != null) { state.iceUpper.remove(); state.iceUpper = null; }
    }

    private void cleanup(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state != null) {
            clearIce(state);
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
