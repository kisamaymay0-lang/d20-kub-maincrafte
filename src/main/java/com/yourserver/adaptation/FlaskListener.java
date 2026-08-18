package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class FlaskListener implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, FlaskData> activeFlasks = new HashMap<>(); // UUID игрока -> данные о флаконе
    private final Map<UUID, BukkitTask> updateTasks = new HashMap<>();

    private final int FLASK_WATER_MODEL = 1001;
    private final int FLASK_POISON_MODEL = 1002;

    public FlaskListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static class FlaskData {
        String type;
        int duration; // в секундах
        int slot; // слот меча

        FlaskData(String type, int duration, int slot) {
            this.type = type;
            this.duration = duration;
            this.slot = slot;
        }
    }

    private boolean isSword(ItemStack item) {
        if (item == null) return false;
        String type = item.getType().name();
        return type.contains("SWORD");
    }

    private boolean isFlask(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return false;
        String name = ChatColor.stripColor(meta.getDisplayName());
        return name.equals("Флакон с водой") || name.equals("Флакон с отравлением");
    }

    private String getFlaskType(ItemStack item) {
        if (!isFlask(item)) return null;
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (name.equals("Флакон с водой")) return "water";
        if (name.equals("Флакон с отравлением")) return "poison";
        return null;
    }

    private boolean hasFlaskEffect(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return false;
        for (String line : item.getItemMeta().getLore()) {
            if (line.contains("Отравление I")) return true;
        }
        return false;
    }

    public boolean giveFlask(Player player, String type, int amount) {
        if (player == null) return false;

        ItemStack flask = new ItemStack(Material.POTION);
        ItemMeta meta = flask.getItemMeta();

        if (type.equalsIgnoreCase("water")) {
            meta.setDisplayName("§bФлакон с водой");
            meta.setLore(Arrays.asList("§7Смывает эффекты флаконов с меча"));
            meta.setCustomModelData(FLASK_WATER_MODEL);
        } else if (type.equalsIgnoreCase("poison")) {
            meta.setDisplayName("§aФлакон с отравлением");
            meta.setLore(Arrays.asList("§7Наносит отравление на меч на 3 минуты"));
            meta.setCustomModelData(FLASK_POISON_MODEL);
        } else {
            return false;
        }

        flask.setItemMeta(meta);
        flask.setAmount(Math.min(amount, 64));

        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItem(player.getLocation(), flask);
            player.sendMessage(ChatColor.RED + "Ваш инвентарь полон! Флакон упал на землю.");
        } else {
            player.getInventory().addItem(flask);
        }

        return true;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!player.isSneaking()) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        if (!isSword(mainHand)) {
            player.sendMessage(ChatColor.RED + "В основной руке должен быть меч!");
            return;
        }

        if (!isFlask(offHand)) {
            player.sendMessage(ChatColor.RED + "В офф-руке должен быть флакон!");
            return;
        }

        String flaskType = getFlaskType(offHand);
        if (flaskType == null) return;

        event.setCancelled(true);

        if (flaskType.equals("water")) {
            // Смываем эффект
            if (!hasFlaskEffect(mainHand)) {
                player.sendMessage(ChatColor.RED + "На этом мече нет эффекта флакона!");
                return;
            }
            removeFlaskFromSword(player);
            offHand.setAmount(offHand.getAmount() - 1);
            if (offHand.getAmount() <= 0) {
                player.getInventory().setItemInOffHand(null);
            }
        } else if (flaskType.equals("poison")) {
            // Наносим эффект
            if (hasFlaskEffect(mainHand)) {
                player.sendMessage(ChatColor.RED + "На этом мече уже есть эффект флакона!");
                return;
            }
            applyFlaskToSword(player, mainHand);
            offHand.setAmount(offHand.getAmount() - 1);
            if (offHand.getAmount() <= 0) {
                player.getInventory().setItemInOffHand(null);
            }
        }
    }

    private void applyFlaskToSword(Player player, ItemStack sword) {
        if (!isSword(sword)) {
            player.sendMessage(ChatColor.RED + "Флакон можно нанести только на меч!");
            return;
        }

        if (hasFlaskEffect(sword)) {
            player.sendMessage(ChatColor.RED + "На этом мече уже есть эффект флакона!");
            return;
        }

        ItemMeta meta = sword.getItemMeta();
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.removeIf(line -> line.contains("Отравление I"));
        lore.add("§2Отравление I §f- §a3:00");
        meta.setLore(lore);
        sword.setItemMeta(meta);

        int slot = player.getInventory().getHeldItemSlot();
        UUID uuid = player.getUniqueId();
        
        // Очищаем старые данные
        if (activeFlasks.containsKey(uuid)) {
            activeFlasks.remove(uuid);
            if (updateTasks.containsKey(uuid)) {
                updateTasks.get(uuid).cancel();
                updateTasks.remove(uuid);
            }
        }
        
        FlaskData data = new FlaskData("poison", 180, slot);
        activeFlasks.put(uuid, data);

        if (!updateTasks.containsKey(uuid)) {
            startUpdateTask(player);
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.1);
        // Сообщение убрано
    }

    private void removeFlaskFromSword(Player player) {
        UUID uuid = player.getUniqueId();
        
        if (!activeFlasks.containsKey(uuid)) {
            return;
        }

        FlaskData data = activeFlasks.get(uuid);
        ItemStack sword = player.getInventory().getItem(data.slot);
        
        if (sword != null && isSword(sword)) {
            ItemMeta meta = sword.getItemMeta();
            if (meta != null && meta.hasLore()) {
                List<String> lore = meta.getLore();
                lore.removeIf(line -> line.contains("Отравление I"));
                meta.setLore(lore);
                sword.setItemMeta(meta);
            }
        }

        activeFlasks.remove(uuid);
        if (updateTasks.containsKey(uuid)) {
            updateTasks.get(uuid).cancel();
            updateTasks.remove(uuid);
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
        player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.1);
        // Сообщение убрано
    }

    private void startUpdateTask(Player player) {
        UUID uuid = player.getUniqueId();
        updateTasks.put(uuid, new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) {
                    this.cancel();
                    updateTasks.remove(uuid);
                    return;
                }

                FlaskData data = activeFlasks.get(uuid);
                if (data == null) {
                    this.cancel();
                    updateTasks.remove(uuid);
                    return;
                }

                data.duration--;

                // Обновляем лор на мече (даже если он в другом слоте)
                ItemStack sword = p.getInventory().getItem(data.slot);
                if (sword == null || !isSword(sword)) {
                    // Меч пропал - удаляем эффект
                    activeFlasks.remove(uuid);
                    this.cancel();
                    updateTasks.remove(uuid);
                    return;
                }

                ItemMeta meta = sword.getItemMeta();
                if (meta == null || !meta.hasLore()) {
                    activeFlasks.remove(uuid);
                    this.cancel();
                    updateTasks.remove(uuid);
                    return;
                }

                List<String> lore = meta.getLore();
                boolean found = false;
                for (int i = 0; i < lore.size(); i++) {
                    if (lore.get(i).contains("Отравление I")) {
                        int minutes = data.duration / 60;
                        int seconds = data.duration % 60;
                        String timeStr = String.format("%02d:%02d", minutes, seconds);
                        lore.set(i, "§2Отравление I §f- §a" + timeStr);
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    // Лор пропал - удаляем эффект
                    activeFlasks.remove(uuid);
                    this.cancel();
                    updateTasks.remove(uuid);
                    return;
                }
                
                meta.setLore(lore);
                sword.setItemMeta(meta);

                if (data.duration <= 0) {
                    // Эффект закончился
                    lore.removeIf(line -> line.contains("Отравление I"));
                    meta.setLore(lore);
                    sword.setItemMeta(meta);
                    
                    p.sendMessage(ChatColor.RED + "Эффект флакона на мече закончился!");
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
                    p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.05);
                    
                    activeFlasks.remove(uuid);
                    this.cancel();
                    updateTasks.remove(uuid);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)); // Каждую секунду
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        
        Player player = (Player) event.getDamager();
        LivingEntity victim = (LivingEntity) event.getEntity();
        UUID uuid = player.getUniqueId();

        // Проверяем, есть ли активный флакон у игрока
        if (!activeFlasks.containsKey(uuid)) return;
        
        FlaskData data = activeFlasks.get(uuid);
        if (!data.type.equals("poison")) return;

        // Проверяем, что бьем мечом
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isSword(hand)) return;
        
        // Проверяем, что меч имеет эффект флакона
        if (!hasFlaskEffect(hand)) {
            // Если эффекта нет в лоре, но в памяти есть - удаляем
            activeFlasks.remove(uuid);
            if (updateTasks.containsKey(uuid)) {
                updateTasks.get(uuid).cancel();
                updateTasks.remove(uuid);
            }
            return;
        }

        // Накладываем отравление на 5 секунд
        victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 0)); // 5 секунд = 100 тиков
        
        // Эффекты удара
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.2f);
        victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.1);
        victim.getWorld().spawnParticle(Particle.SPELL_WITCH, victim.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        // Обновляем слот в данных при смене слота
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        if (activeFlasks.containsKey(uuid)) {
            FlaskData data = activeFlasks.get(uuid);
            if (data.slot == event.getPreviousSlot()) {
                data.slot = event.getNewSlot();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        activeFlasks.remove(uuid);
        if (updateTasks.containsKey(uuid)) {
            updateTasks.get(uuid).cancel();
            updateTasks.remove(uuid);
        }
    }

    public void disable() {
        activeFlasks.clear();
        for (BukkitTask task : updateTasks.values()) {
            task.cancel();
        }
        updateTasks.clear();
    }
}
