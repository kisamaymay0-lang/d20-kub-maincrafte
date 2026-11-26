package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Небольшие дуэли диких котов.
 *
 * <p>Менеджер работает только на главном серверном тике и использует обычный
 * Bukkit/Paper API, без NMS. Коты крадутся, прыгают друг на друга, а между
 * короткими обменами кружат и повторяют прыжок. Когда у одного из котов
 * остаётся половина здоровья, дуэль в 90% случаев заканчивается миром:
 * оба кота расходятся живыми. В оставшихся 10% один кот убегает, второй его
 * преследует, и дуэль заканчивается одной смертью. Таймер «ничьей» не
 * используется: если погоня застряла, по длительному лимиту проигрывает
 * более слабый кот.</p>
 *
 * <p>Звуки котов не отключаются: мурлыканье и ванильные звуки остаются как
 * есть, своих звуков менеджер не проигрывает. Ванильные частицы прыжков и
 * ударов не требуют ресурспака.</p>
 */
final class CatBattleManager implements Listener {
    private static final double DEFAULT_CHANCE_PER_TICK = 0.001D;
    private static final double SEARCH_RADIUS = 6.0D;
    private static final double SEARCH_HEIGHT = 3.0D;
    private static final double START_DISTANCE = 6.0D;
    private static final double ATTACK_DISTANCE = 1.9D;
    private static final double STALK_DISTANCE = 2.6D;
    /** Небольшой урон: один успешный удар не снимает больше 0.5 HP. */
    private static final double ATTACK_DAMAGE = 0.5D;
    /** Четверть замахов видна, но проходит без урона — дуэль не превращается в обмен одним ударом. */
    private static final double NO_DAMAGE_CHANCE = 0.25D;
    /** Уходим заранее, пока у кота остаётся запас здоровья для погони. */
    private static final double LOW_HEALTH_RATIO = 0.50D;
    /** На половине здоровья коты чаще щадят друг друга: 90% дуэлей без жертвы. */
    private static final double SPARE_CHANCE = 0.90D;
    private static final int STALK_MIN_TICKS = 30;
    private static final int STALK_MAX_TICKS = 70;
    private static final int CIRCLE_TICKS = 28;
    private static final int ATTACKS_PER_ROUND = 6;
    private static final int POUNCE_TICKS = 8;
    private static final int ATTACK_INTERVAL_TICKS = 12;
    private static final int MAX_BATTLE_TICKS = 1200;
    private static final int RESTART_COOLDOWN_TICKS = 100;

    private final AdaptationPlugin plugin;
    /** По одному объекту боя на каждого кота; значения повторяются дважды. */
    private final Map<UUID, Battle> battles = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private boolean enabled;
    private double chancePerTick;

    CatBattleManager(AdaptationPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    /** Перечитать шанс после /f8 reload; уже идущие дуэли не обрываются. */
    void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("cat-battles.enabled", true);
        chancePerTick = readChance(plugin.getConfig());
    }

    /**
     * Вероятность за один тик, а не за секунду. Значение 0.001 = 0.1%.
     * Поддерживаем и явный процентный вариант для удобства старых конфигов.
     */
    static double readChance(ConfigurationSection config) {
        double chance;
        if (config.contains("cat-battles.chance-percent-per-tick")) {
            chance = config.getDouble("cat-battles.chance-percent-per-tick", 0.1D) / 100.0D;
        } else {
            chance = config.getDouble("cat-battles.chance-per-tick", DEFAULT_CHANCE_PER_TICK);
        }
        if (!Double.isFinite(chance)) return DEFAULT_CHANCE_PER_TICK;
        return Math.clamp(chance, 0.0D, 1.0D);
    }

    String describe() {
        if (!enabled) return "Битвы диких котов: выключены.";
        return "Битвы диких котов: шанс " + chancePerTick + " за тик для каждой подходящей пары.";
    }

    /** Один серверный тик: сначала продолжаем существующие бои, потом ищем новые пары. */
    void tick() {
        for (Battle battle : uniqueBattles()) tickBattle(battle);

        long now = Bukkit.getCurrentTick();
        if (now % 200L == 0L) cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (!enabled || chancePerTick <= 0.0D) return;

        for (World world : Bukkit.getWorlds()) {
            for (Cat cat : world.getEntitiesByClass(Cat.class)) {
                if (!canStart(cat, now)) continue;
                for (Entity nearby : cat.getNearbyEntities(SEARCH_RADIUS, SEARCH_HEIGHT, SEARCH_RADIUS)) {
                    if (!(nearby instanceof Cat other) || !canStart(other, now)) continue;
                    // Каждая пара получает один бросок за тик, а не два в обе стороны.
                    if (cat.getUniqueId().compareTo(other.getUniqueId()) >= 0) continue;
                    if (!sameWorld(cat, other)
                            || cat.getLocation().distanceSquared(other.getLocation()) > START_DISTANCE * START_DISTANCE
                            || !cat.hasLineOfSight(other)) continue;
                    if (ThreadLocalRandom.current().nextDouble() >= chancePerTick) continue;
                    startBattle(cat, other);
                    break;
                }
            }
        }
    }

    private boolean canStart(Cat cat, long now) {
        if (cat == null || !cat.isValid() || cat.isDead() || cat.getHealth() <= 0.0D) return false;
        if (cat.isTamed() || cat.isSitting() || cat.isLyingDown() || cat.getVehicle() != null) return false;
        if (battles.containsKey(cat.getUniqueId())) return false;
        Long cooldown = cooldowns.get(cat.getUniqueId());
        return cooldown == null || cooldown <= now;
    }

    private void startBattle(Cat first, Cat second) {
        if (battles.containsKey(first.getUniqueId()) || battles.containsKey(second.getUniqueId())) return;
        Battle battle = new Battle(first, second, new OriginalState(first), new OriginalState(second));
        battles.put(first.getUniqueId(), battle);
        battles.put(second.getUniqueId(), battle);
        try {
            prepare(first, second);
            prepare(second, first);
        } catch (RuntimeException ex) {
            // Не оставляем половину пары в карте, если сторонний плагин запретил изменение состояния.
            abort(battle);
            plugin.getLogger().warning("Не удалось начать бой диких котов: " + ex.getMessage());
        }
    }

    private void prepare(Cat cat, Cat opponent) {
        cat.setPersistent(true);
        cat.setRemoveWhenFarAway(false);
        cat.setAI(true);
        cat.setAware(true);
        cat.setSitting(false);
        cat.setLyingDown(false);
        cat.setHeadUp(true);
        cat.setTarget(opponent);
        stalkPose(cat);
    }

    private void tickBattle(Battle battle) {
        if (battle.finished) return;
        Cat first = battle.first;
        Cat second = battle.second;
        if (first.isDead() || !first.isValid() || second.isDead() || !second.isValid()) {
            Cat alive = first.isValid() && !first.isDead() ? first
                    : second.isValid() && !second.isDead() ? second : null;
            Cat gone = alive == first ? second : first;
            if (alive != null && (gone.isDead() || !gone.isValid())) finish(battle, alive, gone, false);
            else abort(battle);
            return;
        }
        // Приручение во время дуэли отменяется событием ниже. Это второй барьер
        // для команд/других плагинов: домашнего кота не вовлекаем в следующий тик.
        if (first.isTamed() || second.isTamed() || !sameWorld(first, second)) {
            abort(battle);
            return;
        }

        battle.elapsed++;
        battle.phaseTicks++;
        if (battle.elapsed >= MAX_BATTLE_TICKS) {
            resolveStalledBattle(battle);
            return;
        }

        switch (battle.phase) {
            case STALK -> tickStalk(battle);
            case CIRCLE -> tickCircle(battle);
            case POUNCE -> tickPounce(battle);
            case FIGHT -> tickFight(battle);
        }
    }

    private void tickStalk(Battle battle) {
        Cat first = battle.first;
        Cat second = battle.second;
        stalkPose(first);
        stalkPose(second);
        face(first, second);
        face(second, first);
        if (battle.phaseTicks == 1 || battle.phaseTicks % 5 == 0) {
            first.getPathfinder().moveTo(second, 0.55D);
            second.getPathfinder().moveTo(first, 0.55D);
        }
        if ((battle.phaseTicks >= STALK_MIN_TICKS
                && first.getLocation().distanceSquared(second.getLocation()) <= STALK_DISTANCE * STALK_DISTANCE)
                || battle.phaseTicks >= STALK_MAX_TICKS) {
            beginPounce(battle);
        }
    }

    /** Небольшой перерыв между схватками: коты кружат и снова выбирают момент для прыжка. */
    private void beginCircle(Battle battle) {
        battle.phase = Phase.CIRCLE;
        battle.phaseTicks = 0;
        battle.attacksThisRound = 0;
        battle.first.getPathfinder().stopPathfinding();
        battle.second.getPathfinder().stopPathfinding();
        stalkPose(battle.first);
        stalkPose(battle.second);
    }

    private void tickCircle(Battle battle) {
        updateFleeing(battle);
        if (battle.finished) return;
        if (battle.fleeing != null) {
            battle.phase = Phase.FIGHT;
            battle.phaseTicks = 0;
            return;
        }
        stalkPose(battle.first);
        stalkPose(battle.second);
        face(battle.first, battle.second);
        face(battle.second, battle.first);
        if (battle.phaseTicks == 1 || battle.phaseTicks % 4 == 0) {
            circleMove(battle.first, battle.second, 1.0D);
            circleMove(battle.second, battle.first, -1.0D);
        }
        if (battle.phaseTicks >= CIRCLE_TICKS) beginPounce(battle);
    }

    private void circleMove(Cat cat, Cat opponent, double direction) {
        Vector radial = horizontalDirection(opponent, cat);
        if (radial.lengthSquared() < 0.0001D) radial = new Vector(1.0D, 0.0D, 0.0D);
        Vector sideways = new Vector(-radial.getZ() * direction, 0.0D, radial.getX() * direction).normalize();
        Location destination = opponent.getLocation()
                .add(radial.multiply(2.6D))
                .add(sideways.multiply(1.5D));
        cat.getPathfinder().moveTo(destination, 0.75D);
    }

    private void stalkPose(Cat cat) {
        cat.setSitting(false);
        cat.setLyingDown(false);
        cat.setHeadUp(true);
        cat.setSneaking(true);
        // fixed=true не даёт обычному AI кота тут же вернуть стоячую позу.
        cat.setPose(Pose.SNEAKING, true);
    }

    private void beginPounce(Battle battle) {
        battle.phase = Phase.POUNCE;
        battle.phaseTicks = 0;
        battle.attacksThisRound = 0;
        pounce(battle.first, battle.second);
        pounce(battle.second, battle.first);
        burst(battle.first, Particle.CLOUD, 5);
        burst(battle.second, Particle.CLOUD, 5);
    }

    private void pounce(Cat cat, Cat opponent) {
        cat.setSneaking(false);
        cat.setPose(Pose.STANDING, true);
        cat.setHeadUp(true);
        cat.getPathfinder().stopPathfinding();
        face(cat, opponent);
        Vector direction = horizontalDirection(cat, opponent);
        // Небольшая вертикальная скорость + направление к сопернику: это настоящий
        // физический прыжок, а не телепортация и не покадровая анимация.
        cat.setJumping(true);
        cat.setVelocity(new Vector(direction.getX() * 0.65D, 0.45D, direction.getZ() * 0.65D));
    }

    private void tickPounce(Battle battle) {
        face(battle.first, battle.second);
        face(battle.second, battle.first);
        if (battle.phaseTicks < POUNCE_TICKS) return;
        battle.phase = Phase.FIGHT;
        battle.phaseTicks = 0;
        battle.first.setJumping(false);
        battle.second.setJumping(false);
        standingPose(battle.first);
        standingPose(battle.second);
        burst(battle.first, Particle.CLOUD, 3);
        burst(battle.second, Particle.CLOUD, 3);
        // Короткая пауза после приземления, чтобы прыжок читался отдельно от ударов.
        battle.firstCooldown = 4;
        battle.secondCooldown = 4;
        battle.first.setTarget(battle.second);
        battle.second.setTarget(battle.first);
    }

    private void tickFight(Battle battle) {
        if (battle.firstCooldown > 0) battle.firstCooldown--;
        if (battle.secondCooldown > 0) battle.secondCooldown--;
        updateFleeing(battle);
        if (battle.finished) return;

        fightCat(battle, battle.first, battle.second);
        if (!battle.finished && !battle.second.isDead() && battle.second.isValid()) {
            fightCat(battle, battle.second, battle.first);
        }
        // После нескольких обменов не даём котам неподвижно «пилить» друг
        // друга: новый круг, новый прыжок, следующая короткая схватка.
        if (!battle.finished && battle.phase == Phase.FIGHT && battle.fleeing == null
                && battle.attacksThisRound >= ATTACKS_PER_ROUND) {
            beginCircle(battle);
        }
    }

    /** Низкое здоровье необратимо для текущей дуэли: кот не перестаёт убегать. */
    private void updateFleeing(Battle battle) {
        if (battle.fleeing != null) return;
        boolean firstLow = lowHealth(battle.first);
        boolean secondLow = lowHealth(battle.second);
        if (!firstLow && !secondLow) return;
        if (firstLow && secondLow) {
            battle.fleeing = battle.first.getHealth() <= battle.second.getHealth()
                    ? battle.first : battle.second;
        } else {
            battle.fleeing = firstLow ? battle.first : battle.second;
        }
        // Как только у одного из котов остаётся половина здоровья, дуэль чаще
        // заканчивается миром: оба расходятся, никто не умирает.
        if (sparesInsteadOfKill(ThreadLocalRandom.current().nextDouble())) {
            Cat spared = battle.fleeing;
            finish(battle, battle.other(spared), spared, false);
            return;
        }
        Cat pursuer = battle.other(battle.fleeing);
        battle.fleeing.setTarget(null);
        battle.fleeing.getPathfinder().stopPathfinding();
        pursuer.setTarget(battle.fleeing);
    }

    /**
     * Решение «пощадить» на пороге половины здоровья.
     * Отдельный метод, чтобы вероятность была явной и покрытой тестом.
     */
    static boolean sparesInsteadOfKill(double roll) {
        return roll < SPARE_CHANCE;
    }

    private static boolean lowHealth(Cat cat) {
        return cat.getHealth() <= cat.getMaxHealth() * LOW_HEALTH_RATIO;
    }

    private void fightCat(Battle battle, Cat cat, Cat opponent) {
        if (battle.fleeing == cat) {
            flee(battle, cat, opponent);
            return;
        }
        standingPose(cat);
        face(cat, opponent);
        double distanceSquared = cat.getLocation().distanceSquared(opponent.getLocation());
        if (distanceSquared > ATTACK_DISTANCE * ATTACK_DISTANCE || !cat.hasLineOfSight(opponent)) {
            if (battle.phaseTicks % 4 == 0) {
                double speed = battle.fleeing == opponent ? 1.45D : 1.05D;
                cat.getPathfinder().moveTo(opponent, speed);
            }
            return;
        }
        cat.getPathfinder().stopPathfinding();
        if (battle.cooldownFor(cat) > 0) return;
        hit(battle, cat, opponent);
    }

    private void flee(Battle battle, Cat cat, Cat pursuer) {
        standingPose(cat);
        cat.setTarget(null);
        Vector away = horizontalDirection(pursuer, cat);
        if (away.lengthSquared() < 0.0001D) {
            away = new Vector(ThreadLocalRandom.current().nextDouble(-1.0D, 1.0D), 0.0D,
                    ThreadLocalRandom.current().nextDouble(-1.0D, 1.0D));
            if (away.lengthSquared() < 0.0001D) away = new Vector(1.0D, 0.0D, 0.0D);
        }
        away.normalize().multiply(5.0D);
        Location destination = cat.getLocation().add(away);
        if (battle.phaseTicks % 5 == 0) cat.getPathfinder().moveTo(destination, 1.35D);
        if (battle.phaseTicks % 5 == 0) cat.lookAt(destination);
    }

    private void hit(Battle battle, Cat attacker, Cat victim) {
        battle.setCooldown(attacker, ATTACK_INTERVAL_TICKS);
        battle.attacksThisRound++;
        attacker.swingMainHand();
        // Замах остаётся видимым, но часть ударов не наносит урон вообще:
        // не вызываем damage и не делаем отброс, чтобы это был именно пустой удар.
        if (ThreadLocalRandom.current().nextDouble() < NO_DAMAGE_CHANCE) {
            burst(victim, Particle.SMOKE, 2);
            return;
        }
        burst(victim, Particle.CRIT, 4);
        victim.damage(ATTACK_DAMAGE, attacker);
        // Смерть может синхронно вызвать EntityDeathEvent: не трогаем уже
        // удалённую сущность ради отброса и не даём второму коту ударить труп.
        if (victim.isDead() || victim.getHealth() <= 0.0D) {
            finish(battle, attacker, victim, true);
            return;
        }
        // Лёгкий отброс помогает прочитать удар, но не разрывает погоню.
        Vector direction = horizontalDirection(attacker, victim);
        if (direction.lengthSquared() > 0.0001D) {
            victim.knockback(0.12D, -direction.getX(), -direction.getZ());
        }
        updateFleeing(battle);
    }

    /** Страховка от непроходимой геометрии/отменённого сторонним плагином урона. */
    private void resolveStalledBattle(Battle battle) {
        Cat loser;
        if (battle.first.getHealth() == battle.second.getHealth()) {
            loser = ThreadLocalRandom.current().nextBoolean() ? battle.first : battle.second;
        } else {
            loser = battle.first.getHealth() < battle.second.getHealth() ? battle.first : battle.second;
        }
        finish(battle, battle.other(loser), loser, true);
    }

    /**
     * Завершить пару ровно один раз. При kill=true проигравший реально умирает;
     * при внешней смерти (kill=false) только восстанавливаем выжившего.
     */
    private void finish(Battle battle, Cat winner, Cat loser, boolean kill) {
        if (!removeBattle(battle)) return;
        long cooldownUntil = Bukkit.getCurrentTick() + RESTART_COOLDOWN_TICKS;
        cooldowns.put(battle.first.getUniqueId(), cooldownUntil);
        cooldowns.put(battle.second.getUniqueId(), cooldownUntil);

        if (kill && loser != null && loser.isValid() && !loser.isDead()) {
            loser.setSneaking(false);
            loser.setPose(Pose.STANDING, true);
            loser.getPathfinder().stopPathfinding();
            // Сначала обычный урон (события/дроп/анимация), затем защита от
            // cancelled event: дуэль не может зависнуть с двумя живыми котами.
            if (loser.getHealth() > 0.0D) {
                loser.damage(Math.max(1.0D, loser.getHealth() + 1.0D), winner);
            }
            if (!loser.isDead()) loser.setHealth(0.0D);
        }
        if (winner != null && winner.isValid() && !winner.isDead()) {
            OriginalState state = battle.stateFor(winner);
            if (state != null) state.restore(winner);
        }
        if (!kill && loser != null && loser.isValid() && !loser.isDead()) {
            OriginalState state = battle.stateFor(loser);
            if (state != null) state.restore(loser);
        }
    }

    /** Прерывание только при выключении/выгрузке или внешнем вмешательстве, без выдуманного трупа. */
    private void abort(Battle battle) {
        if (!removeBattle(battle)) return;
        restoreIfAlive(battle.first, battle.firstState);
        restoreIfAlive(battle.second, battle.secondState);
    }

    private static void restoreIfAlive(Cat cat, OriginalState state) {
        if (cat != null && cat.isValid() && !cat.isDead()) state.restore(cat);
    }

    private boolean removeBattle(Battle battle) {
        if (battle.finished) return false;
        battle.finished = true;
        battles.remove(battle.first.getUniqueId(), battle);
        battles.remove(battle.second.getUniqueId(), battle);
        return true;
    }

    private List<Battle> uniqueBattles() {
        Set<Battle> unique = new HashSet<>(battles.values());
        return new ArrayList<>(unique);
    }

    private static boolean sameWorld(Cat first, Cat second) {
        return first.getWorld().equals(second.getWorld());
    }

    private static void face(Cat cat, Cat opponent) {
        cat.lookAt(opponent.getEyeLocation());
    }

    /** Направление от from к to в горизонтальной плоскости, без изменения исходных локаций. */
    private static Vector horizontalDirection(Cat from, Cat to) {
        Vector direction = to.getLocation().toVector().subtract(from.getLocation().toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() > 0.0001D) direction.normalize();
        return direction;
    }

    private static void burst(Cat cat, Particle particle, int count) {
        cat.getWorld().spawnParticle(particle, cat.getLocation().add(0.0D, 0.55D, 0.0D),
                count, 0.18D, 0.20D, 0.18D, 0.02D);
    }

    private static void standingPose(Cat cat) {
        cat.setSitting(false);
        cat.setLyingDown(false);
        cat.setSneaking(false);
        cat.setHeadUp(true);
        cat.setPose(Pose.STANDING, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Cat cat)) return;
        Battle battle = battles.get(cat.getUniqueId());
        if (battle == null) return;
        Cat survivor = battle.other(cat);
        finish(battle, survivor, cat, false);
    }

    /** Не даём укрощению превратить активную дикую пару в домашнего кота mid-fight. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (battles.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    void disable() {
        for (Battle battle : uniqueBattles()) abort(battle);
        battles.clear();
        cooldowns.clear();
    }

    private enum Phase { STALK, CIRCLE, POUNCE, FIGHT }

    private static final class Battle {
        private final Cat first;
        private final Cat second;
        private final OriginalState firstState;
        private final OriginalState secondState;
        private Phase phase = Phase.STALK;
        private int elapsed;
        private int phaseTicks;
        private int firstCooldown;
        private int secondCooldown;
        private int attacksThisRound;
        private Cat fleeing;
        private boolean finished;

        private Battle(Cat first, Cat second, OriginalState firstState, OriginalState secondState) {
            this.first = first;
            this.second = second;
            this.firstState = firstState;
            this.secondState = secondState;
        }

        private Cat other(Cat cat) {
            return cat.getUniqueId().equals(first.getUniqueId()) ? second : first;
        }

        private OriginalState stateFor(Cat cat) {
            return cat.getUniqueId().equals(first.getUniqueId()) ? firstState : secondState;
        }

        private int cooldownFor(Cat cat) {
            return cat.getUniqueId().equals(first.getUniqueId()) ? firstCooldown : secondCooldown;
        }

        private void setCooldown(Cat cat, int value) {
            if (cat.getUniqueId().equals(first.getUniqueId())) firstCooldown = value;
            else secondCooldown = value;
        }
    }

    /** Состояние до дуэли: после неё победитель не остаётся навсегда крадущимся. */
    private static final class OriginalState {
        private final Pose pose;
        private final boolean fixedPose;
        private final boolean sneaking;
        private final boolean sitting;
        private final boolean lying;
        private final boolean headUp;
        private final boolean ai;
        private final boolean aware;
        private final boolean persistent;
        private final boolean removeWhenFarAway;
        private final LivingEntity target;

        private OriginalState(Cat cat) {
            pose = cat.getPose();
            fixedPose = cat.hasFixedPose();
            sneaking = cat.isSneaking();
            sitting = cat.isSitting();
            lying = cat.isLyingDown();
            headUp = cat.isHeadUp();
            ai = cat.hasAI();
            aware = cat.isAware();
            persistent = cat.isPersistent();
            removeWhenFarAway = cat.getRemoveWhenFarAway();
            target = cat.getTarget();
        }

        private void restore(Cat cat) {
            cat.setJumping(false);
            cat.setSneaking(sneaking);
            cat.setPose(pose, fixedPose);
            cat.setSitting(sitting);
            cat.setLyingDown(lying);
            cat.setHeadUp(headUp);
            cat.setAI(ai);
            cat.setAware(aware);
            cat.setPersistent(persistent);
            cat.setRemoveWhenFarAway(removeWhenFarAway);
            cat.setTarget(target != null && target.isValid() && !target.isDead() ? target : null);
            cat.getPathfinder().stopPathfinding();
        }
    }
}
