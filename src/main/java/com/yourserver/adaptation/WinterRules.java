package com.yourserver.adaptation;

import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;

/** Чистые правила зимнего улова и зацепа, без доступа к серверу. */
final class WinterRules {
    static final int DURABILITY = 16;
    static final int CLIMB_DAMAGE = 4;
    static final int FISH_LOCK_TICKS = 160;
    static final int SANDWICH_LOCK_TICKS = 80;
    static final int COLD_TICKS = 200;
    static final Set<String> BIOMES = Set.of(
            "minecraft:snowy_plains", "minecraft:ice_spikes", "minecraft:snowy_taiga",
            "minecraft:snowy_beach", "minecraft:frozen_river", "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean", "minecraft:grove", "minecraft:snowy_slopes",
            "minecraft:frozen_peaks", "minecraft:jagged_peaks");

    /* ==== Зацеп — механика мода Gouge (https://modrinth.com/mod/gouge).
       Управление: ПКМ по стене в падении — зацеп, присед — стоп (на твёрдом блоке),
       пробел на приседе — прыжок от стены. Роль hardness играет ванильная прочность
       блока (destroy speed): чем прочнее блок, тем сильнее он тормозит скольжение. ==== */
    static final double GRAB_REACH = 2.5;              // mechanics.reach
    static final double MIN_FALL_CLEARANCE = 1.5;      // mechanics.min_fall_distance (только для ПКМ)
    static final double MAX_DRIFT = 1.5;               // mechanics.max_drift
    static final int HANG_TICKS = 200;                 // pickaxes.diamond_pickaxe.hang_time = 10 с;
                                                       // в config.yml по умолчанию 0 — держит без ограничения
    static final int SOFT_FALL_GRACE_TICKS = 5;        // окно после скольжения по мягкому блоку
    static final int GRIP_HOLD_GRACE_TICKS = 8;        // зацеп держится, пока зажата ПКМ: тишина дольше — отпускаем
                                                       // (клиент повторяет отклик каждые 4 тика, 8 = запас на лаг)
    static final int REGRAB_GRACE_TICKS = 10;          // после отпускания ПКМ самозахват приседом ждёт полсекунды
    static final double AUTO_GRAB_MAX_RISE = 0.0;      // пока взлетаем, присед ещё не цепляется: зацеп по апогею
    static final double WALL_JUMP_FORWARD_BOOST = 0.0; // только вверх: от стены не толкаем,
                                                       // иначе игрок сразу отлетает и не карабкается
    static final double WALL_JUMP_UPWARD_BOOST = 0.55; // вверх — больше обычного прыжка (0.42): чтобы
                                                       // заново цепляться выше и карабкаться вверх
    static final double SOFT_FALL_DAMAGE = 0.5;        // mechanics.soft_fall_damage
    static final double SOFT_FALL_DAMAGE_CAP = 6.0;    // mechanics.soft_fall_damage_cap = 3 сердца

    /* Скольжение: скорость входа даёт падение, дальше трение блока отнимает её каждый тик,
       причём тем сильнее, чем сильнее игрок замедлился — торможение идёт плавными фазами:
       сначала держит крепче, а к концу отпускает. Трение подобрано так, чтобы скорость таяла
       дрифтом: с падения в 20+ блоков скольжение по камню идёт 2.6 секунды и 36 блоков, а
       сама скорость меняется за тик меньше чем на 4 % — рывка не видно и не чувствуется.
       Даже короткое падение начинает скользить: скорость входа не ниже min-entry-speed.
       Твёрдый блок держит крепче мягкого, поэтому скольжение гаснет почти в ноль; у мягкого
       «ползущая» скорость высокая, и сползание продолжается.
       Присед включает режим дрифта: скорость падает ровно на slide-drift-deceleration за тик
       до «ползущей» скорости, а на ней изморозь держит — оттуда можно прыгнуть вверх
       (см. slideDrift и atSlideFloor). */
    /** Версия чисел зацепа в config.yml: по ней видно, что файл обновлён под дрифт
     *  и режим дрифта шифтом (в старом файле лежат прежние числа — они не применяются). */
    static final double SLIDE_ENTRY_SPEED = 1.5;             // предел скорости входа: её даёт высота падения
    static final double SLIDE_MIN_ENTRY_SPEED = 0.7;         // и её минимум: дрифт начинается даже с двух блоков
    static final double SLIDE_HARD_FRICTION = 0.006;         // трение твёрдого блока у «ползущей» скорости
    static final double SLIDE_HARD_FRICTION_RAMP = 0.026;    // надбавка в начале скольжения: уходит по мере замедления
    static final double SLIDE_HARD_FRICTION_HARDNESS = 0.006; // чем прочнее блок, тем сильнее трение
    static final double SLIDE_HARD_FRICTION_MAX = 0.35;      // предел: обсидиан гасит почти сразу
    static final double SLIDE_HARD_FLOOR_SPEED = 0.12;       // минимальная скорость скольжения (2.4 блока в секунду)
    static final double SLIDE_DRIFT_DECELERATION = 0.04;     // режим дрифта (шифт): скорость падает ровно
                                                             // на столько за тик — плавно и предсказуемо
    static final double SLIDE_DRIFT_FLOOR_SPEED = 0.05;      // закреп на твёрдом блоке тормозит совсем
                                                             // тихо — 1 блок в секунду, как прежний шифт
    static final double SLIDE_SOFT_FRICTION = 0.008;          // мягкий блок тормозит слабее
    static final double SLIDE_SOFT_FRICTION_RAMP = 0.024;
    static final double SLIDE_SOFT_FLOOR_SPEED = 0.30;       // и не держит: сползание продолжается
    static final int SLIDE_COOLDOWN_TICKS = 80;              // откат изморози после скольжения — 4 секунды
    static final int GOUGE_CONFIG_VERSION = 4;

    /* Ванильная механика: скорость падения растёт как (v + 0.08) * 0.98, а заданная скорость
       доходит до клиента умноженной на 0.98 — отсюда множитель в velocityForSpeed. */
    static final double HORIZONTAL_DAMPING = 0.5;            // mod HORIZONTAL_DAMPING: снос в сторону за тик
    static final double GRAVITY_DRAG = 0.98;
    static final double SLIDE_DAMAGE_MIN_SPEED = 0.8;        // «быстрое» скольжение — то, что стирает инструмент
    static final int SLIDE_DAMAGE_PER_BLOCK = 1;             // прочности за блок такого скольжения
    static final int SLIDE_DAMAGE_MAX_PER_SLIDE = 8;         // потолок за одно скольжение: дрифт
                                                             // не должен ломать инструмент в полёте

    private WinterRules() { }

    /** Секунды конфига в тики с ограничением: значения вне диапазона заменяются границей. */
    static int ticks(double seconds, int min, int max) { return Math.clamp((int) Math.round(seconds * 20), min, max); }

    /** Зацеп по ПКМ: инструмент в руке, игрок в воздухе и падает, под ногами есть запас высоты,
     *  проскальзывание ещё не откатилось и другого зацепа нет. */
    static boolean canGrab(boolean tool, boolean airborne, boolean falling, boolean clearance,
            boolean cooling, boolean gripping, boolean frozen) {
        return tool && airborne && falling && clearance && !cooling && !gripping && !frozen;
    }

    /** Зацеп приседом у стены: высоту под ногами не проверяем — так цепляются сразу
     *  после обычного прыжка с пола, как только игрок перестал взлетать. */
    static boolean canAutoGrab(boolean tool, boolean airborne, boolean notRising,
            boolean cooling, boolean gripping, boolean frozen) {
        return tool && airborne && notRising && !cooling && !gripping && !frozen;
    }

    /** Зацеп по ПКМ держится, пока приходят отклики зажатой кнопки: клиент повторяет их
     *  каждые 4 тика, поэтому тишина дольше запаса означает, что игрок кнопку отпустил, —
     *  и изморозь отпускает стену. Ноль в holdUntil (нет требования) — прежнее поведение,
     *  когда зацеп жил, пока игрок смотрит на стену. */
    static boolean gripExpired(long now, long holdUntil) {
        return holdUntil > 0 && now > holdUntil;
    }

    /** Ещё взлетаем (например, только что прыгнули от стены) — цепляться рано. */
    static boolean notRising(double verticalSpeed) { return verticalSpeed <= AUTO_GRAB_MAX_RISE; }

    /** Скорость входа в скольжение: её даёт падение — чем выше падал, тем быстрее начнёшь. */
    static double slideEntry(double fallSpeed, double floorSpeed, double minSpeed, double maxSpeed) {
        return Math.clamp(Math.max(fallSpeed, Math.max(floorSpeed, minSpeed)), floorSpeed,
                Math.max(floorSpeed, maxSpeed));
    }

    /** Трение твёрдого блока в начале скольжения: чем прочнее блок, тем крепче он держит. */
    static double slideFrictionBase(double hardness, double base, double scale, double max) {
        return Math.min(base + hardness * scale, Math.max(base, max));
    }

    /** Трение за тик: в начале скольжения блок держит крепче всего, а к «ползущей» скорости
     *  хватка слабеет до base. Поэтому скорость тает плавным дрифтом: заметное торможение
     *  приходится на быструю часть, а низ проходится мягко, без обрыва.
     *  В режиме дрифта (шифт) трение не считается: скорость падает ровно на одну и ту же
     *  величину за тик — см. slideDrift. */
    static double slideFriction(double speed, double entrySpeed, double floorSpeed, double base, double ramp) {
        double span = Math.max(entrySpeed - floorSpeed, 1e-6);
        double grip = Math.clamp((speed - floorSpeed) / span, 0.0, 1.0);
        return base + ramp * grip;
    }

    /** Тик скольжения: трение отнимает скорость, но ниже «ползущей» блок её не отдаёт. */
    static double slideStep(double speed, double floorSpeed, double friction) {
        return Math.max(floorSpeed, speed - friction);
    }

    /** Режим дрифта (зажат шифт): скорость падает ровно на deceleration за тик и никогда
     *  не проваливается ниже предела закрепа — торможение плавное, без рывка на последних тиках.
     *  Дрифт умеет только сбрасывать скорость: он никогда не разгоняет игрока вниз. */
    static double slideDrift(double speed, double floorSpeed, double deceleration) {
        return Math.max(floorSpeed, speed - Math.max(0, deceleration));
    }

    /** Скорость входа в закреп: та, с которой игрок подошёл к стене, в границах от предела закрепа
     *  до предела скольжения. Разгона вниз нет: прыгнул с пола и зацепился — сразу закреп на
     *  минимуме (1 блок в секунду по твёрдому блоку), а не рывок на скорости скольжения.
     *  Минимум именно закрепа, а не скольжения: у мягкого блока он свой (0.30). */
    static double driftEntry(double fallSpeed, double floorSpeed, double maxSpeed) {
        return Math.clamp(Math.max(fallSpeed, floorSpeed), floorSpeed, Math.max(floorSpeed, maxSpeed));
    }

    /** Дрифт закончился: скорость дошла до предела и инструмент держит — можно прыгать вверх. */
    static boolean atSlideFloor(double speed, double floorSpeed) {
        return speed <= floorSpeed + 1e-6;
    }

    /** «Ползущая» скорость блока: у мягкого она высокая — инструмент мягкое не держит. */
    static double slideFloor(boolean soft, double hardFloorSpeed, double softFloorSpeed) {
        return soft ? softFloorSpeed : hardFloorSpeed;
    }

    /** Предел закрепа (зажатый шифт): по твёрдому блоку инструмент держит совсем крепко и отпускает
     *  игрока на самую тихую скорость, по мягкому — прежняя «ползущая» скорость блока: мягкое
     *  изморозь не держит, и замедлять там нечего. */
    static double driftFloor(boolean soft, double hardDriftFloor, double softFloorSpeed) {
        return soft ? softFloorSpeed : hardDriftFloor;
    }

    /** Быстрое скольжение — то, за которое инструмент стирается. */
    static boolean slideWears(double speed, double minSpeed) { return speed >= minSpeed; }

    /** Ванильное вертикальное трение: чтобы фактическая скорость совпала с задуманной. */
    static double velocityForSpeed(double speed) { return speed / GRAVITY_DRAG; }

    /** Целые единицы из накопленного расхода: прочность списывается только целиком. */
    static int whole(double accumulated) { return accumulated <= 0 ? 0 : (int) Math.floor(accumulated); }

    /** Прочности за накопленные блоки скольжения (для тестов и документации). */
    static int durabilityForSlide(double blocks, int perBlock) { return whole(blocks * perBlock); }

    /** Сколько прочности списать сейчас: по perBlock за блок быстрого скольжения, но не больше
     *  потолка за одно скольжение (длинный дрифт стирает инструмент постепенно, а не в ноль). */
    static int slideWear(double charge, int perBlock, int alreadyWorn, int maxPerSlide) {
        int left = Math.max(0, maxPerSlide - Math.max(0, alreadyWorn));
        return Math.min(whole(Math.max(0, charge) * perBlock), left);
    }

    /** Мягкое скольжение: падение слабее в softFallDamage раз и не больше cap (в очках урона, 2 = сердце). */
    static double softFallDamage(double damage, double multiplier, double cap) {
        double softened = Math.round(Math.max(0, damage) * multiplier);
        return cap > 0 ? Math.min(softened, cap) : softened;
    }

    static NamedTextColor titleColor(String kind) {
        return switch (kind) {
            case "DEPLETED" -> NamedTextColor.GRAY;
            case "SANDWICH", "CLAW" -> NamedTextColor.GOLD;
            default -> NamedTextColor.AQUA;
        };
    }
    static boolean forbiddenEnchant(boolean winterTool, boolean book, boolean enchanted, boolean customRune) {
        return winterTool && (book || enchanted || customRune);
    }
    static int afterUse(int damage, int amount) { return Math.min(DURABILITY, Math.max(0, damage) + Math.max(0, amount)); }
    static boolean broken(int damage) { return damage >= DURABILITY; }
}
