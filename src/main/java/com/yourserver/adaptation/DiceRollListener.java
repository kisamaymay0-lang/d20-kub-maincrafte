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
import org.bukkit.event.player.PlayerItemHeldEvent;
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
    private final Map<UUID, Integer> activeCheaters = new HashMap<>();
    private final Map<UUID, Long> cheatCooldowns = new HashMap<>();
    private final String CHAR_LORE = "§dБросок I";
    private static final int CHEAT_COOLDOWN_SECONDS = 5;

    // Ссылка на основной плагин, чтобы вызывать метод лома адаптации
    private final AdaptationPlugin adaptationPlugin;

    public DiceRollListener(JavaPlugin plugin) {
        this.plugin = plugin;
        // Один и тот же плагин, поэтому безопасно сохраняем ссылку.
        // Если вдруг класс используется отдельно — просто не ломаем адаптацию.
        this.adaptationPlugin =
                plugin instanceof AdaptationPlugin
                        ? (AdaptationPlugin) plugin
                        : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Использование: /d20 give <игрок>, /d20 cheat <1-20> или /d20 enchant <ID>");
            return true;
        }

        if (args[0].equalsIgnoreCase("cheat")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Эту команду может использовать только игрок.");
                return true;
            }
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();

            if (cheatCooldowns.containsKey(uuid)) {
                long timeLeft = (cheatCooldowns.get(uuid) + CHEAT_COOLDOWN_SECONDS * 1000L) - System.currentTimeMillis();
                if (timeLeft > 0) {
                    player.sendMessage(ChatColor.RED + "Подождите " + (timeLeft / 1000 + 1) + " секунд перед следующим использованием чита!");
                    return true;
                }
            }

            if (args.length < 2) {
                if (activeCheaters.containsKey(uuid)) {
                    activeCheaters.remove(uuid);
                    cheatCooldowns.remove(uuid);
                    player.sendMessage(ChatColor.RED + "Чит-режим отключен. Роллы снова случайны.");
                } else {
                    player.sendMessage(ChatColor.RED + "Укажите число! Пример: /d20 cheat 20");
                }
                return true;
            }

            try {
                int targetRoll = Integer.parseInt(args[1]);
                if (targetRoll < 1 || targetRoll > 20) {
                    player.sendMessage(ChatColor.RED + "Число кубика должно быть строго от 1 до 20!");
                    return true;
                }
                activeCheaters.put(uuid, targetRoll);
                cheatCooldowns.put(uuid, System.currentTimeMillis());
                player.sendMessage(ChatColor.GREEN + "Чит-режим активирован! Следующий удар гарантированно выдаст: §e§l[" + targetRoll + "]");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Некорректное число! Пример: /d20 cheat 7");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("enchant")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Эту команду может использовать только игрок.");
                return true;
            }
            Player player = (Player) sender;
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Укажите ID предмета! Пример: /d20 enchant mace");
                return true;
            }

            String matName = args[1].toUpperCase();
            Material material = Material.getMaterial(matName);
            if (material == null || material == Material.AIR) {
                player.sendMessage(ChatColor.RED + "Предмет с ID '" + args[1] + "' не найден в базе Minecraft!");
                return true;
            }

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(CHAR_LORE);
                meta.setLore(lore);
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }

            player.getInventory().addItem(item);
            player.sendMessage(ChatColor.GREEN + "Вам успешно выдан предмет " + material.name() + " с чаром Бросок I!");
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

        sender.sendMessage(ChatColor.RED + "Неизвестный аргумент. Используйте give, cheat или enchant.");
        return true;
    }

    // ===== МЕТОД ДЛЯ ПРОВЕРКИ ЛОРА "БРОСОК" =====
    public boolean hasD20Lore(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;

        for (String line : meta.getLore()) {
            String cleanLine = ChatColor.stripColor(line).trim();
            if (cleanLine.equals("Бросок I")) {
                return true;
            }
        }
        return false;
    }

    // ===== ПРОВЕРКА АДАПТАЦИИ (для запрета объединения) =====
    private boolean hasAdaptationLore(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;

        for (String line : meta.getLore()) {
            String cleanLine = ChatColor.stripColor(line).trim();
            if (cleanLine.startsWith("Адаптация")) {
                return true;
            }
        }
        return false;
    }

    // ===== НАКОВАЛЬНЯ: ЗАПРЕТ ОБЪЕДИНЕНИЯ БРОСКА И АДАПТАЦИИ =====
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);

        if (left == null || right == null) return;

        boolean leftHasD20 = hasD20Lore(left);
        boolean rightHasD20 = hasD20Lore(right);
        boolean leftHasAdaptation = hasAdaptationLore(left);
        boolean rightHasAdaptation = hasAdaptationLore(right);

        // ЗАПРЕЩАЕМ ОБЪЕДИНЕНИЕ
        if ((leftHasD20 && rightHasAdaptation) || (leftHasAdaptation && rightHasD20)) {
            event.setResult(null);
            return;
        }

        // Если нет броска — выходим
        if (!leftHasD20 && !rightHasD20) return;

        // Обычная логика накладывания броска (как было)
        boolean isSword = Tag.ITEMS_SWORDS.isTagged(left.getType());
        boolean isBook = left.getType() == Material.ENCHANTED_BOOK;

        if (!isSword && !isBook) {
            event.setResult(null);
            return;
        }

        // Проверка несовместимости с Заговором огня
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

        if (!isSword && left.getType() != Material.ENCHANTED_BOOK) {
            event.setResult(null);
            return;
        }

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
            if (!hasD20Lore(result)) {
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

    // ===== ОСТАЛЬНЫЕ МЕТОДЫ =====
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
                Set<Enchantment> enchants = new HashSet<>(sm.getStoredEnchants().keySet());
                for (Enchantment ench : enchants) {
                    sm.removeStoredEnchant(ench);
                }
            } else {
                Set<Enchantment> enchants = new HashSet<>(resultMeta.getEnchants().keySet());
                for (Enchantment ench : enchants) {
                    resultMeta.removeEnchant(ench);
                }
            }

            List<String> lore = resultMeta.hasLore() ? new ArrayList<>(resultMeta.getLore()) : new ArrayList<>();
            if (!hasD20Lore(result)) {
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
        if (event.isCancelled()) return;

        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            UUID victimUUID = victim.getUniqueId();
            if (rollingTasks.containsKey(victimUUID) && !waitingForHit.containsKey(victimUUID)) {
                if (!(event.getDamager() instanceof Player && event.getDamager().equals(victim))) {
                    event.setDamage(event.getDamage() * 0.70);
                    victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0.01);
                }
            }
        }

        if (!(event.getDamager() instanceof Player)) return;
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

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (rollingTasks.containsKey(uuid) && !waitingForHit.containsKey(uuid)) {
            ItemStack newHand = player.getInventory().getItem(event.getNewSlot());
            if (!hasD20Lore(newHand)) {
                cleanup(uuid);
                player.sendMessage(ChatColor.RED + "Бафф чара \"Бросок I\" был отменен, так как вы сменили предмет в руке!");
            }
        }
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
                    int finalRoll;
                    if (activeCheaters.containsKey(uuid)) {
                        finalRoll = activeCheaters.remove(uuid);
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
                    player.sendMessage(ChatColor.RED + "Бафф чара \"Бросок I\" был отменен, так как вы сменили предмет в руке!");
                    return;
                }
                if (ticks <= 0) {
                    cleanup(uuid);
                    player.sendMessage(ChatColor.RED + "Время для удара истекло!");
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
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }

        final LivingEntity victim = (LivingEntity) event.getEntity();

        // ===== КРИТИЧЕСКАЯ УДАЧА (20) — ЛОМАЕМ АДАПТАЦИЮ ВРАГА =====
        if (roll == 20 && victim instanceof Player) {
            Player victimPlayer = (Player) victim;
            if (adaptationPlugin != null) {
                adaptationPlugin.breakAdaptation(victimPlayer);
            }
        }

        // ===== ОСТАЛЬНАЯ ЛОГИКА (как была) =====
        if (roll == 1) {
            event.setCancelled(true);
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 1.2f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.8f, 0.7f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6f, 1.5f);

            victim.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, victim.getLocation().add(0, 1, 0), 2, 0.1, 0.1, 0.1, 0);
            victim.getWorld().spawnParticle(Particle.LARGE_SMOKE, victim.getLocation().add(0, 1, 0), 85, 0.5, 0.6, 0.5, 0.03);

            attacker.sendMessage("§c§lКРИТИЧЕСКИЙ ПРОВАЛ! Текущий удар нанес 0 урона.");
            return;
        }

        double multiplier;
        Sound hitSound;
        Particle particle;
        int particleCount;

        if (roll <= 5) {
            multiplier = 0.50;
            hitSound = Sound.ENTITY_SLIME_ATTACK;
            particle = Particle.ASH;
            particleCount = 65;
        } else if (roll <= 9) {
            multiplier = 0.75;
            hitSound = Sound.ITEM_SHIELD_BLOCK;
            particle = Particle.WHITE_SMOKE;
            particleCount = 50;
        } else if (roll <= 13) {
            multiplier = 1.50;
            hitSound = Sound.BLOCK_ANVIL_PLACE;
            particle = Particle.CRIT;
            particleCount = 18;
        } else if (roll <= 17) {
            multiplier = 2.00;
            hitSound = Sound.ITEM_SHIELD_BREAK;
            particle = Particle.LAVA;
            particleCount = 20;
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1.2f);
        } else if (roll <= 19) {
            multiplier = 2.50;
            hitSound = Sound.ENTITY_DRAGON_FIREBALL_EXPLODE;
            particle = Particle.FLAME;
            particleCount = 30;
        } else {
            // roll == 20 — уже обработали выше
            multiplier = 4.00;
            hitSound = Sound.ENTITY_LIGHTNING_BOLT_THUNDER;
            particle = Particle.SOUL_FIRE_FLAME;
            particleCount = 45;

            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1));
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.1f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_BELL_USE, 0.5f, 1.6f);

            victim.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, victim.getLocation().add(0, 1, 0), 45, 0.4, 0.6, 0.4, 0.05);
            victim.getWorld().spawnParticle(Particle.LAVA, victim.getLocation().add(0, 1, 0), 35, 0.3, 0.5, 0.3, 0.1);

            Vector launchDirection = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
            if (launchDirection.lengthSquared() == 0) {
                launchDirection = new Vector(1, 0, 0);
            } else {
                launchDirection.normalize();
            }

            launchDirection.setY(1.25);
            launchDirection.setX(launchDirection.getX() * 1.1);
            launchDirection.setZ(launchDirection.getZ() * 1.1);

            final Vector finalVector = new Vector(launchDirection.getX(), launchDirection.getY(), launchDirection.getZ());
            final JavaPlugin finalPlugin = this.plugin;
            final LivingEntity finalVictim = victim;

            finalPlugin.getServer().getScheduler().runTaskLater(finalPlugin, new Runnable() {
                @Override
                public void run() {
                    if (!finalVictim.isDead()) {
                        finalVictim.setVelocity(new Vector(0, 0, 0));
                        finalVictim.setVelocity(finalVector);
                    }
                }
            }, 1L);

            // ВАЖНО: отменяем ТОЛЬКО этот таймер, а не все задачи плагина.
            // Раньше здесь был Bukkit.getScheduler().cancelTasks(...), который
            // гасил вообще все таймеры (адаптацию, откат, отравление, нотные блоки).
            finalPlugin.getServer().getScheduler().runTaskTimer(finalPlugin, new BukkitRunnable() {
                int timer = 30;
                @Override
                public void run() {
                    if (finalVictim.isDead() || timer <= 0 || finalVictim.isOnGround()) {
                        this.cancel();
                        return;
                    }
                    finalVictim.getWorld().spawnParticle(Particle.EXPLOSION, finalVictim.getLocation().add(0, 0.8, 0), 2, 0.1, 0.1, 0.1, 0.01);
                    timer -= 2;
                }
            }, 2L, 2L);

            attacker.sendMessage("§e§lБОЖЕСТВЕННОЕ ВЕЗЕНИЕ! Мощная взрывная волна откинула врага, Скорость II и х4.0 урон!");

            event.setDamage(event.getDamage() * multiplier);

            if (roll >= 18) {
                victim.setFireTicks(60);
            }
            return;
        }

        event.setDamage(event.getDamage() * multiplier);
        attacker.getWorld().playSound(attacker.getLocation(), hitSound, 1f, 1.2f);
        victim.getWorld().spawnParticle(particle, victim.getLocation().add(0, 1, 0), particleCount, 0.4, 0.5, 0.4, 0.05);

        if (roll >= 18) {
            victim.setFireTicks(60);
        }
    }

    private void cleanup(UUID uuid) {
        waitingForHit.remove(uuid);
        if (rollingTasks.containsKey(uuid)) {
            BukkitTask task = rollingTasks.get(uuid);
            if (task != null) {
                task.cancel();
            }
            rollingTasks.remove(uuid);
        }
        if (playerBossBars.containsKey(uuid)) {
            BossBar bossBar = playerBossBars.get(uuid);
            if (bossBar != null) {
                bossBar.removeAll();
            }
            playerBossBars.remove(uuid);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId());
        activeCheaters.remove(event.getPlayer().getUniqueId());
        cheatCooldowns.remove(event.getPlayer().getUniqueId());
    }

    public void disable() {
        for (UUID uuid : new ArrayList<>(playerBossBars.keySet())) {
            cleanup(uuid);
        }
        activeCheaters.clear();
        cheatCooldowns.clear();
    }
}
