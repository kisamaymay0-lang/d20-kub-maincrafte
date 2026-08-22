package com.yourserver.adaptation;

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

public class FlaskListener implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey flaskTypeKey;
    private final NamespacedKey poisonExpireKey;

    public FlaskListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.flaskTypeKey = new NamespacedKey(plugin, "flask_type");
        this.poisonExpireKey = new NamespacedKey(plugin, "poison_expire");
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

        return meta.getPersistentDataContainer().has(
                flaskTypeKey,
                PersistentDataType.STRING
        );
    }

    private String getFlaskType(ItemStack item) {
        if (!isFlask(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer().get(
                flaskTypeKey,
                PersistentDataType.STRING
        );
    }

    private boolean hasPoison(ItemStack sword) {
        if (!isSword(sword) || !sword.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = sword.getItemMeta();

        if (meta == null) {
            return false;
        }

        Long expire = meta.getPersistentDataContainer().get(
                poisonExpireKey,
                PersistentDataType.LONG
        );

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

        long expire = System.currentTimeMillis() + 180_000L;

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

        sword.setItemMeta(meta);
    }

    public boolean giveFlask(Player player, String type, int amount) {
        if (player == null) {
            return false;
        }

        if (!type.equalsIgnoreCase("water") &&
                !type.equalsIgnoreCase("poison")) {
            return false;
        }

        amount = Math.max(1, Math.min(amount, 64));

        ItemStack flask = new ItemStack(Material.POTION, amount);
        ItemMeta meta = flask.getItemMeta();

        if (meta == null) {
            return false;
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
                    new NamespacedKey(
                            "f8resurs",
                            "flask_water"
                    )
            );
        } else {
            meta.setDisplayName("§fФлакон с отравлением");

            pdc.set(
                    flaskTypeKey,
                    PersistentDataType.STRING,
                    "poison"
            );

            meta.setItemModel(
                    new NamespacedKey(
                            "f8resurs",
                            "flask_poison"
                    )
            );
        }

        flask.setItemMeta(meta);

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
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }

        ItemStack sword = player.getInventory().getItemInMainHand();

        if (!isSword(sword)) {
            return;
        }

        if (!hasPoison(sword)) {
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
    }
}
