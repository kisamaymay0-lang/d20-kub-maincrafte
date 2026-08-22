package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class FlaskListener implements Listener {

    private final JavaPlugin plugin;

    private final int FLASK_WATER_MODEL = 1001;
    private final int FLASK_POISON_MODEL = 1002;

    private final NamespacedKey flaskTypeKey;
    private final NamespacedKey poisonExpireKey;

    private final java.util.Map<UUID, BukkitTask> updateTasks =
            new java.util.HashMap<>();

    public FlaskListener(JavaPlugin plugin) {

        this.plugin = plugin;

        /*
         * Эти ключи будут храниться внутри предмета.
         */
        flaskTypeKey =
                new NamespacedKey(
                        plugin,
                        "flask_type"
                );

        poisonExpireKey =
                new NamespacedKey(
                        plugin,
                        "poison_expire"
                );
    }

    private boolean isSword(ItemStack item) {

        if (item == null) {
            return false;
        }

        return item.getType()
                .name()
                .endsWith("_SWORD");
    }

    private boolean isFlask(ItemStack item) {

        if (item == null ||
                item.getType() != Material.POTION ||
                !item.hasItemMeta()) {

            return false;
        }

        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return false;
        }

        /*
         * Новый способ определения.
         */
        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        if (pdc.has(
                flaskTypeKey,
                PersistentDataType.STRING
        )) {

            return true;
        }

        /*
         * Совместимость со старыми флаконами.
         */
        if (!meta.hasDisplayName()) {
            return false;
        }

        String name =
                ChatColor.stripColor(
                        meta.getDisplayName()
                );

        return name.equals("Флакон с водой") ||
               name.equals("Флакон с отравлением");
    }

    private String getFlaskType(ItemStack item) {

        if (!isFlask(item)) {
            return null;
        }

        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return null;
        }

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        String storedType =
                pdc.get(
                        flaskTypeKey,
                        PersistentDataType.STRING
                );

        if (storedType != null) {
            return storedType;
        }

        /*
         * Совместимость со старыми предметами.
         */
        if (meta.hasDisplayName()) {

            String name =
                    ChatColor.stripColor(
                            meta.getDisplayName()
                    );

            if (name.equals("Флакон с водой")) {
                return "water";
            }

            if (name.equals("Флакон с отравлением")) {
                return "poison";
            }
        }

        return null;
    }

    private boolean hasPoison(ItemStack sword) {

        if (!isSword(sword)) {
            return false;
        }

        if (!sword.hasItemMeta()) {
            return false;
        }

        ItemMeta meta =
                sword.getItemMeta();

        if (meta == null) {
            return false;
        }

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        Long expire =
                pdc.get(
                        poisonExpireKey,
                        PersistentDataType.LONG
                );

        if (expire != null) {

            if (expire > System.currentTimeMillis()) {
                return true;
            }

            removePoisonData(sword);
            return false;
        }

        /*
         * Совместимость со старыми мечами,
         * где яд был только в Lore.
         */
        return hasLegacyPoisonLore(sword);
    }

    private boolean hasLegacyPoisonLore(ItemStack sword) {

        if (sword == null ||
                !sword.hasItemMeta()) {

            return false;
        }

        ItemMeta meta =
                sword.getItemMeta();

        if (meta == null ||
                !meta.hasLore()) {

            return false;
        }

        for (String line :
                meta.getLore()) {

            if (ChatColor.stripColor(line)
                    .contains("Отравление I")) {

                return true;
            }
        }

        return false;
    }

    private void removePoisonData(ItemStack sword) {

        if (sword == null ||
                !sword.hasItemMeta()) {

            return;
        }

        ItemMeta meta =
                sword.getItemMeta();

        if (meta == null) {
            return;
        }

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        pdc.remove(poisonExpireKey);

        List<String> lore =
                meta.hasLore()
                        ? new ArrayList<>(meta.getLore())
                        : new ArrayList<>();

        lore.removeIf(line ->
                ChatColor.stripColor(line)
                        .contains("Отравление I")
        );

        meta.setLore(lore);

        sword.setItemMeta(meta);
    }

    public boolean giveFlask(
            Player player,
            String type,
            int amount
    ) {

        if (player == null) {
            return false;
        }

        if (!type.equalsIgnoreCase("water") &&
                !type.equalsIgnoreCase("poison")) {

            return false;
        }

        amount =
                Math.max(
                        1,
                        Math.min(
                                amount,
                                64
                        )
                );

        ItemStack flask =
                new ItemStack(
                        Material.POTION,
                        amount
                );

        ItemMeta meta =
                flask.getItemMeta();

        if (meta == null) {
            return false;
        }

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        if (type.equalsIgnoreCase("water")) {

            meta.setDisplayName(
                    "§bФлакон с водой"
            );

            meta.setLore(
                    Arrays.asList(
                            "§7Смывает эффект отравления с меча"
                    )
            );

            /*
             * Старый CustomModelData оставляем.
             * Он совместим с текущим кодом и будет
             * прочитан новым resource pack через
             * range_dispatch.
             */
            meta.setCustomModelData(
                    FLASK_WATER_MODEL
            );

            pdc.set(
                    flaskTypeKey,
                    PersistentDataType.STRING,
                    "water"
            );

        } else {

            meta.setDisplayName(
                    "§aФлакон с отравлением"
            );

            meta.setLore(
                    Arrays.asList(
                            "§7Наносит отравление на меч на 3 минуты"
                    )
            );

            meta.setCustomModelData(
                    FLASK_POISON_MODEL
            );

            pdc.set(
                    flaskTypeKey,
                    PersistentDataType.STRING,
                    "poison"
            );
        }

        flask.setItemMeta(meta);

        java.util.Map<Integer, ItemStack> leftovers =
                player.getInventory().addItem(flask);

        if (!leftovers.isEmpty()) {

            for (ItemStack leftover :
                    leftovers.values()) {

                player.getWorld().dropItemNaturally(
                        player.getLocation(),
                        leftover
                );
            }

            player.sendMessage(
                    ChatColor.YELLOW +
                    "Часть флаконов не поместилась в инвентарь и была выброшена рядом."
            );
        }

        return true;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onPlayerInteract(
            PlayerInteractEvent event
    ) {

        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {

            return;
        }

        Player player =
                event.getPlayer();

        ItemStack eventItem =
                event.getItem();

        /*
         * Если игрок взаимодействует самим флаконом,
         * запрещаем обычное питьё Potion.
         */
        if (isFlask(eventItem)) {
            event.setCancelled(true);
        }

        ItemStack mainHand =
                player.getInventory()
                        .getItemInMainHand();

        ItemStack offHand =
                player.getInventory()
                        .getItemInOffHand();

        /*
         * Флакон должен находиться в оффхенде.
         */
        if (!isFlask(offHand)) {
            return;
        }

        /*
         * Для нанесения нужен Shift.
         */
        if (!player.isSneaking()) {
            return;
        }

        event.setCancelled(true);

        if (!isSword(mainHand)) {

            player.sendMessage(
                    ChatColor.RED +
                    "В основной руке должен быть меч!"
            );

            return;
        }

        String type =
                getFlaskType(offHand);

        if (type == null) {
            return;
        }

        if (type.equals("water")) {

            if (!hasPoison(mainHand)) {

                player.sendMessage(
                        ChatColor.RED +
                        "На этом мече нет эффекта отравления!"
                );

                return;
            }

            removePoisonData(mainHand);

            consumeOffhandFlask(player);

            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ENTITY_GENERIC_DRINK,
                    1.0f,
                    1.0f
            );

            player.getWorld().spawnParticle(
                    Particle.SMOKE,
                    player.getLocation().add(0, 1, 0),
                    20,
                    0.3,
                    0.3,
                    0.3,
                    0.1
            );

            player.sendMessage(
                    ChatColor.GREEN +
                    "Отравление с меча смыто!"
            );

            return;
        }

        if (type.equals("poison")) {

            if (hasPoison(mainHand)) {

                player.sendMessage(
                        ChatColor.RED +
                        "На этом мече уже есть эффект отравления!"
                );

                return;
            }

            applyPoison(mainHand);

            consumeOffhandFlask(player);

            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                    1.0f,
                    1.0f
            );

            player.getWorld().spawnParticle(
                    Particle.CRIT,
                    player.getLocation().add(0, 1, 0),
                    20,
                    0.3,
                    0.3,
                    0.3,
                    0.1
            );

            player.sendMessage(
                    ChatColor.GREEN +
                    "На меч нанесено отравление на 3 минуты!"
            );
        }
    }

    private void consumeOffhandFlask(
            Player player
    ) {

        ItemStack offhand =
                player.getInventory()
                        .getItemInOffHand();

        if (offhand == null) {
            return;
        }

        int amount =
                offhand.getAmount();

        if (amount <= 1) {

            player.getInventory()
                    .setItemInOffHand(null);

        } else {

            offhand.setAmount(
                    amount - 1
            );
        }
    }

    private void applyPoison(
            ItemStack sword
    ) {

        if (!isSword(sword)) {
            return;
        }

        ItemMeta meta =
                sword.getItemMeta();

        if (meta == null) {
            return;
        }

        /*
         * 3 минуты от текущего момента.
         */
        long expire =
                System.currentTimeMillis()
                + 180_000L;

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        pdc.set(
                poisonExpireKey,
                PersistentDataType.LONG,
                expire
        );

        List<String> lore =
                meta.hasLore()
                        ? new ArrayList<>(meta.getLore())
                        : new ArrayList<>();

        lore.removeIf(line ->
                ChatColor.stripColor(line)
                        .contains("Отравление I")
        );

        lore.add(
                "§2Отравление I §f- §a3:00"
        );

        meta.setLore(lore);

        sword.setItemMeta(meta);
    }

    @EventHandler(
            priority = EventPriority.HIGH
    )
    public void onEntityDamage(
            EntityDamageByEntityEvent event
    ) {

        if (!(event.getDamager() instanceof Player)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }

        Player player =
                (Player) event.getDamager();

        ItemStack sword =
                player.getInventory()
                        .getItemInMainHand();

        if (!isSword(sword)) {
            return;
        }

        if (!hasPoison(sword)) {
            return;
        }

        LivingEntity victim =
                (LivingEntity) event.getEntity();

        /*
         * 5 секунд отравления.
         */
        victim.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.POISON,
                        100,
                        0
                )
        );

        victim.getWorld().playSound(
                victim.getLocation(),
                Sound.ENTITY_PLAYER_ATTACK_STRONG,
                0.8f,
                1.2f
        );

        victim.getWorld().spawnParticle(
                Particle.CRIT,
                victim.getLocation().add(0, 1, 0),
                15,
                0.3,
                0.3,
                0.3,
                0.1
        );

        victim.getWorld().spawnParticle(
                Particle.SMOKE,
                victim.getLocation().add(0, 1, 0),
                10,
                0.3,
                0.3,
                0.3,
                0.1
        );
    }

    private void startUpdateTask(
            Player player
    ) {

        UUID uuid =
                player.getUniqueId();

        if (updateTasks.containsKey(uuid)) {
            return;
        }

        BukkitTask task =
                new BukkitRunnable() {

                    @Override
                    public void run() {

                        Player p =
                                Bukkit.getPlayer(uuid);

                        if (p == null ||
                                !p.isOnline()) {

                            cancel();
                            updateTasks.remove(uuid);
                            return;
                        }

                        updatePlayerPoison(
                                p
                        );
                    }

                }.runTaskTimer(
                        plugin,
                        0L,
                        20L
                );

        updateTasks.put(
                uuid,
                task
        );
    }

    private void updatePlayerPoison(
            Player player
    ) {

        boolean foundPoison =
                false;

        /*
         * Проверяем весь основной инвентарь.
         * Поэтому меч можно свободно
         * перемещать между слотами.
         */
        for (int slot = 0;
             slot < player.getInventory().getSize();
             slot++) {

            ItemStack item =
                    player.getInventory()
                            .getItem(slot);

            if (!isSword(item)) {
                continue;
            }

            if (!hasPoison(item)) {

                /*
                 * Если старый эффект уже истёк,
                 * убираем его Lore.
                 */
                if (hasLegacyPoisonLore(item)) {
                    removePoisonData(item);
                }

                continue;
            }

            foundPoison = true;

            updatePoisonLore(
                    item
            );
        }

        /*
         * Также проверяем предметы в руках,
         * если они не попали в inventory.
         */
        for (ItemStack item :
                player.getInventory().getArmorContents()) {

            if (!isSword(item)) {
                continue;
            }

            if (hasPoison(item)) {
                foundPoison = true;
                updatePoisonLore(item);
            }
        }

        if (!foundPoison) {

            BukkitTask task =
                    updateTasks.remove(
                            player.getUniqueId()
                    );

            if (task != null) {
                task.cancel();
            }
        }
    }

    private void updatePoisonLore(
            ItemStack sword
    ) {

        if (!sword.hasItemMeta()) {
            return;
        }

        ItemMeta meta =
                sword.getItemMeta();

        if (meta == null) {
            return;
        }

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        Long expire =
                pdc.get(
                        poisonExpireKey,
                        PersistentDataType.LONG
                );

        if (expire == null) {
            return;
        }

        long remaining =
                expire -
                System.currentTimeMillis();

        if (remaining <= 0) {

            removePoisonData(
                    sword
            );

            return;
        }

        long totalSeconds =
                (remaining + 999L) / 1000L;

        long minutes =
                totalSeconds / 60L;

        long seconds =
                totalSeconds % 60L;

        String time =
                String.format(
                        "%02d:%02d",
                        minutes,
                        seconds
                );

        List<String> lore =
                meta.hasLore()
                        ? new ArrayList<>(meta.getLore())
                        : new ArrayList<>();

        lore.removeIf(line ->
                ChatColor.stripColor(line)
                        .contains("Отравление I")
        );

        lore.add(
                "§2Отравление I §f- §a" +
                time
        );

        meta.setLore(lore);

        sword.setItemMeta(meta);
    }

    @EventHandler
    public void onItemHeld(
            PlayerItemHeldEvent event
    ) {

        Player player =
                event.getPlayer();

        /*
         * Никакой привязки к slot больше нет.
         * Просто запускаем обновление,
         * если оно ещё не запущено.
         */
        startUpdateTask(player);
    }

    @EventHandler
    public void onQuit(
            PlayerQuitEvent event
    ) {

        UUID uuid =
                event.getPlayer()
                        .getUniqueId();

        BukkitTask task =
                updateTasks.remove(uuid);

        if (task != null) {
            task.cancel();
        }
    }

    public void disable() {

        for (BukkitTask task :
                updateTasks.values()) {

            task.cancel();
        }

        updateTasks.clear();
    }
}
