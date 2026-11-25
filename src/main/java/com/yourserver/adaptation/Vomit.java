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
 *       блок, но не имеют коллизии, не ставятся в мир и не дропаются. Вылетают прямо
 *       изо рта (половинного размера, по центру головы), летят вперёд-вниз и
 *       рассыпаются частицами. Их видит любой рядом — в этом и зрелище.</li>
 *   <li><b>Лужи</b> — настоящие блоки (ил, слизь), но их ставит сам плагин: только на
 *       природный пол (земля, песок, камень, ил), только в пустое место, и через
 *       {@code puddles-seconds} они убираются сами. Старый блок запоминается и
 *       возвращается — то есть даже если что-то задело, мир вернётся как был.</li>
 * </ul>
 *
 * <p>Лужи не появляются на постройках: пол под ними должен быть из «природного» списка
 * ({@code natural-floor} в настройках). Доски, кирпич и стекло в этот список не входят,
 * поэтому чужие стены и полы не пачкаются.
 *
 * <p>Всё считается по лимитам: одновременно не больше {@code phantom-max} фантомов и
 * {@code puddles-max} луж, поэтому даже вечеринка на двадцать человек не просадит TPS.
 */
final class Vomit {

    /** Один фантомный блок: летит, крутится и исчезает. */
    private static final class Phantom {
        final BlockDisplay display;
        Vector velocity;
        int life;

        Phantom(BlockDisplay display, Vector velocity, int life) {
            this.display = display;
            this.velocity = velocity;
            this.life = life;
        }
    }

    /** Одна лужа: где стоит, что было до неё и когда убирать. */
    private record Puddle(World world, int x, int y, int z, BlockData old, long expireTick) {
    }

    /** Размер фантомного блока: половина куба, чтобы он родился ровно во рту. */
    private static final Transformation MOUTH_TRANSFORM = new Transformation(
            new Vector3f(-0.25f, -0.25f, -0.25f), new Quaternionf(),
            new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf());

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
     * Приступ: облако фантомных блоков и несколько луж вокруг игрока.
     *
     * @param strong сильная стадия — блоков и луж больше
     */
    void burst(Player player, DrunkSettings settings, boolean strong) {
        if (player == null || settings == null) return;
        World world = player.getWorld();
        Location eyes = player.getEyeLocation();
        Vector forward = eyes.getDirection().normalize();

        // 1. Фантомные блоки: ровно изо рта, вперёд и вниз — как будто и правда вырвало.
        int count = settings.phantomBlocks() ? (strong ? 10 : 6) : 0;
        for (int i = 0; i < count && phantoms.size() < settings.phantomMax(); i++) {
            Material material = PHANTOM_BLOCKS[random.nextInt(PHANTOM_BLOCKS.length)];
            // БлокDisplay висит углом в точке спавна, поэтому половинку центруем во рту.
            Location spawn = eyes.clone().add(forward.clone().multiply(0.25));
            try {
                BlockDisplay display = world.spawn(spawn, BlockDisplay.class, entity -> {
                    entity.setBlock(material.createBlockData());
                    entity.setTransformation(MOUTH_TRANSFORM);
                    entity.setPersistent(false);
                    entity.setSilent(true);
                    entity.setInvulnerable(true);
                });
                Vector velocity = forward.clone().multiply(0.18 + random.nextDouble() * 0.18)
                        .add(new Vector((random.nextDouble() - 0.5) * 0.25,
                                -0.05 + random.nextDouble() * 0.10,
                                (random.nextDouble() - 0.5) * 0.25));
                phantoms.add(new Phantom(display, velocity, 24 + random.nextInt(12)));
            } catch (RuntimeException ignored) {
                // Сущность не создалась (чужой мир, лимит) — просто обойдёмся частицами.
            }
        }

        // 2. Лужи — настоящие блоки, которые плагин сам уберёт.
        if (settings.puddles()) {
            int puddleCount = strong ? 4 : 2;
            for (int i = 0; i < puddleCount; i++) placePuddle(player, settings);
        }

        // 3. Звук и брызги: без графики, но слышно, что человеку плохо.
        world.playSound(eyes, Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.9F, 0.7F);
        world.playSound(eyes, Sound.BLOCK_MUD_BREAK, SoundCategory.PLAYERS, 0.7F, 0.9F);
        world.spawnParticle(Particle.ITEM_SLIME, eyes.clone().add(forward.clone().multiply(0.5)),
                strong ? 18 : 10, 0.25, 0.2, 0.25, 0.02);
        world.spawnParticle(Particle.CRIT, eyes.clone().add(forward.clone().multiply(0.4)),
                8, 0.2, 0.2, 0.2, 0.05);
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
            phantom.life -= 2;
            if (phantom.display == null || !phantom.display.isValid() || phantom.life <= 0) {
                finish(phantom);
                iterator.remove();
                continue;
            }
            phantom.velocity = phantom.velocity.subtract(new Vector(0, 0.045, 0));
            Location next = phantom.display.getLocation().add(phantom.velocity);
            phantom.display.teleport(next);
            if (phantom.life % 6 == 0) {
                next.getWorld().spawnParticle(Particle.ITEM_SLIME, next, 2, 0.1, 0.1, 0.1, 0.01);
            }
        }

        // Лужи проверяем пореже: раз в секунду.
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
        if (place.getWorld() != null) {
            place.getWorld().spawnParticle(Particle.ITEM_SLIME, place, 3, 0.1, 0.1, 0.1, 0.01);
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
