package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.Tag;
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
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class DiceRollListener implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final Map<UUID, BukkitTask> rollingTasks = new HashMap<>();
    private final Map<UUID, Integer> waitingForHit = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final Set<UUID> activeCheaters = new HashSet<>();
    private final String CHAR_LORE = "§dБросок I";

    public DiceRollListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Использование: /d20 give <игрок> или /d20 cheat");
            return true;
        }

        if (args[0].equalsIgnoreCase("cheat")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Эту команду может использовать только игрок.");
                return true;
            }
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            if (activeCheaters.contains(uuid)) {
                activeCheaters.remove(uuid);
                player.sendMessage(ChatColor.RED + "§lЧит-режим отключен. Роллы снова случайны.");
            } else {
                activeCheaters.add(uuid);
                player.sendMessage(ChatColor.GREEN + "§lЧит-режим включен! Ваш СЛЕДУЮЩИЙ бросок гарантированно выдаст [20]!");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("give") && args.length >= 2) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                return true;
            }
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta meta = book.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§bЧародейская книга");
                meta.setLore(Collections.singletonList(CHAR_LORE));
                meta.setEnchantmentGlintOverride(true);
                book.setItemMeta(meta);
            }
            target.getInventory().addItem(book);
            sender.sendMessage(ChatColor.GREEN + "Книга выдана игроку " + target.getName());
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Неизвестный аргумент. Используйте give или cheat.");
        return true;
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (left == null || right == null) return;

        Material mat = left.getType();
        boolean isAllowedWeapon = Tag.ITEMS_SWORDS.isTagged(mat);
        
        if (!isAllowedWeapon && left.getType() != Material.ENCHANTED_BOOK) {
            event.setResult(null);
            return;
        }

        boolean leftHasD20 = hasD20Lore(left);
        boolean rightHasD20 = hasD20Lore(right);

        boolean leftHasFire = left.getEnchantments().containsKey(Enchantment.FIRE_ASPECT);
        boolean rightHasFire = right.getEnchantments().containsKey(Enchantment.FIRE_ASPECT);

        if (left.getType() == Material.ENCHANTED_BOOK && left.getItemMeta() instanceof EnchantmentStorageMeta) {
            leftHasFire = ((EnchantmentStorageMeta) left.getItemMeta()).hasStoredEnchant(Enchantment.FIRE_ASPECT);
        }
        if (right.getType() == Material.ENCHANTED_BOOK && right.getItemMeta() instanceof EnchantmentStorageMeta) {
            rightHasFire = ((EnchantmentStorageMeta) right.getItemMeta()).hasStoredEnchant(Enchantment.FIRE_ASPECT);
        }
        
        if ((leftHasD20 && rightHasFire) || (rightHasD20 && leftHasFire)) {
            event.setResult(null);
            return;
        }

        if (!leftHasD20 && !rightHasD20) return;

        ItemStack result = event.getResult();
        if (result == null || result.getType() == Material.AIR) {
            result = left.clone();
        }

        if (right.getType() == Material.ENCHANTED_BOOK && right.getItemMeta() instanceof EnchantmentStorageMeta && result.getType() == Material.ENCHANTED_BOOK) {
            EnchantmentStorageMeta resultStorage = (EnchantmentStorageMeta) result.getItemMeta();
            EnchantmentStorageMeta rightStorage = (EnchantmentStorageMeta) right.getItemMeta();
            if (resultStorage != null && rightStorage != null) {
                rightStorage.getStoredEnchants().forEach((ench, lvl) -> resultStorage.addStoredEnchant(ench, lvl, true));
                result.setItemMeta(resultStorage);
            }
        }

        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            if (meta.hasEnchant(Enchantment.FIRE_ASPECT)) {
                event.setResult(null);
                return;
            }
            
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            if (!lore.contains(CHAR_LORE)) {
                lore.add(CHAR_LORE);
                meta.setLore(lore);
            }
            meta.setEnchantmentGlintOverride(true);
            result.setItemMeta(meta);
            event.setResult(result);
            try {
                inv.setRepairCost(5);
            } catch (Exception ignored) {}
        }
    }

    @EventHandler
    public void onGrindstonePrepare(PrepareGrindstoneEvent event) {
        GrindstoneInventory inv = event.getInventory();
        ItemStack top = inv.getItem(0);
        ItemStack bottom = inv.getItem(1);
        
        ItemStack targetItem = (top != null) ? top : bottom;
        if (targetItem == null || !hasD20Lore(targetItem)) return;

        ItemMeta meta = targetItem.getItemMeta();
        if (meta == null) return;

        int vanillaEnchantsCount = meta.getEnchants().size();
        if (targetItem.getType() == Material.ENCHANTED_BOOK && meta instanceof EnchantmentStorageMeta) {
            vanillaEnchantsCount = ((EnchantmentStorageMeta) meta).getStoredEnchants().size();
        }
        
        if (vanillaEnchantsCount == 0) {
            event.setResult(null);
            return;
        }

        ItemStack result = targetItem.clone();
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta != null) {
            if (resultMeta instanceof EnchantmentStorageMeta) {
                EnchantmentStorageMeta sm = (EnchantmentStorageMeta) resultMeta;
                new ArrayList<>(sm.getStoredEnchants().keySet()).forEach(sm::removeStoredEnchant);
            } else {
                new ArrayList<>(resultMeta.getEnchants().keySet()).forEach(resultMeta::removeEnchant);
            }
            
            List<String> lore = resultMeta.hasLore() ? new ArrayList<>(resultMeta.getLore()) : new ArrayList<>();
            if (!lore.contains(CHAR_LORE)) {
                lore.add(CHAR_LORE);
            }
            resultMeta.setLore(lore);
            resultMeta.setEnchantmentGlintOverride(true);
            result.setItemMeta(resultMeta);
            event.setResult(result);
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) {
            Player victimPlayer = (Player) event.getEntity();
            UUID victimUUID = victimPlayer.getUniqueId();
            if (rollingTasks.containsKey(victimUUID) && !waitingForHit.containsKey(victimUUID)) {
                event.setDamage(event.getDamage() * 0.70);
                victimPlayer.getWorld().spawnParticle(Particle.CRIT, victimPlayer.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0.01);
            }
        }

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
            rollingTasks.get(uuid).cancel();
            rollingTasks.remove(uuid);
        }
        startDiceRoll(attacker);
    }

    private void startDiceRoll(Player player) {
        UUID uuid = player.getUniqueId();
        if (playerBossBars.containsKey(uuid)) {
            playerBossBars.remove(uuid).removeAll();
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
                    int finalRoll;
                    if (activeCheaters.contains(uuid)) {
                        finalRoll = 20;
                        activeCheaters.remove(uuid);
                    } else {
                        finalRoll = ThreadLocalRandom.current().nextInt(1, 21);
                    }
                    rollingTasks.remove(uuid);
                    this.cancel();
                    startWaitingForHitPhase(player, bossBar, finalRoll);
                    return;
                }
                double progress = (double) ticks / 80.0;
                bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                int randomNum = ThreadLocalRandom.current().nextInt(1, 21);
                String color = (ticks % 4 == 0) ? "§e§l" : "§f§l";
                bossBar.setTitle("§f§lВыпало: " + color + "[" + randomNum + "]");
                
                if (ticks % 4 == 0) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.5f);
                }
                ticks -= 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        rollingTasks.put(uuid, task);
    }
    private void startWaitingForHitPhase(Player player, BossBar bossBar, int finalRoll) {
        UUID uuid = player.getUniqueId();
        bossBar.setProgress(1.0);
        bossBar.setTitle("§f§lВыпало: §e§l[" + finalRoll + "] §f§lВремя для §e§lУДАРА!");
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
                    player.sendMessage("§c§lВремя для удара истекло!");
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

        if (roll >= 18) {
            victim.setFireTicks(60);
        }

        if (roll == 1) {
            event.setDamage(0); 
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 1.2f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.8f, 0.7f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6f, 1.5f);
            victim.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, victim.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
            victim.getWorld().spawnParticle(Particle.LARGE_SMOKE, victim.getLocation().add(0, 1, 0), 25, 0.4, 0.5, 0.4, 0.02);
            attacker.sendMessage("§c§lКРИТИЧЕСКИЙ ПРОВАЛ! Текущий удар нанес 0 урона.");
        } else if (roll <= 5) {
            event.setDamage(event.getDamage() * 0.50); 
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_SLIME_ATTACK, 1f, 0.7f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 0.8f, 0.5f);
            victim.getWorld().spawnParticle(Particle.ASH, victim.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.02);
        } else if (roll <= 9) {
            event.setDamage(event.getDamage() * 0.75); 
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_STONE_HIT, 0.6f, 1.2f);
            victim.getWorld().spawnParticle(Particle.WHITE_SMOKE, victim.getLocation().add(0, 1, 0), 12, 0.2, 0.2, 0.2, 0.01);
        } else if (roll <= 13) {
            event.setDamage(event.getDamage() * 1.50); 
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.7f, 1.8f);
            victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.1);
        } else if (roll <= 17) {
            event.setDamage(event.getDamage() * 2.00); 
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.2f, 0.9f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOWN_TO_BE_CRIT, 1f, 1.2f);
            victim.getWorld().spawnParticle(Particle.LAVA, victim.getLocation().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0.05);
        } else if (roll <= 19) {
            event.setDamage(event.getDamage() * 2.50); 
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.8f, 1.3f);
            victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.05);
        } else {
            event.setDamage(event.getDamage() * 4.00); 
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1));
            
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.9f, 1.4f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.1f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_BELL_USE, 0.5f, 1.6f);
            
            victim.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, victim.getLocation().add(0, 1, 0), 45, 0.4, 0.6, 0.4, 0.05);
            victim.getWorld().spawnParticle(Particle.LAVA, victim.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.1);
            
            Vector launchDirection = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).normalize();
            launchDirection.setY(0.75); 
            launchDirection.multiply(1.1); 
            victim.setVelocity(launchDirection);

            attacker.sendMessage("§e§lБОЖЕСТВЕННОЕ ВЕЗЕНИЕ! Ударная волна отбросила врага, Скорость II и х4.0 урон!");
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
        activeCheaters.remove(event.getPlayer().getUniqueId());
    }
    
    public void disable() {
        for (UUID uuid : new ArrayList<>(playerBossBars.keySet())) {
            cleanup(uuid);
        }
        activeCheaters.clear();
    }
}
