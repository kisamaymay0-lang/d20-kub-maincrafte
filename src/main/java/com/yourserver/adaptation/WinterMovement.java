package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundGroup;
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
import org.bukkit.event.player.PlayerInputEvent;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * Зацеп по механике мода Gouge (https://modrinth.com/mod/gouge) и временная неподвижность.
 *
 * <p>ПКМ изморозью по стене в падении: инструмент врезается в грань, и игрок скользит вдоль
 * неё. Скорость входа — та, с которой игрок подлетел к стене, поэтому чем выше было падение,
 * тем дольше длится торможение. Дальше трение отнимает скорость каждый тик: твёрдый блок
 * держит крепче и гасит скольжение почти в ноль, мягкий только притормаживает и оставляет
 * высокую «ползущую» скорость — игрок продолжает сползать. Присед на твёрдом блоке
 * останавливает совсем, на мягком — не спасает; пробел на зажатом приседе выбрасывает
 * игрока от стены с силой обычного прыжка.
 *
 * <p>Зацепиться можно и без ПКМ: подпрыгнуть у стены и зажать присед — «прыгнул с пола,
 * зажал Shift и полез вверх». Пока инструмент скользит быстро, он стирается по 1 прочности
 * за блок, прыжок стоит 4 прочности, а в креативе прочность не тратится вовсе.
 *
 * <p>Движение задаётся только скоростью: гравитацию на зацеп плагин выключает, а позиции
 * в PlayerMoveEvent не переписывает. Клиент и сервер считают одну и ту же физику, поэтому
 * сервер не ругается на «moved wrongly», не рассылает телепорты, а ванильная проверка
 * «игрок висит в воздухе» при нулевой гравитации не доводит до кика за полёт.
 *
 * <p>Обрабатываются только игроки в зацепе и с заморозкой; самозахват приседом проверяется
 * каждый тик, но выходит из строя на первой же проверке.
 */
final class WinterMovement implements Listener {
    /** Зацеп: SLIDE — скольжение по стене, HOLD — присед держит игрока на месте. */
    private enum Grip { NONE, SLIDE, HOLD }

    /** Настройки механики из config.yml; по умолчанию — значения gouge.toml. */
    private record Tuning(double reach, double clearance, double drift, int hangTicks, int slideCooldownTicks,
                          double jumpForward, double jumpUp,
                          double softFallDamage, double softFallDamageCap,
                          double slideEntry, double hardFriction, double hardFrictionRamp, double hardFrictionScale,
                          double hardFrictionMax, double hardFloor, double holdFloor,
                          double softFriction, double softFrictionRamp, double softFloor,
                          double slideDamageSpeed, int slideDamagePerBlock,
                          Set<Material> alwaysHard, Set<Material> alwaysSoft) { }

    private static final class State {
        Grip grip = Grip.NONE;
        Block wall;
        BlockFace face;
        boolean hardWall;         // блок требует верный инструмент: на нём присед держит
        boolean entered;          // первый тик скольжения уже посчитан
        boolean holding;          // присед зажат: игрок стоит на инструменте
        Location driftAnchor;
        long hangUntil;           // конец виса (0 — без ограничения)
        long slipUntil;
        long softFallUntil;
        double slideSpeed;        // скорость скольжения вниз, блоков за тик
        double slideCharge;       // накопленный расход прочности за скольжение
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
                WinterRules.ticks(config.getDouble("gouge.slide-cooldown-seconds", WinterRules.SLIDE_COOLDOWN_TICKS / 20.0), 0, 600),
                Math.clamp(config.getDouble("gouge.wall-jump.forward-boost", WinterRules.WALL_JUMP_FORWARD_BOOST), 0.0, 5.0),
                Math.clamp(config.getDouble("gouge.wall-jump.upward-boost", WinterRules.WALL_JUMP_UPWARD_BOOST), 0.0, 5.0),
                Math.clamp(config.getDouble("gouge.soft-fall-damage", WinterRules.SOFT_FALL_DAMAGE), 0.0, 1.0),
                Math.clamp(config.getDouble("gouge.soft-fall-damage-cap", WinterRules.SOFT_FALL_DAMAGE_CAP), 0.0, 40.0),
                Math.clamp(config.getDouble("gouge.slide.entry-speed", WinterRules.SLIDE_ENTRY_SPEED), 0.05, 3.0),
                Math.clamp(config.getDouble("gouge.slide.hard-friction", WinterRules.SLIDE_HARD_FRICTION), 0.0, 1.0),
                Math.clamp(config.getDouble("gouge.slide.hard-friction-ramp", WinterRules.SLIDE_HARD_FRICTION_RAMP), 0.0, 1.0),
                Math.clamp(config.getDouble("gouge.slide.hard-friction-hardness", WinterRules.SLIDE_HARD_FRICTION_HARDNESS), 0.0, 0.5),
                Math.clamp(config.getDouble("gouge.slide.hard-friction-max", WinterRules.SLIDE_HARD_FRICTION_MAX), 0.0, 1.0),
                Math.clamp(config.getDouble("gouge.slide.hard-floor-speed", WinterRules.SLIDE_HARD_FLOOR_SPEED), 0.01, 3.0),
                Math.clamp(config.getDouble("gouge.slide.hold-floor-speed", WinterRules.SLIDE_HOLD_FLOOR_SPEED), 0.0, 3.0),
                Math.clamp(config.getDouble("gouge.slide.soft-friction", WinterRules.SLIDE_SOFT_FRICTION), 0.0, 1.0),
                Math.clamp(config.getDouble("gouge.slide.soft-friction-ramp", WinterRules.SLIDE_SOFT_FRICTION_RAMP), 0.0, 1.0),
                Math.clamp(config.getDouble("gouge.slide.soft-floor-speed", WinterRules.SLIDE_SOFT_FLOOR_SPEED), 0.01, 3.0),
                Math.clamp(config.getDouble("gouge.slide-durability.min-speed", WinterRules.SLIDE_DAMAGE_MIN_SPEED), 0.0, 3.0),
                Math.clamp(config.getInt("gouge.slide-durability.per-block", WinterRules.SLIDE_DAMAGE_PER_BLOCK), 0, 64),
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
        state.wall = null; state.face = null; state.hangUntil = 0;
        state.holding = false; state.entered = false; state.slideSpeed = 0; state.slideCharge = 0;
        control(player, state);
        player.lockFreezeTicks(true);
        player.setFreezeTicks(player.getMaxFreezeTicks()); // Ванильный FREEZE урон и иммунитеты остаются у Minecraft.
        player.setFallDistance(0);
        player.setVelocity(new Vector());
    }

    /** Запомнить исходные скорости и гравитацию один раз за сессию удержания: их вернёт
     *  restoreControl, а в PDC они лежат на случай аварийного перезапуска сервера. */
    private void storeControl(Player player, State state) {
        if (state.controlled) return;
        state.walkSpeed = player.getWalkSpeed(); state.flySpeed = player.getFlySpeed();
        state.gravity = player.hasGravity();
        var data = player.getPersistentDataContainer();
        data.set(walk, PersistentDataType.FLOAT, state.walkSpeed);
        data.set(fly, PersistentDataType.FLOAT, state.flySpeed);
        data.set(gravity, PersistentDataType.BYTE, (byte) (state.gravity ? 1 : 0));
        state.controlled = true;
    }

    /** Заморозка: движение и поворот запрещены, ходьба и полёт — по нулям.
     *  Гравитацию не отключаем: съевший рыбу в воздухе падает, а не висит на месте. */
    private void control(Player player, State state) {
        storeControl(player, state);
        player.setWalkSpeed(0); player.setFlySpeed(0);
        player.setGravity(state.frozenUntil > tick ? state.gravity : false);
    }

    /** Зацеп: ходьбу гасим только в висе (присед), зато гравитацию — на весь зацеп.
     *  При нулевой гравитации клиент и сервер считают одну физику, а ванильная проверка
     *  «игрок висит в воздухе» никогда не доходит до кика за полёт. */
    private void gripControl(Player player, State state) {
        storeControl(player, state);
        player.setWalkSpeed(state.holding ? 0 : state.walkSpeed);
        player.setFlySpeed(state.flySpeed);
        player.setGravity(false);
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
    void release(Player player) { release(player, false); }

    /** Отпустить зацеп. После скольжения изморозь уходит на откат — кроме прыжка от стены
     *  (иначе не получится карабкаться) и креатива (там откат не действует). */
    private void release(Player player, boolean jumped) {
        State state = states.get(player.getUniqueId());
        if (state == null || state.grip == Grip.NONE) return;
        if (!jumped && !creative(player)) state.slipUntil = tick + tuning.slideCooldownTicks();
        if (!state.hardWall) state.softFallUntil = tick + WinterRules.SOFT_FALL_GRACE_TICKS;
        state.grip = Grip.NONE; state.wall = null; state.face = null;
        state.driftAnchor = null; state.hangUntil = 0;
        state.holding = false; state.entered = false; state.slideSpeed = 0; state.slideCharge = 0;
        if (state.frozenUntil <= tick) restoreControl(player, state);
    }

    /** Разбить заморозку ударом изморози: лёд исчезает, управление возвращается,
     *  «озноб» (замороженные тики) остаётся до своего обычного истечения. */
    private void shatter(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        state.frozenUntil = 0;
        state.grip = Grip.NONE; state.wall = null; state.face = null;
        state.driftAnchor = null; state.hangUntil = 0;
        state.holding = false; state.entered = false; state.slideSpeed = 0; state.slideCharge = 0;
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
        boolean cooling = state != null && state.slipUntil > tick;
        // Падение: скорость вниз или уже накопленная высота падения (mod: deltaMovement.y < 0).
        boolean falling = player.getVelocity().getY() < 0 || player.getFallDistance() > 0;
        if (cooling && items.holdsTool(player) && airborne(player)) {
            // Откат после скольжения: говорим, сколько ещё ждать, чтобы зацеп не выглядел сломанным.
            player.sendActionBar("§bИзморозь ещё не готова: "
                    + ((state.slipUntil - tick + 19) / 20) + " с");
        }
        if (!WinterRules.canGrab(items.holdsTool(player), airborne(player), falling, clearance(player),
                cooling, state != null && state.grip != Grip.NONE,
                state != null && state.frozenUntil > tick)) return;
        RayTraceResult hit = sight(player);
        if (hit == null || hit.getHitBlock() == null || hardness(hit.getHitBlock()) < 0) return;
        attach(player, states.computeIfAbsent(player.getUniqueId(), ignored -> new State()), hit);
        event.setCancelled(true);
    }

    /** В креативе изморозь не изнашивается и откат после скольжения не действует. */
    private static boolean creative(Player player) { return player.getGameMode() == GameMode.CREATIVE; }

    /** Игрок сам в воздухе: и зацеп по ПКМ, и самозахват начинаются только так. */
    private static boolean airborne(Player player) {
        return !player.isOnGround() && !player.isFlying() && !player.isInsideVehicle()
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    /** Присед у стены в воздухе цепляется сам: «прыгнул с пола, зажал Shift и полез вверх».
     *  Высоту под ногами здесь не проверяем — иначе обычный прыжок с пола не цеплялся бы. */
    private void autoGrab(Player player) {
        if (!player.isSneaking() || !airborne(player)) return;
        State state = states.get(player.getUniqueId());
        boolean cooling = state != null && state.slipUntil > tick;
        boolean gripping = state != null && state.grip != Grip.NONE;
        boolean frozen = state != null && (state.frozenUntil > tick || state.coldUntil > tick);
        if (!WinterRules.canAutoGrab(items.holdsTool(player), airborne(player),
                WinterRules.notRising(player.getVelocity().getY()), cooling, gripping, frozen)) return;
        RayTraceResult hit = sight(player);
        if (hit == null || hit.getHitBlock() == null || hardness(hit.getHitBlock()) < 0) return;
        attach(player, states.computeIfAbsent(player.getUniqueId(), ignored -> new State()), hit);
    }

    /** Зацепились: запоминаем грань, включаем удержание и точку для ограничения сноса. */
    private void attach(Player player, State state, RayTraceResult hit) {
        state.grip = Grip.SLIDE;   // скорость падения и твёрдость уточнит первый же тик
        state.wall = hit.getHitBlock(); state.face = hit.getHitBlockFace();
        state.hardWall = hard(state.wall);
        state.entered = false; state.holding = false; state.hangUntil = 0;
        state.slideSpeed = 0; state.slideCharge = 0;
        state.driftAnchor = player.getLocation().clone();
        gripControl(player, state);
        impact(player, state.wall);
    }

    /** Звук и частицы удара о грань: звук блока плюс осколки самой изморози. */
    private void impact(Player player, Block wall) {
        Location at = nearestFace(wall, player.getEyeLocation());
        player.getWorld().playSound(at, wall.getBlockData().getSoundGroup().getBreakSound(), 1.2f, 0.6f);
        player.getWorld().spawnParticle(Particle.BLOCK, at, 18, 0.25, 0.25, 0.25, 0.0, wall.getBlockData());
        player.getWorld().spawnParticle(Particle.ITEM, at, 12, 0.25, 0.25, 0.25, 0.05, rimeParticle);
    }

    /** Присед зажат и нажат пробел — прыжок от стены. Без зацепа это обычный присед. */
    @EventHandler
    public void input(PlayerInputEvent event) {
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null || state.grip == Grip.NONE || state.frozenUntil > tick) return;
        if (!event.getInput().isJump()) return;
        if (!event.getInput().isSneak() && !player.isSneaking()) return;
        wallJump(player, state);
    }

    /** Прыжок от стены: как обычный прыжок игрока — 0.42 вверх плюс небольшой
     *  толчок от грани (mod wallKick, но сила уменьшена), и 4 прочности изморози. */
    private void wallJump(Player player, State state) {
        Block wall = state.wall;
        BlockFace face = state.face;
        Vector normal = face == null ? new Vector(0, 1, 0) : face.getDirection();
        if (wall != null) iceBreakEffects(player, wall);
        release(player, true); // прыжок откат не даёт: иначе не получится карабкаться вверх
        player.setFallDistance(0);
        player.setVelocity(normal.multiply(tuning.jumpForward()).setY(tuning.jumpUp()));
        Location at = wall == null ? player.getLocation() : nearestFace(wall, player.getEyeLocation());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.2f);
        player.getWorld().spawnParticle(Particle.CRIT, at, 15, 0.2, 0.2, 0.2, 0.25);
        // Прыжок стоит 4 прочности из 16, последний ломает инструмент; в креативе — бесплатно.
        items.useClimb(player, WinterRules.CLIMB_DAMAGE);
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

    /** Один тик зацепа: держим грань в поле взгляда, считаем скольжение и износ инструмента. */
    private void grip(Player player, State state) {
        if (player.isDead() || !airborne(player) || !items.holdsTool(player)) { release(player); return; }
        RayTraceResult hit = sight(player);
        if (hit == null || hit.getHitBlock() == null) { release(player); return; } // грань ушла из-под взгляда
        double hardness = hardness(hit.getHitBlock());
        if (hardness < 0) { release(player); return; } // неразрушимый блок инструмент не держит
        state.wall = hit.getHitBlock(); state.face = hit.getHitBlockFace();
        state.hardWall = hard(state.wall);
        if (drifted(player, state)) { release(player); return; } // снос в сторону
        // Присед: на твёрдом блоке инструмент держит игрока, на мягком скольжение идёт своим ходом.
        if (player.isSneaking() && state.hardWall) hold(player, state);
        else wallSlide(player, state, hardness);
    }

    /** Ушли от точки захвата дальше max_drift — инструмент срывается (mod max_drift). */
    private boolean drifted(Player player, State state) {
        Location now = player.getLocation();
        if (state.driftAnchor == null || !state.driftAnchor.getWorld().equals(now.getWorld())) {
            state.driftAnchor = now.clone();
            return false;
        }
        double dx = now.getX() - state.driftAnchor.getX(), dz = now.getZ() - state.driftAnchor.getZ();
        return dx * dx + dz * dz > tuning.drift() * tuning.drift();
    }

    /** Присед: инструмент держит крепко, но не намертво — игрок медленно сползает вниз
     *  на hold-floor-speed (это прежняя минимальная скорость скольжения).
     *  hang-seconds больше нуля ограничивает такое удержание, как время кирки в моде; 0 — без предела. */
    private void hold(Player player, State state) {
        if (tuning.hangTicks() > 0) {
            if (!state.holding) state.hangUntil = tick + tuning.hangTicks();
            if (state.hangUntil <= tick) { slip(player, state); return; }
        }
        state.holding = true;
        state.entered = false;              // после приседа скольжение начнётся заново
        state.slideSpeed = tuning.holdFloor();
        gripControl(player, state);         // ходьба по нулям: игрок сползает, но не уходит вдоль стены
        player.setFallDistance(0);
        player.setVelocity(new Vector(0, -WinterRules.velocityForSpeed(tuning.holdFloor()), 0));
        scrapeFx(player, state, true);
    }

    /** Скольжение: скорость входа даёт падение, дальше трение блока отнимает её каждый тик —
     *  «быстро, медленнее, ещё медленнее». Твёрдый блок тормозит сильнее и почти останавливает
     *  игрока, мягкий только притормаживает, а ползущая скорость у него выше. */
    private void wallSlide(Player player, State state, double hardness) {
        state.holding = false; state.hangUntil = 0;
        if (!state.hardWall) state.softFallUntil = tick + WinterRules.SOFT_FALL_GRACE_TICKS;
        double floor = WinterRules.slideFloor(!state.hardWall, tuning.hardFloor(), tuning.softFloor());
        if (!state.entered) {
            // Скорость входа — та, с которой игрок подлетел к стене: выше падал, дольше тормозить.
            state.entered = true;
            state.slideSpeed = WinterRules.slideEntry(fallSpeed(player), floor, tuning.slideEntry());
        } else {
            // Трение растёт по мере замедления: торможение идёт плавными фазами.
            double base = state.hardWall
                    ? WinterRules.slideFrictionBase(hardness, tuning.hardFriction(), tuning.hardFrictionScale(),
                            tuning.hardFrictionMax())
                    : tuning.softFriction();
            double ramp = state.hardWall ? tuning.hardFrictionRamp() : tuning.softFrictionRamp();
            double friction = WinterRules.slideFriction(state.slideSpeed, tuning.slideEntry(), floor, base, ramp);
            state.slideSpeed = WinterRules.slideStep(state.slideSpeed, floor, friction);
        }
        state.grip = Grip.SLIDE;
        gripControl(player, state);
        if (state.hardWall) player.setFallDistance(0); // инструмент держит: падение не копится
        if (!wear(player, state)) return;              // инструмент стёрся — зацеп уже снят
        Vector velocity = player.getVelocity();
        player.setVelocity(new Vector(velocity.getX() * WinterRules.HORIZONTAL_DAMPING,
                -WinterRules.velocityForSpeed(state.slideSpeed),
                velocity.getZ() * WinterRules.HORIZONTAL_DAMPING));
        scrapeFx(player, state, false);
    }

    /** Скорость падения на момент захвата: сервер её уже посчитал своей физикой. */
    private static double fallSpeed(Player player) { return Math.max(-player.getVelocity().getY(), 0); }

    /** Быстрое скольжение стирает изморозь: по 1 прочности за блок, а в креативе — ничего. */
    private boolean wear(Player player, State state) {
        if (!WinterRules.slideWears(state.slideSpeed, tuning.slideDamageSpeed())) return true;
        state.slideCharge += state.slideSpeed * tuning.slideDamagePerBlock();
        int charge = WinterRules.whole(state.slideCharge);
        if (charge <= 0) return true;
        state.slideCharge -= charge;
        if (items.useClimb(player, charge)) return true;
        player.sendActionBar("§bИзморозь стёрлась о стену"); // инструмент кончился — зацеп снимается
        release(player);
        return false;
    }

    /** Звук и осколки блока у грани, пока инструмент скользит или сползает: у мода это
     *  spawnJuice — звук самого блока плюс его частицы. Чем быстрее скольжение, тем громче. */
    private void scrapeFx(Player player, State state, boolean holding) {
        Block wall = state.wall;
        if (wall == null || wall.getType().isAir()) return;
        Location at = nearestFace(wall, player.getEyeLocation());
        SoundGroup sounds = wall.getBlockData().getSoundGroup();
        if (holding) {
            if (tick % 12 == 0) player.getWorld().playSound(at, sounds.getHitSound(), 0.3f, 1.1f);
        } else if (tick % 4 == 0) {
            double ratio = Math.clamp(state.slideSpeed / Math.max(tuning.slideEntry(), 1e-6), 0.0, 1.0);
            player.getWorld().playSound(at, sounds.getHitSound(), (float) (0.35 + 0.5 * ratio),
                    0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f);
        }
        if (tick % 5 != 0) return;
        player.getWorld().spawnParticle(Particle.CRIT, at, 2, 0.05, 0.05, 0.05, 0.05);
        player.getWorld().spawnParticle(Particle.BLOCK, at, 4, 0.05, 0.3, 0.05, 0.08, wall.getBlockData());
    }

    /** Время виса истекло — изморозь соскальзывает (в моде это конец hang_time). */
    private void slip(Player player, State state) {
        release(player);
        state.slipUntil = Math.max(state.slipUntil, tick + tuning.slideCooldownTicks());
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
                boolean inAir = !player.isOnGround() && !player.isFlying() && !player.isInsideVehicle();
                if (!anchored || inAir || actual.getY() < anchor.getY() - 1e-8) {
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
        // Самозахват приседом: у игроков без состояния зацеп тоже может начаться.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!states.containsKey(player.getUniqueId())) autoGrab(player);
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
            state.driftAnchor = null; state.hangUntil = 0;
            state.holding = false; state.entered = false; state.slideSpeed = 0; state.slideCharge = 0;
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
