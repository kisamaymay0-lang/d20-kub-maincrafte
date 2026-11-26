package com.yourserver.adaptation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Приступ тошноты: фантомные блоки и исчезающие лужи.
 *
 * <h2>Два слоя</h2>
 *
 * <ul>
 *   <li><b>Фантомные блоки</b> — сущности {@link BlockDisplay}: выглядят как настоящий
 *       блок, но не имеют коллизии, не ставятся в мир и не дропаются. Вылетают из
 *       <b>лицевой части головы</b> (чуть впереди глаз и чуть ниже), летят по инерции
 *       туда, куда игрок смотрит, и по дуге падают вниз. Долетев до земли, шлёпаются,
 *       брызгают частицами и растворяются. Их видит любой рядом — в этом и зрелище.</li>
 *   <li><b>Лужи</b> — настоящие блоки (ил, слизь, земля), но их ставит сам плагин: только
 *       на природный пол, только в пустое место, и через {@code puddles-seconds} они
 *       убираются сами. Старый блок запоминается и возвращается — мир не портится.</li>
 * </ul>
 *
 * <p>Лужи не появляются на постройках: пол под ними должен быть из «природного» списка
 * ({@code natural-floor} в настройках). Доски, кирпич и стекло в него не входят.
 *
 * <p>Всё считается по лимитам: одновременно не больше {@code phantom-max} фантомов и
 * {@code puddles-max} луж, поэтому даже вечеринка на двадцать человек не просадит TPS.
 */
final class Vomit {

    /** Сколько «шагов» живёт фантом: один шаг — два тика. */
    private static final int FLIGHT_STEPS = 60;
    /** Гравитация за шаг: сначала блок летит вперёд, потом всё быстрее падает. */
    private static final double GRAVITY = 0.022;
    /** Предел падения, чтобы не телепортироваться сквозь пол. */
    private static final double TERMINAL = 0.30;

    /** Один фантомный блок: летит, крутится, шлёпается и растворяется. */
    private static final class Phantom {
        final BlockDisplay display;
        final BlockData data;
        final float size;
        Vector velocity;
        int life;
        boolean landed;
        double spin;
        float shrink = 1.0f;

        Phantom(BlockDisplay display, BlockData data, float size, Vector velocity) {
            this.display = display;
            this.data = data;
            this.size = size;
            this.velocity = velocity;
            this.life = FLIGHT_STEPS;
        }
    }

    /** Одна лужа: где стоит, что было до неё и когда убирать. */
    private record Puddle(World world, int x, int y, int z, BlockData old, long expireTick) {
    }

    /** Блоки, которые бросаем: песок душ, керамика, ил и прочая пакость. */
    private static final Material[] PHANTOM_BLOCKS = {
            Material.SOUL_SAND,
            Material.SAND,
            Material.MUD,
            Material.BROWN_TERRACOTTA,
            Material.GREEN_TERRACOTTA,
            Material.ROOTED_DIRT,
            Material.MOSS_BLOCK,
            Material.SLIME_BLOCK
    };

    /** Куда можно ставить лужу: только в пустое место, траву и снег — ничего не ломаем. */
    private static boolean replaceable(Material material) {
        return material.isAir()
                || material == Material.SHORT_GRASS
                || material == Material.TALL_GRASS
                || material == Material.FERN
                || material == Material.LARGE_FERN
                || material == Material.DEAD_BUSH
                || material == Material.SNOW;
    }

    private final List<Phantom> phantoms = new ArrayList<>();
    private final List<Puddle> puddles = new ArrayList<>();
    private final Map<String, Integer> puddlesByChunk = new HashMap<>();
    private final Random random = new Random();
    private long tick;

    /** Сколько фантомных блоков сейчас живо. */
    int phantoms() {
        return phantoms.size();
    }

    /** Сколько луж сейчас стоит в мире. */
    int puddles() {
        return puddles.size();
    }

    /**
     * Приступ: облако фантомных блоков изо рта и несколько луж вокруг игрока.
     *
     * @param strong сильная стадия — блоков и луж больше
     */
    void burst(Player player, DrunkSettings settings, boolean strong) {
        if (player == null || settings == null) return;
        World world = player.getWorld();
        Location eyes = player.getEyeLocation();
        Vector forward = eyes.getDirection().normalize();
        // Рот: чуть впереди глаз и чуть ниже — там, где лицо, а не над головой.
        Location mouth = eyes.clone()
                .add(forward.clone().multiply(0.32))
                .add(0, -0.10, 0);

        if (settings.phantomBlocks()) {
            int count = strong ? 10 : 6;
            for (int i = 0; i < count && phantoms.size() < settings.phantomMax(); i++) {
                spawnPhantom(world, mouth, forward);
            }
        }

        if (settings.puddles()) {
            int puddleCount = strong ? 4 : 2;
            for (int i = 0; i < puddleCount; i++) placePuddle(player, settings);
        }

        world.playSound(eyes, Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.9F, 0.7F);
        world.playSound(eyes, Sound.BLOCK_MUD_BREAK, SoundCategory.PLAYERS, 0.7F, 0.9F);
        world.spawnParticle(Particle.ITEM_SLIME, mouth, strong ? 18 : 10, 0.16, 0.12, 0.16, 0.02);
        world.spawnParticle(Particle.CRIT, mouth, 8, 0.12, 0.12, 0.12, 0.05);
    }

    /**
     * Один фантом: родился во рту, полетел по инерции взгляда и дальше падает сам.
     * Размер — меньше куба, поэтому он и правда вылетает изо рта, а не из угла блока.
     */
    private void spawnPhantom(World world, Location mouth, Vector forward) {
        Material material = PHANTOM_BLOCKS[random.nextInt(PHANTOM_BLOCKS.length)];
        BlockData data = material.createBlockData();
        float size = 0.40f + random.nextFloat() * 0.18f;
        Location spawn = mouth.clone().add(
                (random.nextDouble() - 0.5) * 0.10,
                (random.nextDouble() - 0.5) * 0.08,
                (random.nextDouble() - 0.5) * 0.10);
        try {
            BlockDisplay display = world.spawn(spawn, BlockDisplay.class, entity -> {
                entity.setBlock(data);
                entity.setTransformation(transform(size, 0));
                entity.setPersistent(false);
                entity.setSilent(true);
                entity.setInvulnerable(true);
            });
            // Небольшая инерция туда, куда смотрит игрок, плюс чуть-чуть разброса.
            Vector velocity = forward.clone().multiply(0.070 + random.nextDouble() * 0.035)
                    .add(new Vector((random.nextDouble() - 0.5) * 0.030,
                            0.004 + random.nextDouble() * 0.012,
                            (random.nextDouble() - 0.5) * 0.030));
            phantoms.add(new Phantom(display, data, size, velocity));
        } catch (RuntimeException ignored) {
            // Сущность не создалась (чужой мир, лимит) — обойдёмся частицами.
        }
    }

    /** Поставить лужу рядом с игроком: только на природный пол и только в пустое место. */
    private void placePuddle(Player player, DrunkSettings settings) {
        if (puddles.size() >= settings.puddleMax()) return;
        World world = player.getWorld();
        Location feet = player.getLocation();

        for (int attempt = 0; attempt < 6; attempt++) {
            int dx = random.nextInt(5) - 2;
            int dz = random.nextInt(5) - 2;
            if (dx == 0 && dz == 0) continue;

            Block ground = world.getBlockAt(feet.getBlockX() + dx, feet.getBlockY() - 1, feet.getBlockZ() + dz);
            Block target = ground.getRelative(BlockFace.UP);

            if (!settings.naturalFloor().contains(ground.getType())) continue;
            if (!replaceable(target.getType())) continue;
            if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) continue;

            Material material = settings.puddleMaterials().get(random.nextInt(settings.puddleMaterials().size()));
            BlockData old = target.getBlockData().clone();
            try {
                target.setType(material, false);
            } catch (RuntimeException ex) {
                // В настройках оказался не блок (палка, стрела) — просто не пачкаем мир.
                continue;
            }

            long expire = tick + Math.max(20, settings.puddleSeconds()) * 20L;
            puddles.add(new Puddle(world, target.getX(), target.getY(), target.getZ(), old, expire));
            puddlesByChunk.merge(chunkKey(world, target.getX(), target.getZ()), 1, Integer::sum);
            world.spawnParticle(Particle.SPLASH, target.getLocation().add(0.5, 0.6, 0.5), 6, 0.2, 0.1, 0.2, 0.02);
            return;
        }
    }

    /**
     * Забрать лужу из учёта: игрок её сломал. Возвращает прежний блок, чтобы плагин
     * мог поставить его обратно, — и {@code null}, если по этим координатам лужи нет.
     */
    BlockData takePuddle(World world, int x, int y, int z) {
        Iterator<Puddle> iterator = puddles.iterator();
        while (iterator.hasNext()) {
            Puddle puddle = iterator.next();
            if (puddle.world() != world || puddle.x() != x || puddle.y() != y || puddle.z() != z) continue;
            iterator.remove();
            puddlesByChunk.computeIfPresent(chunkKey(world, x, z), (chunk, count) -> count <= 1 ? null : count - 1);
            return puddle.old();
        }
        return null;
    }

    /**
     * Движение фантомов и уборка луж. Вызывается плагином каждые два тика,
     * поэтому «полёт» выглядит плавно, а работа стоит копейки.
     */
    void tick() {
        tick += 2;

        Iterator<Phantom> iterator = phantoms.iterator();
        while (iterator.hasNext()) {
            Phantom phantom = iterator.next();
            if (phantom.display == null || !phantom.display.isValid()) {
                iterator.remove();
                continue;
            }
            if (--phantom.life <= 0) {
                finish(phantom);
                iterator.remove();
                continue;
            }
            if (phantom.landed) {
                // Шлёпнулся: медленно растворяется, оставляя капли.
                phantom.shrink = Math.max(0f, phantom.shrink - 0.045f);
                applyTransform(phantom);
                if (phantom.life % 6 == 0) splatter(phantom);
                continue;
            }
            fly(phantom);
        }

        if (tick % 20 == 0) {
            Iterator<Puddle> puddlesIterator = puddles.iterator();
            while (puddlesIterator.hasNext()) {
                Puddle puddle = puddlesIterator.next();
                if (puddle.expireTick() > tick) continue;
                restore(puddle);
                puddlesIterator.remove();
            }
        }
    }

    /** Шаг полёта: инерция вперёд, гравитация, вращение. На земле — шлёпок. */
    private void fly(Phantom phantom) {
        phantom.velocity = phantom.velocity
                .multiply(new Vector(0.985, 1.0, 0.985))
                .subtract(new Vector(0, GRAVITY, 0));
        if (phantom.velocity.getY() < -TERMINAL) phantom.velocity.setY(-TERMINAL);

        Location next = phantom.display.getLocation().add(phantom.velocity);
        Block block = next.getBlock();
        if (!block.isPassable()) {
            land(phantom, block);
            return;
        }

        phantom.spin += 0.16 + random.nextDouble() * 0.10;
        phantom.display.teleport(next);
        applyTransform(phantom);
        if (phantom.life % 4 == 0) trail(next, phantom.data);
    }

    /** Блок долетел до препятствия: кладём его на поверхность и брызгаем. */
    private void land(Phantom phantom, Block block) {
        phantom.landed = true;
        phantom.life = 18 + random.nextInt(12);
        phantom.shrink = 1.0f;
        Location rest = new Location(block.getWorld(),
                block.getX() + 0.5,
                block.getY() + 1.0 + phantom.size / 2.0,
                block.getZ() + 0.5);
        phantom.display.teleport(rest);
        phantom.velocity = new Vector(0, 0, 0);
        applyTransform(phantom);
        splatter(phantom);
        block.getWorld().playSound(rest, Sound.BLOCK_MUD_BREAK, SoundCategory.PLAYERS, 0.35F, 1.4F);
    }

    /** Брызги от шлёпнувшегося блока. */
    private void splatter(Phantom phantom) {
        splat(phantom.display.getLocation(), phantom.data, 6);
    }

    /** След в воздухе: капли слизи и крошки того самого блока. */
    private void trail(Location at, BlockData data) {
        World world = at.getWorld();
        if (world == null) return;
        try {
            world.spawnParticle(Particle.ITEM_SLIME, at, 2, 0.07, 0.07, 0.07, 0.01);
        } catch (RuntimeException ignored) {
            // Частицы — украшение: если сервер их не знает, плевок всё равно летит.
        }
        try {
            world.spawnParticle(Particle.BLOCK_CRUMBLE, at, 2, 0.08, 0.08, 0.08, data);
        } catch (RuntimeException ignored) {
            // То же самое: без крошек полёт не ломается.
        }
    }

    /** Шлёпок: крошки блока и капли. */
    private void splat(Location at, BlockData data, int count) {
        World world = at.getWorld();
        if (world == null) return;
        try {
            world.spawnParticle(Particle.BLOCK_CRUMBLE, at, count, 0.20, 0.10, 0.20, data);
        } catch (RuntimeException ignored) {
            // Без крошек шлёпок переживём.
        }
        try {
            world.spawnParticle(Particle.SPLASH, at, Math.max(2, count / 2), 0.15, 0.05, 0.15, 0.02);
        } catch (RuntimeException ignored) {
            // И без капель.
        }
    }

    /** Превращение блока: размер и проворот. Центр куба — в точке сущности. */
    private static void applyTransform(Phantom phantom) {
        float size = Math.max(0.02f, phantom.size * phantom.shrink);
        phantom.display.setTransformation(transform(size, phantom.spin));
    }

    private static Transformation transform(float size, double spin) {
        Quaternionf rotation = new Quaternionf()
                .rotationX((float) (spin * 0.7))
                .rotateY((float) spin)
                .rotateZ((float) (spin * 0.4));
        return new Transformation(
                new Vector3f(-size / 2f, -size / 2f, -size / 2f),
                rotation,
                new Vector3f(size, size, size),
                new Quaternionf());
    }

    /** Лужа исчезла: вернуть на её место прежний блок и брызнуть напоследок. */
    private void restore(Puddle puddle) {
        World world = puddle.world();
        String key = chunkKey(world, puddle.x(), puddle.z());
        puddlesByChunk.computeIfPresent(key, (chunk, count) -> count <= 1 ? null : count - 1);

        if (!world.isChunkLoaded(puddle.x() >> 4, puddle.z() >> 4)) return;
        Block block = world.getBlockAt(puddle.x(), puddle.y(), puddle.z());
        block.setBlockData(puddle.old(), false);
        world.spawnParticle(Particle.SPLASH, block.getLocation().add(0.5, 0.4, 0.5), 5, 0.2, 0.05, 0.2, 0.01);
    }

    private void finish(Phantom phantom) {
        if (phantom.display == null) return;
        Location place = phantom.display.getLocation();
        if (phantom.display.isValid()) phantom.display.remove();
        World world = place.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.ITEM_SLIME, place, 3, 0.1, 0.1, 0.1, 0.01);
        }
    }

    /** Убрать лужи в выгружаемом чанке, чтобы блоки не остались «в воздухе» навсегда. */
    void restoreChunk(World world, int chunkX, int chunkZ) {
        String key = chunkKey(world, chunkX << 4, chunkZ << 4);
        if (!puddlesByChunk.containsKey(key)) return;
        Iterator<Puddle> iterator = puddles.iterator();
        while (iterator.hasNext()) {
            Puddle puddle = iterator.next();
            if (puddle.world() != world || (puddle.x() >> 4) != chunkX || (puddle.z() >> 4) != chunkZ) continue;
            restore(puddle);
            iterator.remove();
        }
        puddlesByChunk.remove(key);
    }

    /** Убрать всё: плагин выключается или перечитывает настройки. */
    void restoreAll() {
        for (Phantom phantom : phantoms) finish(phantom);
        phantoms.clear();
        for (Puddle puddle : puddles) restore(puddle);
        puddles.clear();
        puddlesByChunk.clear();
    }

    private static String chunkKey(World world, int blockX, int blockZ) {
        return world.getName() + ":" + (blockX >> 4) + ":" + (blockZ >> 4);
    }
}
