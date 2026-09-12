package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Миниигра особого предмета биома.
 *
 * При улове с небольшим шансом (config.yml → fishing.special-item-chance-percent)
 * вместо обычной рыбы из воды выпрыгивает особый предмет биома: из конца удочки
 * по воде идут золотые частицы кругами, над инвентарём появляется
 * «Поймай его! Осталось: N», а вокруг игрока в куполе летает предмет.
 * Его надо ударить несколько раз (по умолчанию 4): от каждого удара предмет
 * делает рывок в случайную сторону. После последнего удара он падает на землю
 * и превращается в настоящий предмет.
 *
 * Миниигра живёт только пока удочка в руке игрока и заброшена в водоём.
 * Если у биома нет особого предмета (fishing/special-items в конфиге),
 * миниигра не запускается.
 */
final class SpecialCatch implements Listener {

    /** Отметка «этот поплавок мы забросили сами»: на нём миниигра не начинается. */
    private final NamespacedKey ourHook;
    private final JavaPlugin plugin;
    private final AncientJug jug;
    private final WinterFishing winter;

    private final Map<UUID, Hunt> hunts = new HashMap<>();
    private BukkitTask task;

    SpecialCatch(JavaPlugin plugin, AncientJug jug, WinterFishing winter) {
        this.plugin = plugin;
        this.jug = jug;
        this.winter = winter;
        this.ourHook = new NamespacedKey(plugin, "special_catch_hook");
    }

    // ===== НАСТРОЙКИ (читаются на ходу, поэтому /f8 reload сразу действует) =====

    private double chance() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-item-chance-percent", 5.0), 0.0, 100.0) / 100.0;
    }

    private int hitsRequired() {
        return Math.clamp(plugin.getConfig().getInt("fishing.special-minigame.hits-required", 4), 1, 20);
    }

    private int durationTicks() {
        return Math.clamp(plugin.getConfig().getInt("fishing.special-minigame.duration-seconds", 10), 3, 60) * 20;
    }

    private double radius() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.radius", 3.2), 1.5, 6.0);
    }

    private double height() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.height", 1.7), 0.5, 4.0);
    }

    private double dashStrength() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.dash-strength", 1.4), 0.2, 4.0);
    }

    /** Сколько секунд предмет делает один оборот вокруг игрока (в начале охоты). */
    private double revolutionSeconds() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.revolution-seconds", 2.2), 0.4, 10.0);
    }

    /** Прибавка к скорости полёта за каждый удар (1.0 = +100 % скорости). */
    private double speedPerHit() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.speed-per-hit", 0.35), 0.0, 3.0);
    }

    /** Насколько предмет качается вверх-вниз. */
    private double bobHeight() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.bob-height", 0.5), 0.0, 2.0);
    }

    /** Прибавка к раскачиванию за каждый удар. */
    private double bobPerHit() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.bob-per-hit", 0.6), 0.0, 5.0);
    }

    /** Прибавка к силе рывка за каждый удар. */
    private double dashPerHit() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.dash-per-hit", 0.5), 0.0, 3.0);
    }

    private double hitRange() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.hit-range", 4.0), 1.5, 8.0);
    }

    private double hitAngle() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.hit-angle-degrees", 22.0), 5.0, 90.0);
    }

    private int particleInterval() {
        return Math.clamp(plugin.getConfig().getInt("fishing.special-minigame.particle-interval-ticks", 2), 1, 20);
    }

    private double swirlRadius() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.special-minigame.particle-radius", 1.5), 0.3, 4.0);
    }

    /**
     * Особый предмет биома. Ключ раздела — либо точный ID биома, либо шаблон,
     * где «*» заменяет любые символы («*:desert», «*:snowy*»). Так одной строкой
     * закрываются ВСЕ пустынные и ВСЕ зимние/ледяные биомы, включая биомы модов.
     * Если раздела в конфиге нет, работает встроенный список.
     */
    ItemStack specialItem(String biome) {
        var section = plugin.getConfig().getConfigurationSection("fishing.special-items");
        if (section != null) {
            String exact = section.getString(biome);
            if (exact != null && !exact.isBlank()) return itemOf(exact);
            for (String key : section.getKeys(false)) {
                if (!BiomePatterns.isPattern(key)) continue;
                if (BiomePatterns.matches(key, biome)) {
                    String value = section.getString(key);
                    if (value != null && !value.isBlank()) return itemOf(value);
                }
            }
        }
        String lower = biome.toLowerCase(Locale.ROOT);
        if (WinterRules.BIOMES.contains(lower)) return winterRime();
        if (lower.endsWith(":desert") || lower.contains("badlands")) return jug.createEmpty();
        return null;
    }

    /** Значение из конфига: имя нашего предмета или ID ванильного. */
    private ItemStack itemOf(String value) {
        String token = value.trim().toUpperCase(Locale.ROOT);
        if (token.equals("ANCIENT_JUG") || token.equals("ДРЕВНИЙ_КУВШИН")) return jug.createEmpty();
        if (token.equals("ICY_RIME") || token.equals("RIME") || token.equals("ИЗМОРОЗЬ")
                || token.equals("ЛЕДЯНАЯ_ИЗМОРОЗЬ") || token.equals("ЗАЛЕДЕНЕВШАЯ_ИЗМОРОЗЬ")) {
            return winterRime();
        }
        Material material = Material.matchMaterial(token);
        return material == null || material.isAir() ? null : new ItemStack(material);
    }

    /** Заледеневшая изморозь — зимний особый предмет биома. */
    private ItemStack winterRime() {
        return winter.items.create(WinterItems.Kind.TOOL);
    }


    // ===== ЗАПУСК =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCatch(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof Item caught)) return;
        Player player = event.getPlayer();
        if (hunts.containsKey(player.getUniqueId())) return;
        if (event.getHook().getPersistentDataContainer().has(ourHook, PersistentDataType.BYTE)) return;
        String biome = event.getHook().getLocation().getBlock().getBiome().getKey().toString();
        ItemStack reward = specialItem(biome);
        if (reward == null) return;                       // у биома нет особого предмета
        if (ThreadLocalRandom.current().nextDouble() >= chance()) return;
        // Обычного улова не будет: вместо него начинается охота.
        caught.remove();
        start(player, reward);
    }

    private void start(Player player, ItemStack reward) {
        Hunt hunt = new Hunt(player, reward);
        Location spot = orbitSpot(hunt);
        World world = player.getWorld();
        hunt.display = world.spawn(spot, ItemDisplay.class, display -> {
            display.setItemStack(reward.clone());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTeleportDuration(1);
            display.setInterpolationDuration(1);
            display.setShadowRadius(0f);
            display.setShadowStrength(0f);
            display.setViewRange(64f);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setSilent(true);
        });
        // Удочка остаётся заброшенной: забрасываем поплавок за игрока, чтобы
        // миниигра жила по своим правилам (пока поплавок в воде и в руке удочка).
        try {
            Vector cast = player.getEyeLocation().getDirection().multiply(1.1);
            FishHook hook = player.launchProjectile(FishHook.class, cast);
            hook.getPersistentDataContainer().set(ourHook, PersistentDataType.BYTE, (byte) 1);
            hunt.hook = hook;
        } catch (Throwable ignored) {
            // Не удалось забросить — миниигра всё равно пойдёт, условие с поплавком мягкое.
        }
        hunts.put(player.getUniqueId(), hunt);
        player.sendActionBar("§6Из воды выпрыгнул особый предмет биома! Поймай его!");
        world.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.6f);
        world.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 0.7f);
        ensureTask();
    }

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ===== ЖИЗНЬ МИНИИГРЫ =====

    private void tick() {
        if (hunts.isEmpty()) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            return;
        }
        for (Hunt hunt : new ArrayList<>(hunts.values())) {
            Player player = hunt.player;
            if (!player.isOnline() || player.isDead() || !player.isValid()) {
                cancel(hunt, null);
                continue;
            }
            if (!holdsRod(player)) {
                cancel(hunt, "§cМиниигра прервана: удочка должна быть в руке.");
                continue;
            }
            if (hunt.hook != null && !hunt.hook.isValid()) {
                cancel(hunt, "§cМиниигра прервана: поплавок вытащен из воды.");
                continue;
            }
            hunt.ticks++;
            if (hunt.ticks >= hunt.totalTicks) {
                cancel(hunt, "§cОсобый предмет сорвался и ушёл в глубину...");
                continue;
            }
            move(hunt);
            if (hunt.ticks % Math.max(1, particleInterval()) == 0) particles(hunt);
            if (hunt.ticks % 4 == 0) {
                int left = Math.max(1, (hunt.totalTicks - hunt.ticks) / 20);
                player.sendActionBar("§6Поймай его! Осталось: §e" + left);
            }
        }
    }

    /** Кувшин летает по кругу в куполе вокруг игрока и по инерции доезжает после удара. */
    private void move(Hunt hunt) {
        if (hunt.display == null || !hunt.display.isValid()) return;
        hunt.phase += orbitSpeed(hunt);
        Location spot = orbitSpot(hunt);
        if (hunt.dashTicks > 0) {
            hunt.dashTicks--;
            spot.add(hunt.dash);
            // Золотая дорожка за рывком: так бросок в сторону хорошо видно.
            hunt.player.getWorld().spawnParticle(Particle.DUST, spot, 3, 0.06, 0.06, 0.06, 0.0, goldDust());
            hunt.dash.multiply(hunt.dashTicks > 0 ? 0.84 : 0.5);
        }
        hunt.spot = spot;
        hunt.display.teleport(spot);
    }

    /** Скорость облёта: с каждым ударом предмет носится всё быстрее. */
    private double orbitSpeed(Hunt hunt) {
        double base = Math.PI * 2.0 / Math.max(6.0, revolutionSeconds() * 20.0);
        return base * (1.0 + hunt.hits * speedPerHit());
    }

    private Location orbitSpot(Hunt hunt) {
        Player player = hunt.player;
        double phase = hunt.phase;
        double x = player.getX() + Math.cos(phase) * radius();
        double z = player.getZ() + Math.sin(phase) * radius();
        // Раскачивание вверх-вниз тоже растёт с каждым ударом (но не уходит под ноги).
        double bob = bobHeight() * (1.0 + hunt.hits * bobPerHit());
        double y = Math.max(player.getY() + 0.3, player.getY() + height() + Math.sin(phase * 1.7) * bob);
        return new Location(player.getWorld(), x, y, z);
    }

    private static Particle.DustOptions goldDust() {
        return new Particle.DustOptions(Color.fromRGB(0xFFC61A), 1.1f);
    }

    /** Золотые частицы: круги по воде у поплавка и искры с конца удочки. */
    private void particles(Hunt hunt) {
        Player player = hunt.player;
        World world = player.getWorld();
        Location water = hunt.hook != null && hunt.hook.isValid()
                ? hunt.hook.getLocation()
                : player.getLocation();
        double radius = swirlRadius();
        Particle.DustOptions gold = goldDust();
        int points = 12;
        for (int i = 0; i < points; i++) {
            double angle = hunt.phase * 2.0 + i * (Math.PI * 2.0 / points);
            Location point = water.clone().add(Math.cos(angle) * radius, 0.15 + 0.1 * Math.sin(angle * 2), Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0.0, gold);
        }
        // Короткая дорожка от конца удочки к воде: видно, что частицы идут «из удочки».
        Location tip = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(1.3)).subtract(0, 0.2, 0);
        for (int i = 0; i < 3; i++) {
            Location spark = tip.clone().add(ThreadLocalRandom.current().nextDouble(-0.15, 0.15),
                    ThreadLocalRandom.current().nextDouble(-0.15, 0.15),
                    ThreadLocalRandom.current().nextDouble(-0.15, 0.15));
            world.spawnParticle(Particle.DUST, spark, 1, 0.0, 0.0, 0.0, 0.0, gold);
        }
        world.spawnParticle(Particle.END_ROD, tip, 1, 0.05, 0.05, 0.05, 0.0);
    }

    // ===== УДАРЫ =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Hunt hunt = hunts.get(event.getPlayer().getUniqueId());
        if (hunt == null || hunt.display == null || !hunt.display.isValid()) return;
        if (!aimedAt(event.getPlayer(), hunt)) return;
        hit(hunt);
    }

    /** Игрок смотрит на предмет и стоит достаточно близко: удар засчитан. */
    private boolean aimedAt(Player player, Hunt hunt) {
        Location eye = player.getEyeLocation();
        Location target = hunt.display.getLocation().add(0, 0.25, 0);
        Vector toTarget = target.toVector().subtract(eye.toVector());
        if (toTarget.length() > hitRange()) return false;
        if (toTarget.lengthSquared() < 0.0001) return true;
        double angle = Math.toDegrees(eye.getDirection().angle(toTarget.normalize()));
        return angle <= hitAngle();
    }

    private void hit(Hunt hunt) {
        Player player = hunt.player;
        hunt.hits++;
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 1.2f);
        world.spawnParticle(Particle.CRIT, hunt.display.getLocation().add(0, 0.3, 0), 12, 0.25, 0.25, 0.25, 0.15);
        if (hunt.hits >= hitsRequired()) {
            finish(hunt);
            return;
        }
        // Рывок в случайную сторону, чтобы запутать игрока: с каждым ударом сильнее.
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double power = dashStrength() * (1.0 + hunt.hits * dashPerHit()) * 0.35;
        hunt.dash = new Vector(Math.cos(angle), ThreadLocalRandom.current().nextDouble(-0.2, 0.4), Math.sin(angle))
                .multiply(power);
        hunt.dashTicks = 10;
        world.spawnParticle(Particle.DUST, hunt.display.getLocation().add(0, 0.3, 0), 14, 0.25, 0.25, 0.25, 0.04, goldDust());
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.5f);
    }

    /** Последний удар: предмет падает на землю и превращается в вещь. */
    private void finish(Hunt hunt) {
        Player player = hunt.player;
        World world = player.getWorld();
        Location drop = hunt.display != null && hunt.display.isValid()
                ? hunt.display.getLocation().clone()
                : player.getLocation();
        drop.setY(world.getHighestBlockYAt(drop) + 0.4);
        if (hunt.display != null && hunt.display.isValid()) hunt.display.remove();
        world.dropItem(drop, hunt.reward);
        world.playSound(drop, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f);
        world.playSound(drop, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        world.spawnParticle(Particle.DUST, drop, 20, 0.4, 0.4, 0.4, 0.0, goldDust());
        player.sendActionBar("§aПоймал!");
        release(hunt);
    }

    private void cancel(Hunt hunt, String message) {
        if (hunt.display != null && hunt.display.isValid()) hunt.display.remove();
        if (message != null && hunt.player.isOnline()) hunt.player.sendActionBar(message);
        release(hunt);
    }

    private void release(Hunt hunt) {
        hunts.remove(hunt.player.getUniqueId());
        if (hunt.hook != null && hunt.hook.isValid()) hunt.hook.remove();
    }

    private static boolean holdsRod(Player player) {
        Material main = player.getInventory().getItemInMainHand().getType();
        Material off = player.getInventory().getItemInOffHand().getType();
        return main == Material.FISHING_ROD || off == Material.FISHING_ROD;
    }

    // ===== УБОРКА =====

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Hunt hunt = hunts.get(event.getPlayer().getUniqueId());
        if (hunt != null) cancel(hunt, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Hunt hunt = hunts.get(event.getEntity().getUniqueId());
        if (hunt != null) cancel(hunt, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Hunt hunt = hunts.get(event.getPlayer().getUniqueId());
        if (hunt == null || event.getTo() == null) return;
        if (!event.getTo().getWorld().equals(hunt.player.getWorld())
                || event.getFrom().distanceSquared(event.getTo()) > 32.0 * 32.0) {
            cancel(hunt, null);
        }
    }

    /** Короткая сводка действующих настроек для /f8 reload. */
    String describe() {
        List<String> biomes = new ArrayList<>();
        var section = plugin.getConfig().getConfigurationSection("fishing.special-items");
        if (section != null) biomes.addAll(section.getKeys(false));
        return "§7Миниигра особого улова: шанс §f" + Math.round(chance() * 100.0) + "%"
                + "§7, ударов §f" + hitsRequired()
                + "§7, таймер §f" + (durationTicks() / 20) + " с"
                + "§7, разгон §f+" + Math.round(speedPerHit() * 100.0) + "%/удар"
                + "§7, биомы с особым предметом: §f" + (biomes.isEmpty() ? "нет" : String.join(", ", biomes));
    }

    void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Hunt hunt : new ArrayList<>(hunts.values())) {
            if (hunt.display != null && hunt.display.isValid()) hunt.display.remove();
            if (hunt.hook != null && hunt.hook.isValid()) hunt.hook.remove();
        }
        hunts.clear();
    }

    /** Одна охота: летающий предмет, счётчик ударов и таймер. */
    private final class Hunt {
        final Player player;
        final ItemStack reward;
        final int totalTicks = durationTicks();
        ItemDisplay display;
        FishHook hook;
        double phase = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        Vector dash = new Vector();
        int dashTicks;
        int hits;
        int ticks;
        Location spot;

        Hunt(Player player, ItemStack reward) {
            this.player = player;
            this.reward = reward;
        }
    }
}
