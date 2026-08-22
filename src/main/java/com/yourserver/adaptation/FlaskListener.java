package com.yourserver.adaptation;

import org.bukkit.ChatColor;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

public class FlaskListener implements Listener {

    private final JavaPlugin plugin;

    /*
     * Эти ключи находятся непосредственно внутри предмета.
     * Поэтому переименование предмета ничего не меняет.
     */
    private final NamespacedKey flaskTypeKey;
    private final NamespacedKey poisonExpireKey;

    public FlaskListener(JavaPlugin plugin) {
        this.plugin = plugin;

        flaskTypeKey = new NamespacedKey(
                plugin,
                "flask_type"
        );

        poisonExpireKey = new NamespacedKey(
                plugin,
                "poison_expire"
        );
    }

    /*
     * Определяем меч.
     */
    private boolean isSword(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        return item.getType().name().endsWith("_SWORD");
    }

    /*
     * Флакон определяется ТОЛЬКО по PDC.
     *
     * Поэтому:
     * - воду нельзя превратить в яд простым переименованием;
     * - яд нельзя превратить в воду простым переименованием.
     */
    private boolean isFlask(ItemStack item) {
        if (item == null ||
                item.getType() != Material.POTION ||
                !item.hasItemMeta()) {
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

    /*
     * Проверяем яд непосредственно на мече.
     */
    private boolean hasPoison(ItemStack sword) {

        if (!isSword(sword) || !sword.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = sword.getItemMeta();

        if (meta == null) {
            return false;
        }

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        Long expire = pdc.get(
                poisonExpireKey,
                PersistentDataType.LONG
        );

        if (expire == null) {
            return false;
        }

        /*
         * Время закончилось.
         */
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

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        /*
         * Ровно 3 минуты.
         */
        long expire =
                System.currentTimeMillis() + 180_000L;

        pdc.set(
                poisonExpireKey,
                PersistentDataType.LONG,
                expire
        );

        /*
         * Только одна короткая строка.
         * Таймер сюда НЕ записываем.
         *
         * Поэтому ItemMeta больше не меняется
         * каждую секунду и меч не дёргается.
         */
        List<String> lore =
                meta.hasLore()
                        ? new ArrayList<>(meta.getLore())
                        : new ArrayList<>();

        lore.removeIf(line ->
                ChatColor.stripColor(line)
                        .contains("Отравление I")
        );

        lore.add("§2Отравление I");

        meta.setLore(lore);

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

        PersistentDataContainer pdc =
                meta.getPersistentDataContainer();

        pdc.remove(poisonExpireKey);

        if (meta.hasLore()) {

            List<String> lore =
                    new ArrayList<>(meta.getLore());

            lore.removeIf(line ->
                    ChatColor.stripColor(line)
                            .contains("Отравление I")
            );

            if (lore.isEmpty()) {
                meta.setLore(null);
            } else {
                meta.setLore(lore);
            }
        }

        sword.setItemMeta(meta);
    }

    /*
     * Выдача флакона.
     *
     * Никакого Lore.
     * Никаких описаний.
     */
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

        amount = Math.max(
                1,
                Math.min(amount, 64)
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

            /*
             * Название.
             */
            meta.setDisplayName(
                    "§fФлакон с водой"
            );

            /*
             * PDC — реальный тип предмета.
             */
            pdc.set(
                    flaskTypeKey,
                    PersistentDataType.STRING,
                    "water"
            );

            /*
             * ВАЖНО:
             * больше CustomModelData 1001 здесь нет.
             *
             * Используем современный item_model
             * Paper 1.21.11.
             */
            meta.setItemModel(
                    new NamespacedKey(
                            "minecraft",
                            "flask_water"
                    )
            );

        } else {

            meta.setDisplayName(
                    "§fФлакон с отравлением"
            );

            pdc.set(
                    flaskTypeKey,
                    PersistentDataType.STRING,
                    "poison"
            );

            meta.setItemModel(
                    new NamespacedKey(
                            "minecraft",
                            "flask_poison"
                    )
            );
        }

        flask.setItemMeta(meta);

        var leftovers =
                player.getInventory().addItem(flask);

        /*
         * Если инвентарь полный — предмет падает,
         * но игроку ничего не пишем в чат.
         */
        for (ItemStack leftover :
                leftovers.values()) {

            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    leftover
            );
        }

        return true;
    }

    /*
     * Shift + ПКМ.
     *
     * Меч в основной руке.
     * Флакон в левой.
     */
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

        ItemStack offHand =
                player.getInventory()
                        .getItemInOffHand();

        /*
         * Если это вообще не наш флакон —
         * ничего не трогаем.
         */
        if (!isFlask(offHand)) {
            return;
        }

        /*
         * Наш флакон нельзя пить как обычное зелье.
         */
        event.setCancelled(true);

        if (!player.isSneaking()) {
            return;
        }

        ItemStack mainHand =
                player.getInventory()
                        .getItemInMainHand();

        if (!isSword(mainHand)) {
            return;
        }

        String flaskType =
                getFlaskType(offHand);

        if (flaskType == null) {
            return;
        }

        /*
         * ВОДА
         */
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

        /*
         * ОТРАВЛЕНИЕ
         */
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

        ItemStack flask =
                player.getInventory()
                        .getItemInOffHand();

        if (flask == null) {
            return;
        }

        int amount =
                flask.getAmount();

        if (amount <= 1) {

            player.getInventory()
                    .setItemInOffHand(null);

        } else {

            flask.setAmount(
                    amount - 1
            );
        }
    }

    /*
     * Удар отравленным мечом.
     */
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

        LivingEntity victim =
                (LivingEntity) event.getEntity();

        ItemStack sword =
                player.getInventory()
                        .getItemInMainHand();

        if (!isSword(sword)) {
            return;
        }

        /*
         * Здесь одновременно проверяется
         * и наличие яда, и его срок.
         */
        if (!hasPoison(sword)) {
            return;
        }

        /*
         * 5 секунд Poison I.
         */
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
    public void onQuit(PlayerQuitEvent event) {
        /*
         * Ничего не нужно очищать.
         *
         * Таймер находится непосредственно
         * на мече, поэтому после выхода
         * игрока он продолжит отсчитываться
         * по реальному времени.
         */
    }

    public void disable() {
        /*
         * Больше нет каждосекундных задач.
         */
    }
}
