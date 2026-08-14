package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class DiceRollListener implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final Map<UUID, BukkitTask> rollingTasks = new HashMap<>();
    private final Map<UUID, Integer> waitingForHit = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final String CHAR_LORE = ChatColor.LIGHT_PURPLE + "Бросок I";

    public DiceRollListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Использование: /d20 give <игрок>");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Игрок не найден.");
            return true;
        }
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Зачарованная книга");
            meta.setLore(Collections.singletonList(CHAR_LORE));
            book.setItemMeta(meta);
        }
        target.getInventory().addItem(book);
        sender.sendMessage(ChatColor.GREEN + "Книга выдана игроку " + target.getName());
        return true;
    }

    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (left == null || right == null) return;
        if (right.getType() != Material.ENCHANTED_BOOK || !hasD20Lore(right)) return;
        if (!left.getType().name().endsWith("_SWORD")) return;
        
        ItemStack result = left.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            if (!lore.contains(CHAR_LORE)) {
                lore.add(CHAR_LORE);
                meta.setLore(lore);
                
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                
                result.setItemMeta(meta);
                event.setResult(result);
                inv.setRepairCost(5);
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (!hasD20Lore(hand)) return;

        UUID uuid = attacker.getUniqueId();
        if (waitingForHit.containsKey(uuid)) {
            int roll = waitingForHit.remove(uuid);
            cleanup(uuid);
            applyDiceEffects(attacker, event, roll);
            return;
        }

        if (rollingTasks.containsKey(uuid)) {
            rollingTasks.remove(uuid).cancel();
            rollingTasks.remove(uuid);
        }
        startDiceRoll(attacker);
    }

    private void startDiceRoll(Player player) {
        UUID uuid = player.getUniqueId();
        if (playerBossBars.containsKey(uuid)) {
            playerBossBars.get(uuid).removeAll();
            playerBossBars.remove(uuid);
        }
        
        BossBar bossBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
        bossBar.addPlayer(player);
        bossBar.setVisible(true);
        playerBossBars.put(uuid, bossBar);

        BukkitTask task = new BukkitRunnable() {
            int ticks = 80;
            @Override
            public void run() {
                if (!player.isOnline() || !hasD20Lore(player.getInventory().getItemInMainHand())) {
                    cleanup(uuid);
                    return;
                }
                if (ticks <= 0) {
                    int finalRoll = ThreadLocalRandom.current().nextInt(1, 21);
                    this.cancel();
                    rollingTasks.remove(uuid);
                    startWaitingForHitPhase(player, bossBar, finalRoll);
                    return;
                }
                double progress = (double) ticks / 80.0;
                bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                int randomNum = ThreadLocalRandom.current().nextInt(1, 21);
                String color = (ticks % 4 == 0) ? "§e" : "§f";
                bossBar.setTitle("§fВыпало: " + color + "[" + randomNum + "]");
                
  if (ticks % 4 == 0) {
    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HIT, 0.3f, 1.5f);
}

                ticks -= 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        rollingTasks.put(uuid, task);
    }

    private void startWaitingForHitPhase(Player player, BossBar bossBar, int finalRoll) {
        UUID uuid = player.getUniqueId();
        bossBar.setProgress(1.0);
        bossBar.setTitle("§fВыпало: §e[" + finalRoll + "] §fВремя для §eУДАРА!");
        waitingForHit.put(uuid, finalRoll);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f);

        BukkitTask task = new BukkitRunnable() {
            int ticks = 100;
            @Override
            public void run() {
                if (!player.isOnline() || !waitingForHit.containsKey(uuid)) {
                    cleanup(uuid);
                    return;
                }
                if (!hasD20Lore(player.getInventory().getItemInMainHand())) {
                    cleanup(uuid);
                    return;
                }
                if (ticks <= 0) {
                    cleanup(uuid);
                    player.sendMessage("§cВремя для удара истекло!");
                    return;
                }
                double progress = (double) ticks / 100.0;
                bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                ticks -= 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        rollingTasks.put(uuid, task);
    }

    private void applyDiceEffects(Player attacker, EntityDamageByEntityEvent event, int roll) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        LivingEntity victim = (LivingEntity) event.getEntity();

        if (roll == 1) {
            event.setDamage(0);
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
            attacker.getWorld().spawnParticle(Particle.SMOKE, attacker.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.05);
            attacker.sendMessage("§c§lКРИТИЧЕСКИЙ ПРОВАЛ! Следующий удар нанес 0 урона.");
        } else if (roll <= 9) {
            event.setDamage(event.getDamage() * 0.6);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            victim.getWorld().spawnParticle(Particle.WHITE_SMOKE, victim.getLocation().add(0, 1, 0), 10, 0.2, 0.2, 0.2, 0.02);
        } else if (roll <= 14) {
            event.setDamage(event.getDamage() + 2.0);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
            victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.1);
        } else if (roll <= 19) {
            event.setDamage(event.getDamage() * 1.5);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.8f, 1.3f);
            victim.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, victim.getLocation().add(0, 1, 0), 25, 0.4, 0.4, 0.4, 0.1);
        } else {
            event.setDamage(event.getDamage() * 2.5);
            victim.setFireTicks(60);
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1));
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.9f, 1.5f);
            victim.getWorld().spawnParticle(Particle.EXPLOSION, victim.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
            attacker.sendMessage("§e§lКРИТИЧЕСКИЙ УСПЕХ! Скорость II и х2.5 урон!");
        }
    }

    private boolean hasD20Lore(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasLore() && meta.getLore().contains(CHAR_LORE);
    }

    private void cleanup(UUID uuid) {
        waitingForHit.remove(uuid);
        if (rollingTasks.containsKey(uuid)) {
            rollingTasks.get(uuid).cancel();
            rollingTasks.remove(uuid);
        }
        if (playerBossBars.containsKey(uuid)) {
            playerBossBars.get(uuid).removeAll();
            playerBossBars.remove(uuid);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId());
    }
    
    public void disable() {
        for (UUID uuid : new ArrayList<>(playerBossBars.keySet())) {
            cleanup(uuid);
        }
    }
}
