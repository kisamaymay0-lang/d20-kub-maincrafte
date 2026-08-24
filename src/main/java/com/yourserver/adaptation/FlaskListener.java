package com.yourserver.adaptation;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FlaskListener implements Listener {
    private static final long POISON_DURATION_MS = 90_000L;
    private static final String POISON_LORE_PREFIX = "§7Отравление: ";

    private static FlaskListener instance;

    private final JavaPlugin plugin;
    private final NamespacedKey flaskTypeKey;
    private final NamespacedKey poisonExpireKey;
    private final Particle.DustOptions poisonDust =
            new Particle.DustOptions(Color.fromRGB(76, 190, 76), 0.8f);
    private final BukkitTask poisonTask;
    private final Random random = new Random();

    public FlaskListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.flaskTypeKey = new NamespacedKey(plugin, "flask_type");
        this.poisonExpireKey = new NamespacedKey(plugin, "poison_expire");
        instance = this;

        this.poisonTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickPoisonEffects,
                1L,
                5L
        );
    }

    public static FlaskListener getInstance() {
        return instance;
    }

    private boolean isSword(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        return item.getType().name().endsWith("_SWORD");
    }

    private boolean isFlask(ItemStack item) {
        if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        return meta.getPersistentDataContainer()
                .has(flaskTypeKey, PersistentDataType.STRING);
    }

    private String getFlaskType(ItemStack item) {
        if (!isFlask(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer()
                .get(flaskTypeKey, PersistentDataType.STRING);
    }

    private Long getPoisonExpire(ItemStack sword) {
        if (!isSword(sword) || !sword.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = sword.getItemMeta();
        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer()
                .get(poisonExpireKey, PersistentDataType.LONG);
    }

    private boolean hasPoison(ItemStack sword) {
        Long expire = getPoisonExpire(sword);

        if (expire == null) {
            return false;
        }

        if (System.currentTimeMillis() >= expire) {
            removePoison(sword);
            return false;
        }

        return true;
    }

    private void applyPoison(ItemStack sword) {
        ItemMeta meta = sword.getItemMeta();

        if (meta == null) {
            return;
        }

        long expire = System.currentTimeMillis() + POISON_DURATION_MS;

        meta.getPersistentDataContainer().set(
                poisonExpireKey,
                PersistentDataType.LONG,
                expire
        );

        sword.setItemMeta(meta);
    }

    private void removePoison(ItemStack sword) {
        if (sword == null || !sword.hasItemMeta()) {
            return;
        }

        ItemMeta meta = sword.getItemMeta();

        if (meta == null) {
            return;
        }

        meta.getPersistentDataContainer().remove(poisonExpireKey);
        removePoisonLore(meta);
        sword.setItemMeta(meta);
    }

    private void removePoisonLore(ItemMeta meta) {
        if (!meta.hasLore() || meta.getLore() == null) {
            return;
        }

        List<String> lore = new ArrayList<>();

        for (String line : meta.getLore()) {
            if (!line.startsWith(POISON_LORE_PREFIX)) {
                lore.add(line);
            }
        }

        if (lore.isEmpty()) {
            meta.setLore(null);
        } else {
            meta.setLore(lore);
        }
    }

    private String formatTimeLeft(long expire) {
        long seconds = Math.max(
                0L,
                (expire - System.currentTimeMillis() + 999L) / 1000L
        );

        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;

        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private void tickPoisonEffects() {
        long now = System.currentTimeMillis();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ItemStack sword = player.getInventory().getItemInMainHand();
            Long expire = getPoisonExpire(sword);

            if (expire == null) {
                continue;
            }

            if (now >= expire) {
                removePoison(sword);
                continue;
            }

            // Таймер показывается в action bar и не меняет ItemStack каждую секунду.
            player.sendActionBar("§7Отравление: " + formatTimeLeft(expire));

            spawnSwordPoisonParticles(player);
        }
    }

    private void spawnSwordPoisonParticles(Player player) {
        /*
         * Частицы рассчитываются от положения игрока и направления его тела,
         * а не от направления взгляда. Поэтому движение камеры не двигает
         * эффект по сторонам.
         */
        LocationData locationData = getSwordArea(player);

        int count = 1 + random.nextInt(2);

        for (int i = 0; i < count; i++) {
            double x = (random.nextDouble() - 0.5) * 0.22;
            double y = random.nextDouble() * 0.32;
            double z = (random.nextDouble() - 0.5) * 0.22;

            Vector position = locationData.position.clone()
                    .add(locationData.right.clone().multiply(x))
                    .add(new Vector(0, y, 0))
                    .add(locationData.forward.clone().multiply(z));

            player.getWorld().spawnParticle(
                    Particle.DUST,
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    1,
                    0,
                    0,
                    0,
                    0,
                    poisonDust
            );

            // Маленькая "осевшая" частица ниже меча.
            if (random.nextDouble() < 0.35) {
                double fall = 0.10 + random.nextDouble() * 0.18;

                Vector falling = position.clone()
                        .add((random.nextDouble() - 0.5) * 0.08, -fall, (random.nextDouble() - 0.5) * 0.08);

                player.getWorld().spawnParticle(
                        Particle.DUST,
                        falling.getX(),
                        falling.getY(),
                        falling.getZ(),
                        1,
                        0,
                        0,
                        0,
                        0,
                        poisonDust
                );
            }
        }
    }

    private LocationData getSwordArea(Player player) {
        Vector forward = player.getLocation().getDirection().setY(0);

        if (forward.lengthSquared() < 0.001) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }

        Vector right = new Vector(-forward.getZ(), 0, forward.getX()).normalize();

        // Приближённое положение правой руки/меча.
        Vector position = player.getLocation().toVector()
                .add(new Vector(0, 1.05, 0))
                .add(right.clone().multiply(0.38))
                .add(forward.clone().multiply(0.22));

        return new LocationData(position, forward, right);
    }

    public ItemStack createWaterFlask() {
        return createFlask("water");
    }

    public ItemStack createPoisonFlask() {
        return createFlask("poison");
    }

    private ItemStack createFlask(String type) {
        ItemStack flask = new ItemStack(Material.POTION, 1);
        ItemMeta meta = flask.getItemMeta();

        if (meta == null) {
            return flask;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (type.equalsIgnoreCase("water")) {
            meta.setDisplayName("§fФлакон с водой");
            pdc.set(
                    flaskTypeKey,
                    PersistentDataType.STRING,
                    "water"
            );
            meta.setItemModel(
                    new NamespacedKey("f8resurs", "flask_water")
            );
        } else {
            meta.setDisplayName("§fФлакон с отравлением");
            pdc.set(
                    flaskTypeKey,
                    PersistentDataType.STRING,
                    "poison"
            );
            meta.setItemModel(
                    new NamespacedKey("f8resurs", "flask_poison")
            );
        }

        flask.setItemMeta(meta);
        return flask;
    }

    public boolean giveFlask(Player player, String type, int amount) {
        if (player == null) {
            return false;
        }

        if (!type.equalsIgnoreCase("water")
                && !type.equalsIgnoreCase("poison")) {
            return false;
        }

        amount = Math.max(1, Math.min(amount, 64));

        ItemStack flask = createFlask(type);
        flask.setAmount(amount);

        var leftovers = player.getInventory().addItem(flask);

        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    leftover
            );
        }

        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        if (!isFlask(offHand)) {
            return;
        }

        event.setCancelled(true);

        if (!player.isSneaking()) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();

        if (!isSword(mainHand)) {
            return;
        }

        String flaskType = getFlaskType(offHand);

        if (flaskType == null) {
            return;
        }

        if (flaskType.equals("water")) {
            if (!hasPoison(mainHand)) {
                return;
            }

            removePoison(mainHand);
            consumeFlask(player);

            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ENTITY_GENERIC_DRINK,
                    0.7f,
                    1.0f
            );

            player.getWorld().spawnParticle(
                    Particle.SMOKE,
                    player.getLocation().add(0, 1, 0),
                    8,
                    0.2,
                    0.3,
                    0.2,
                    0.02
            );

            return;
        }

        if (flaskType.equals("poison")) {
            if (hasPoison(mainHand)) {
                return;
            }

            applyPoison(mainHand);
            consumeFlask(player);

            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                    0.7f,
                    1.0f
            );

            player.getWorld().spawnParticle(
                    Particle.CRIT,
                    player.getLocation().add(0, 1, 0),
                    8,
                    0.2,
                    0.3,
                    0.2,
                    0.02
            );
        }
    }

    private void consumeFlask(Player player) {
        ItemStack flask = player.getInventory().getItemInOffHand();

        if (flask == null || flask.getType() == Material.AIR) {
            return;
        }

        if (flask.getAmount() <= 1) {
            player.getInventory().setItemInOffHand(null);
        } else {
            flask.setAmount(flask.getAmount() - 1);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }

        ItemStack sword = player.getInventory().getItemInMainHand();

        if (!isSword(sword) || !hasPoison(sword)) {
            return;
        }

        victim.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.POISON,
                        100,
                        0
                )
        );

        victim.getWorld().spawnParticle(
                Particle.CRIT,
                victim.getLocation().add(0, 1, 0),
                8,
                0.2,
                0.3,
                0.2,
                0.02
        );
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
    }

    public void disable() {
        if (poisonTask != null) {
            poisonTask.cancel();
        }

        if (instance == this) {
            instance = null;
        }
    }

    private static class LocationData {
        private final Vector position;
        private final Vector forward;
        private final Vector right;

        private LocationData(Vector position, Vector forward, Vector right) {
            this.position = position;
            this.forward = forward;
            this.right = right;
        }
    }
}
