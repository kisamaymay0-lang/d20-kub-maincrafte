package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Зацеп по механике мода Gouge (https://modrinth.com/mod/gouge) и временная неподвижность.
 *
 * <p>ПКМ изморозью по стене в падении: инструмент врезается в грань и трение гасит падение.
 * Твёрдый блок (тот, что требует верный инструмент для дропа) умножает скорость падения на
 * {@code 0.90 - hardness * 0.03} каждый тик; когда скорость становится меньше 0.08, игрок
 * замирает и висит, пока не истечёт время захвата — у изморози это 10 секунд, как у алмазной
 * кирки в моде. Мягкий блок (земля, песок, дерево) не держит: игрок скользит вниз с постоянной
 * скоростью, зато падение после такого скольжения слабее вдвое и не больше 3 сердец.
 *
 * <p>Двойной присед в окне 0.7 с — прыжок от стены: скорость {@code normal * 1.4 + 1.1 вверх}.
 *
 * <p>Отличия от мода: прочность изморози тратится ТОЛЬКО на прыжок (4 из 16) — ни удар о стену,
 * ни вис, ни скольжение её не отнимают; чар Grip и Momentum здесь нет, поэтому время захвата
 * и кулдаун проскальзывания заданы числами (и настраиваются в config.yml).
 *
 * <p>Высоту игрока при скольжении и вис ведёт плагин, а не клиент: у клиента своя гравитация,
 * и измеренная по позиции скорость падения никогда не дошла бы до нуля. Поэтому трение и
 * скорость скольжения считаются здесь, а PlayerMoveEvent дотягивает игрока до нужной высоты.
 *
 * <p>Обрабатываются только игроки с активным состоянием: без зацепа и заморозки задача ничего
 * не делает.
 */
final class WinterMovement implements Listener {
    /** Зацеп: SLIDE — скольжение по твёрдому блоку, HANG — замок (трение погасило падение),
     *  SOFT — скольжение по мягкому блоку без замка. */
    private enum Grip { NONE, SLIDE, HANG, SOFT }

    /** Настройки механики из config.yml; по умолчанию — значения gouge.toml. */
    private record Tuning(double reach, double clearance, double drift, int hangTicks, int slipTicks,
                          int jumpWindowTicks, int jumpCooldownTicks, double jumpForward, double jumpUp,
                          double softFallDamage, double softFallDamageCap,
                          Set<Material> alwaysHard, Set<Material> alwaysSoft) { }

    private static final class State {
        Grip grip = Grip.NONE;
        Block wall;
        BlockFace face;
        Location driftAnchor;
        Location hangAnchor;
        long hangUntil;
        long slipUntil;
        long sneakRelease = -1;
        long softFallUntil;
        double previousY;
        boolean sliding;
        double slideSpeed;
        double targetY;
        Location correction;
        long correctionTick;
        boolean correcting;
        Location freezeAnchor;
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
    private final Tuning tuning;
    private final BlockData ice;
    private final ItemStack rimeParticle;
    private final Map<UUID, State> states = new HashMap<>();
    /** Кого ударили изморозью по льду: скользит по блокам, пока не остановится. */
    private final Map<UUID, Long> slidingUntil = new HashMap<>();
    private final NamespacedKey walk, fly, gravity, coldLock, coldTicks;
    private final BukkitTask task;
    private long tick;

    WinterMovement(JavaPlugin plugin, WinterItems items) {
        this.items = items;
        tuning = tuning(plugin);
        ice = Bukkit.createBlockData(Material.ICE);
        rimeParticle = items.create(WinterItems.Kind.TOOL);
        walk = new NamespacedKey(plugin, "winter_saved_walk");
        fly = new NamespacedKey(plugin, "winter_saved_fly");
        gravity = new NamespacedKey(plugin, "winter_saved_gravity");
        coldLock = new NamespacedKey(plugin, "winter_saved_cold_lock");
        coldTicks = new NamespacedKey(plugin, "winter_saved_cold_ticks");
        for (Player player : Bukkit.getOnlinePlayers()) recover(player);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private static Tuning tuning(JavaPlugin plugin) {
        var config = plugin.getConfig();
        return new Tuning(
                Math.clamp(config.getDouble("gouge.reach", WinterRules.GRAB_REACH), 0.5, 32.0),
                Math.clamp(config.getDouble("gouge.min-fall-clearance", WinterRules.MIN_FALL_CLEARANCE), 0.0, 32.0),
                Math.clamp(config.getDouble("gouge.max-drift", WinterRules.MAX_DRIFT), 0.0, 16.0),
                WinterRules.ticks(config.getDouble("gouge.hang-seconds", WinterRules.HANG_TICKS / 20.0), 0, 3600),
                WinterRules.ticks(config.getDouble("gouge.slip-cooldown-seconds", WinterRules.SLIP_COOLDOWN_TICKS / 20.0), 0, 600),
                WinterRules.ticks(config.getDouble("gouge.wall-jump.window-seconds", WinterRules.WALL_JUMP_WINDOW_TICKS / 20.0), 1, 100),
                WinterRules.ticks(config.getDouble("gouge.wall-jump.cooldown-seconds", WinterRules.WALL_JUMP_COOLDOWN_TICKS / 20.0), 0, 600),
                Math.clamp(config.getDouble("gouge.wall-jump.forward-boost", WinterRules.WALL_JUMP_FORWARD_BOOST), 0.0, 5.0),
                Math.clamp(config.getDouble("gouge.wall-jump.upward-boost", WinterRules.WALL_JUMP_UPWARD_BOOST), 0.0, 5.0),
                Math.clamp(config.getDouble("gouge.soft-fall-damage", WinterRules.SOFT_FALL_DAMAGE), 0.0, 1.0),
                Math.clamp(config.getDouble("gouge.soft-fall-damage-cap", WinterRules.SOFT_FALL_DAMAGE_CAP), 0.0, 40.0),
                materials(plugin, "gouge.always-hard"),
                materials(plugin, "gouge.always-soft"));
    }

    private static Set<Material> materials(JavaPlugin plugin, String path) {
        Set<Material> result = new HashSet<>();
        for (String name : plugin.getConfig().getStringList(path)) {
            Material material = Material.matchMaterial(name);
            if (material != null) result.add(material);
        }
        return result;
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
        state.wall = null; state.face = null; state.hangAnchor = null; state.hangUntil = 0;
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
        // В зацепе вертикаль считает плагин — трение и скольжение гасят падение здесь.
        player.setGravity(state.frozenUntil > tick ? state.gravity : false);
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

    /** Отпустить зацеп: управление возвращается, а мягкое скольжение ещё несколько тиков
     *  смягчает падение — приземление происходит уже после отпускания. */
    void release(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null || state.grip == Grip.NONE) return;
        if (state.grip == Grip.SOFT) state.softFallUntil = tick + WinterRules.SOFT_FALL_GRACE_TICKS;
        state.grip = Grip.NONE; state.wall = null; state.face = null;
        state.driftAnchor = null; state.hangAnchor = null; state.hangUntil = 0;
        state.sliding = false; state.slideSpeed = 0;
        if (state.frozenUntil <= tick) restoreControl(player, state);
    }

    /** Разбить заморозку ударом изморози: лёд исчезает, управление возвращается,
     *  «озноб» (замороженные тики) остаётся до своего обычного истечения. */
    private void shatter(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        state.frozenUntil = 0;
        state.grip = Grip.NONE; state.wall = null; state.face = null;
        state.driftAnchor = null; state.hangAnchor = null; state.hangUntil = 0;
        clearIce(state);
        restoreControl(player, state);
    }

    /** Луч взгляда до грани блока, а если он мимо — запасной горизонтальный луч.
     *  Ровно так же ищет цель raycast в Gouge: смотреть можно и вверх по стене. */
    private RayTraceResult sight(Player player) {
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, look, tuning.reach(), FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitBlock() != null) return hit;
        Vector flat = look.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) return null;
        hit = player.getWorld().rayTraceBlocks(eye, flat.normalize(), tuning.reach(), FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null ? hit : null;
    }

    /** Под ногами должен быть открытый воздух: обычный прыжок зацепа не даёт. */
    private boolean clearance(Player player) {
        if (tuning.clearance() <= 0) return true;
        RayTraceResult hit = player.getWorld().rayTraceBlocks(player.getLocation(), new Vector(0, -1, 0),
                tuning.clearance(), FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    /** Ванильная прочность блока (destroy speed); у неразрушимых она отрицательная. */
    private static double hardness(Block block) {
        Material type = block.getType();
        return type.isBlock() ? type.getHardness() : -1;
    }

    /** Твёрдый блок — тот, что требует верный инструмент для дропа, как в Gouge.
     *  Списки в config.yml переопределяют это правило для любых блоков. */
    private boolean hard(Block block) {
        Material type = block.getType();
        if (tuning.alwaysHard().contains(type)) return true;
        if (tuning.alwaysSoft().contains(type)) return false;
        return block.getBlockData().requiresCorrectToolForDrops();
    }

    /** ПКМ изморозью по стене в падении: инструмент врезается в грань. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        boolean airborne = !player.isOnGround() && !player.isFlying() && player.getGameMode() != GameMode.SPECTATOR;
        if (!WinterRules.canGrab(items.holdsTool(player), airborne, player.getVelocity().getY() < 0, clearance(player),
                state != null && state.slipUntil > tick, state != null && state.grip != Grip.NONE,
                state != null && state.frozenUntil > tick)) return;
        RayTraceResult hit = sight(player);
        Block wall = hit == null ? null : hit.getHitBlock();
        if (wall == null || wall.getType().isAir() || hardness(wall) < 0) return;
        attach(player, states.computeIfAbsent(player.getUniqueId(), ignored -> new State()), wall, hit.getHitBlockFace());
        event.setCancelled(true);
    }

    /** Зацепились: включаем удержание и запоминаем точку захвата для ограничения сноса. */
    private void attach(Player player, State state, Block wall, BlockFace face) {
        state.grip = Grip.SLIDE; // твёрдость блока и скорость падения уточнит первый же тик физики
        state.wall = wall; state.face = face;
        state.driftAnchor = player.getLocation().clone();
        state.hangAnchor = null; state.hangUntil = 0;
        state.sliding = false; state.slideSpeed = 0;
        state.previousY = player.getLocation().getY();
        control(player, state);
        impact(player, wall);
    }

    /** Звук и частицы удара о грань: звук блока плюс осколки самой изморози. */
    private void impact(Player player, Block wall) {
        Location at = nearestFace(wall, player.getEyeLocation());
        player.getWorld().playSound(at, wall.getBlockData().getSoundGroup().getBreakSound(), 1.2f, 0.6f);
        player.getWorld().spawnParticle(Particle.BLOCK, at, 18, 0.25, 0.25, 0.25, 0.0, wall.getBlockData());
        player.getWorld().spawnParticle(Particle.ITEM, at, 12, 0.25, 0.25, 0.25, 0.05, rimeParticle);
    }

    /** Второй присед в окне — прыжок от стены. Первое нажатие только отпускает присед и запоминает тик. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        if (!event.isSneaking()) { state.sneakRelease = tick; return; }
        if (state.grip == Grip.NONE || state.frozenUntil > tick) return;
        if (!WinterRules.doubleTap(state.sneakRelease, tick, tuning.jumpWindowTicks())) return;
        state.sneakRelease = -1;
        wallJump(player, state);
    }

    /** Прыжок от стены: скорость normal*1.4 + 1.1 вверх (mod wallKick) и 4 прочности изморози. */
    private void wallJump(Player player, State state) {
        Block wall = state.wall;
        BlockFace face = state.face;
        Vector normal = face == null ? new Vector(0, 1, 0) : face.getDirection();
        if (wall != null) iceBreakEffects(player, wall);
        release(player);
        state.slipUntil = Math.max(state.slipUntil, tick + tuning.jumpCooldownTicks());
        player.setFallDistance(0);
        player.setVelocity(normal.multiply(tuning.jumpForward()).setY(tuning.jumpUp()));
        // Прочность изморози тратится только на прыжок: 4 из 16, последний прыжок ломает инструмент.
        items.useClimb(player);
    }

    /** Осколки текстуры самой изморози и звук в точке удара о стену. */
    private void iceBreakEffects(Player player, Block wall) {
        Location at = player.getLocation().add(0, 1.1, 0);
        if (!wall.getType().isAir()) at = nearestFace(wall, player.getEyeLocation());
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.7f, 0.9f);
        // Частицы предмета изморози из руки, а не снега/льда.
        player.getWorld().spawnParticle(Particle.ITEM, at, 22, 0.35, 0.35, 0.35, 0.04, rimeParticle);
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

    /** Один тик зацепа: держим грань в поле взгляда, считаем трение и скольжение. */
    private void grip(Player player, State state) {
        if (player.isDead() || player.isFlying() || player.getGameMode() == GameMode.SPECTATOR
                || player.isOnGround() || !items.holdsTool(player)) { release(player); return; }
        RayTraceResult hit = sight(player);
        Block wall = hit == null ? null : hit.getHitBlock();
        if (wall == null || wall.getType().isAir()) { release(player); return; } // грань ушла из-под взгляда
        double hardness = hardness(wall);
        if (hardness < 0) { release(player); return; } // неразрушимый блок не держит инструмент
        state.wall = wall; state.face = hit.getHitBlockFace();
        Location now = player.getLocation();
        double verticalSpeed = now.getY() - state.previousY;
        state.previousY = now.getY();
        if (state.driftAnchor == null || !state.driftAnchor.getWorld().equals(now.getWorld())) state.driftAnchor = now.clone();
        else {
            double dx = now.getX() - state.driftAnchor.getX(), dz = now.getZ() - state.driftAnchor.getZ();
            if (dx * dx + dz * dz > tuning.drift() * tuning.drift()) { release(player); return; } // снос в сторону
        }
        if (state.grip == Grip.HANG) { hangTick(player, state); return; }
        if (hard(wall)) hardTick(player, state, hardness, verticalSpeed);
        else softTick(player, state, hardness, verticalSpeed);
    }

    /** Вис: держим позицию (замок в PlayerMoveEvent), пока не истечёт время захвата. */
    private void hangTick(Player player, State state) {
        if (state.hangUntil <= tick) { slip(player, state); return; }
        player.setFallDistance(0);
        player.setGravity(false);
        if (state.hangAnchor == null) state.hangAnchor = player.getLocation().clone();
        if (tick % 20 == 10) player.getWorld().playSound(player.getLocation(), Sound.BLOCK_CHAIN_STEP, 0.4f, 0.8f);
        if (player.getVelocity().lengthSquared() > 1e-8) player.setVelocity(new Vector());
    }

    /** Твёрдый блок: трение гасит падение, при полной остановке — замок до конца времени захвата. */
    private void hardTick(Player player, State state, double hardness, double verticalSpeed) {
        player.setFallDistance(0); // держит инструмент: падение не копится
        // Первый тик берёт фактическую скорость падения, дальше скорость считает плагин:
        // у клиента своя гравитация, и измеренная скорость никогда не дошла бы до нуля.
        double raw = state.sliding ? state.slideSpeed : Math.min(verticalSpeed, 0);
        if (WinterRules.locksInPlace(raw)) { lock(player, state); return; }
        state.sliding = true;
        state.slideSpeed = raw * WinterRules.hardFriction(hardness);
        state.grip = Grip.SLIDE;
        state.hangUntil = 0;
        player.setGravity(false);
        track(player, state);
    }

    /** Мягкий блок: замка нет, игрок скользит вниз с постоянной скоростью, но падает мягче. */
    private void softTick(Player player, State state, double hardness, double verticalSpeed) {
        double raw = state.sliding ? state.slideSpeed : Math.min(verticalSpeed, 0);
        if (raw >= 0) {
            // Мягкий блок не держит: пока падения нет, игрок падает сам, а зацеп ждёт.
            state.grip = Grip.SOFT;
            state.sliding = false;
            player.setGravity(state.gravity);
            return;
        }
        state.sliding = true;
        state.slideSpeed = -WinterRules.softSlideSpeed(hardness);
        state.grip = Grip.SOFT;
        state.hangUntil = 0;
        player.setGravity(false);
        track(player, state);
    }

    /** Трение погасило падение: игрок замирает на месте и висит до конца времени захвата. */
    private void lock(Player player, State state) {
        state.grip = Grip.HANG;
        state.sliding = false;
        state.slideSpeed = 0;
        state.hangAnchor = player.getLocation().clone();
        state.hangUntil = tick + tuning.hangTicks();
        player.setVelocity(new Vector());
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_CHAIN_STEP, 0.5f, 0.9f);
    }

    /** Скольжение ведёт плагин: X/Z остаются за игроком, а высоту задаёт посчитанная скорость.
     *  PlayerMoveEvent дотянет игрока до неё, поэтому клиентская гравитация ничего не решает. */
    private void track(Player player, State state) {
        state.targetY = player.getLocation().getY() + state.slideSpeed;
        Vector velocity = player.getVelocity();
        player.setVelocity(new Vector(velocity.getX() * WinterRules.HORIZONTAL_DAMPING, state.slideSpeed,
                velocity.getZ() * WinterRules.HORIZONTAL_DAMPING));
    }

    /** Трение погасило падение, но время захвата истекло — изморозь соскальзывает. */
    private void slip(Player player, State state) {
        release(player);
        state.slipUntil = tick + tuning.slipTicks();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.4f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 30, 0.3, 0.3, 0.3, 0.2);
        player.sendActionBar("§bИзморозь соскользнула со стены");
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
        } else if (state.sliding && (state.grip == Grip.SLIDE || state.grip == Grip.SOFT) && !event.isCancelled()
                && Math.abs(event.getTo().getY() - state.targetY) > 1e-6) {
            // Скольжение: высоту задаёт плагин, X/Z и камера остаются за игроком.
            Location corrected = event.getTo().clone();
            corrected.setY(state.targetY);
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
            if (state.grip != Grip.NONE) grip(player, state);
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
                updateIce(player, state);
            } else {
                clearIce(state);
                if (state.grip == Grip.NONE) restoreControl(player, state);
            }
            if (state.grip == Grip.NONE && state.frozenUntil <= tick && state.coldUntil == 0
                    && state.softFallUntil <= tick && state.slipUntil <= tick) states.remove(entry.getKey(), state);
        }
        slide();
    }

    /** «Керлинг» изморозью: пока действует скольжение, гасим трение земли,
     *  чтобы игрок катился по блокам, как по льду. */
    private void slide() {
        if (slidingUntil.isEmpty()) return;
        var iterator = slidingUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue() <= tick) { iterator.remove(); continue; }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || player.isDead() || !player.isOnline()) { iterator.remove(); continue; }
            if (!player.isOnGround() || player.isFlying() || player.isInsideVehicle()) continue;
            Vector velocity = player.getVelocity();
            double speed = Math.hypot(velocity.getX(), velocity.getZ());
            if (speed < 0.03) { iterator.remove(); continue; }
            // Обычное трение земли оставляет ~0.55 скорости за тик, лёд ~0.89.
            double scale = Math.min(speed * 1.6, 1.25) / speed;
            velocity.setX(velocity.getX() * scale);
            velocity.setZ(velocity.getZ() * scale);
            player.setVelocity(velocity);
        }
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
        placeIce(state.iceLower, base, 0.0);
        placeIce(state.iceUpper, base, 1.0);
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
        // BlockDisplay растёт из нижнего угла блока (у ItemDisplay позиция — центр).
        // Чтобы колонна льда стояла ровно вокруг игрока, сдвигаем полблока по X/Z
        // и ставим нижний куб от ног (up=0), верхний — на блок выше (up=1).
        Location target = base.clone();
        target.setX(target.getX() - 0.5);
        target.setZ(target.getZ() - 0.5);
        target.setY(target.getY() + up);
        target.setYaw(0); target.setPitch(0);
        if (!display.getLocation().getWorld().equals(target.getWorld())
                || display.getLocation().distanceSquared(target) > 1e-6
                || display.getLocation().getYaw() != 0
                || display.getLocation().getPitch() != 0) display.teleport(target);
    }

    private static void clearIce(State state) {
        if (state.iceLower != null) { state.iceLower.remove(); state.iceLower = null; }
        if (state.iceUpper != null) { state.iceUpper.remove(); state.iceUpper = null; }
    }

    private void cleanup(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state != null) {
            clearIce(state);
            state.grip = Grip.NONE; state.wall = null; state.face = null;
            state.driftAnchor = null; state.hangAnchor = null; state.hangUntil = 0;
            state.sliding = false; state.slideSpeed = 0;
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

    /** Пока действует заморозка изморозью, игрок не получает НИКАКОГО урона, кроме
     *  ванильного «мороза» (FREEZE), которым сама изморозь и бьёт: можно взорвать
     *  динамит вплотную или упасть в лаву — урон придёт только от заморозки.
     *  Исключение — удар изморозью по замороженному: урона нет, но лёд разбивается,
     *  игрок отталкивается и скользит по блокам, как по льду.
     *  Падение после мягкого скольжения слабее вдвое и не больше 3 сердец, как в Gouge. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        if (event.getCause() == DamageCause.FALL && state.softFallUntil >= tick) {
            event.setDamage(WinterRules.softFallDamage(event.getDamage(), tuning.softFallDamage(), tuning.softFallDamageCap()));
            state.softFallUntil = 0;
            return;
        }
        if (state.frozenUntil <= tick) return;
        if (event.getCause() != DamageCause.FREEZE) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent byEntity
                    && byEntity.getDamager() instanceof Player attacker) {
                WinterItems.Kind kind = items.kind(attacker.getInventory().getItemInMainHand());
                if (kind == WinterItems.Kind.RAW || kind == WinterItems.Kind.DEPLETED) rimePush(attacker, player);
            }
        }
    }

    private void rimePush(Player attacker, Player victim) {
        shatter(victim);
        Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() < 1e-6) direction = attacker.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 1e-6) direction = new Vector(0, 0, 1);
        direction.normalize();
        victim.setVelocity(direction.multiply(0.7).setY(0.15));
        slidingUntil.put(victim.getUniqueId(), tick + 80);
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6f, 1.4f);
        victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 25, 0.35, 0.6, 0.35, 0.05);
    }

    void disable() {
        task.cancel();
        for (UUID id : new ArrayList<>(states.keySet())) { Player player = Bukkit.getPlayer(id); if (player != null) cleanup(player); }
        states.clear();
        slidingUntil.clear();
    }
}
