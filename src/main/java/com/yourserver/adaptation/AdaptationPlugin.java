package com.yourserver.adaptation;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.Color;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AdaptationPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Map<String, Integer>> damageCounters = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> superDamageCounters = new HashMap<>();
    private final Map<UUID, BukkitTask> activeTimers = new HashMap<>();
    private final Map<UUID, String> activeAdaptations = new HashMap<>();
    private final Map<UUID, Boolean> superAdaptations = new HashMap<>();
    private final Map<UUID, Long> lastHitTime = new HashMap<>();
    private final Map<UUID, BossBar> activeBossBars = new HashMap<>();
    private final Map<UUID, Double> activeTimesLeft = new HashMap<>();
    private final Map<UUID, Double> activeMaxTimes = new HashMap<>();
    private final Map<UUID, Long> cooldownEndTimes = new HashMap<>();
    private DiceRollListener diceRollListener;

    // Кешированные объекты для частиц
    private final Particle.DustOptions meleeDust = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.2f);
    private final Particle.DustOptions rangedDust = new Particle.DustOptions(Color.fromRGB(0, 255, 0), 1.2f);
    private final Particle.DustOptions magicDust = new Particle.DustOptions(Color.fromRGB(200, 0, 255), 1.2f);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("adaptation") != null) {
            getCommand("adaptation").setExecutor(this);
        }

        this.diceRollListener = new DiceRollListener(this);
        getServer().getPluginManager().registerEvents(this.diceRollListener, this);
        if (getCommand("d20") != null) {
            getCommand("d20").setExecutor(this.diceRollListener);
        }

        getLogger().info("Плагин AdaptationPlugin [COOLDOWN-UPDATE + D20] успешно запущен!");
    }

    @Override
    public void onDisable() {
        // Централизованная очистка
        cleanupAll();
        if (this.diceRollListener != null) {
            this.diceRollListener.disable();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("adaptation.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Конфигурация AdaptationPlugin успешно перезагружена!");
            return true;
        }

        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Использование: /adaptation give <игрок> <1/2/3> ИЛИ /adaptation reload");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Игрок не найден или оффлайн!");
            return true;
        }

        int lvl;
        try {
            lvl = Integer.parseInt(args[2]);
            if (lvl < 1 || lvl > 3) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Уровень должен быть от 1 до 3!");
            return true;
        }

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Чародейская книга");
            String strLvl = lvl == 1 ? "I" : lvl == 2 ? "II" : "III";
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.LIGHT_PURPLE + "Адаптация " + strLvl);
            meta.setLore(lore);
            meta.setEnchantmentGlintOverride(true);
            book.setItemMeta(meta);
        }

        target.getInventory().addItem(book);
        sender.sendMessage(ChatColor.GREEN + "Книга Адаптация " + args[2] + " выдана игроку " + target.getName());
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        ItemStack left = anvil.getItem(0);
        ItemStack right = anvil.getItem(1);

        if (left == null || right == null) return;

        int lvlLeft = getLvlFromLore(left);
        int lvlRight = getLvlFromLore(right);

        if (lvlLeft == 0 && lvlRight == 0) return;

        int finalLvl = (lvlLeft == lvlRight && lvlLeft < 3) ? lvlLeft + 1 : Math.max(lvlLeft, lvlRight);
        ItemStack result = left.clone();

        // ====== ИСПРАВЛЕННАЯ ПОЧИНКА ======
        if (left.getType() != Material.ENCHANTED_BOOK && result.getItemMeta() instanceof Damageable) {
            Damageable targetDamageMeta = (Damageable) result.getItemMeta();
            int currentDamage = targetDamageMeta.getDamage();
            int maxDurability = left.getType().getMaxDurability();

            if (right.getType() == left.getType()) {
                if (right.getItemMeta() instanceof Damageable) {
                    Damageable rightDamageMeta = (Damageable) right.getItemMeta();
                    int rightDamage = rightDamageMeta.getDamage();
                    int bonus = (int) (maxDurability * 0.12);
                    int newDamage = Math.max(0, currentDamage - (maxDurability - rightDamage) - bonus);
                    targetDamageMeta.setDamage(newDamage);
                    result.setItemMeta(targetDamageMeta);
                }
            } else {
                String matName = left.getType().name();
                boolean canRepair = false;
                if (matName.contains("DIAMOND") && right.getType() == Material.DIAMOND) canRepair = true;
                else if (matName.contains("NETHERITE") && right.getType() == Material.NETHERITE_INGOT) canRepair = true;
                else if (matName.contains("IRON") && right.getType() == Material.IRON_INGOT) canRepair = true;
                else if (matName.contains("GOLD") && right.getType() == Material.GOLD_INGOT) canRepair = true;
                else if (matName.contains("CHAINMAIL") && right.getType() == Material.IRON_INGOT) canRepair = true;
                else if (matName.contains("LEATHER") && right.getType() == Material.LEATHER) canRepair = true;

                if (canRepair) {
                    int repairAmount = (int) (maxDurability * 0.25);
                    int itemsNeeded = (int) Math.ceil((double) currentDamage / repairAmount);
                    int itemsUsed = Math.min(right.getAmount(), itemsNeeded);
                    int newDamage = Math.max(0, currentDamage - (repairAmount * itemsUsed));
                    targetDamageMeta.setDamage(newDamage);
                    result.setItemMeta(targetDamageMeta);
                }
            }
        }

        // Объединение зачарований для книг
        if (result.getType() == Material.ENCHANTED_BOOK && right.getType() == Material.ENCHANTED_BOOK) {
            if (result.getItemMeta() instanceof EnchantmentStorageMeta && right.getItemMeta() instanceof EnchantmentStorageMeta) {
                EnchantmentStorageMeta resMeta = (EnchantmentStorageMeta) result.getItemMeta();
                EnchantmentStorageMeta rightMeta = (EnchantmentStorageMeta) right.getItemMeta();
                if (resMeta != null && rightMeta != null) {
                    for (Map.Entry<Enchantment, Integer> entry : rightMeta.getStoredEnchants().entrySet()) {
                        Enchantment ench = entry.getKey();
                        int level = entry.getValue();
                        if (resMeta.hasStoredEnchant(ench)) {
                            int currentLevel = resMeta.getStoredEnchantLevel(ench);
                            int finalEnchLevel = (currentLevel == level) ? currentLevel + 1 : Math.max(currentLevel, level);
                            resMeta.addStoredEnchant(ench, Math.min(ench.getMaxLevel(), finalEnchLevel), true);
                        } else {
                            resMeta.addStoredEnchant(ench, level, true);
                        }
                    }
                    result.setItemMeta(resMeta);
                }
            }
        } else {
            // Объединение зачарований для обычных предметов
            ItemMeta resMeta = result.getItemMeta();
            if (resMeta != null) {
                Map<Enchantment, Integer> enchantsToAdd = new HashMap<>();
                if (right.getType() == Material.ENCHANTED_BOOK) {
                    if (right.getItemMeta() instanceof EnchantmentStorageMeta) {
                        EnchantmentStorageMeta rightMeta = (EnchantmentStorageMeta) right.getItemMeta();
                        enchantsToAdd.putAll(rightMeta.getStoredEnchants());
                    }
                } else {
                    enchantsToAdd.putAll(right.getEnchantments());
                }

                for (Map.Entry<Enchantment, Integer> entry : enchantsToAdd.entrySet()) {
                    Enchantment ench = entry.getKey();
                    int level = entry.getValue();
                    if (resMeta.hasEnchant(ench)) {
                        int currentLevel = resMeta.getEnchantLevel(ench);
                        int finalEnchLevel = (currentLevel == level) ? currentLevel + 1 : Math.max(currentLevel, level);
                        resMeta.addEnchant(ench, Math.min(ench.getMaxLevel(), finalEnchLevel), true);
                    } else {
                        resMeta.addEnchant(ench, level, true);
                    }
                }
                result.setItemMeta(resMeta);
            }
        }

        // Добавляем чар "Адаптация" к результату
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return;

        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.removeIf(l -> l.contains("Адаптация"));

        if (finalLvl > 0) {
            String strLvl = finalLvl == 1 ? "I" : finalLvl == 2 ? "II" : "III";
            lore.add(ChatColor.LIGHT_PURPLE + "Адаптация " + strLvl);
            meta.setLore(lore);
        }

        meta.setEnchantmentGlintOverride(true);
        if (result.getType() == Material.ENCHANTED_BOOK) {
            meta.setDisplayName(ChatColor.AQUA + "Чародейская книга");
        }

        result.setItemMeta(meta);
        event.setResult(result);
        try {
            anvil.setRepairCost(5);
        } catch (Exception ignored) {}
    }

    private int getLvlFromLore(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return 0;
        for (String line : item.getItemMeta().getLore()) {
            if (line.contains("Адаптация III")) return 3;
            if (line.contains("Адаптация II")) return 2;
            if (line.contains("Адаптация I")) return 1;
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        // Проверка кулдауна
        if (cooldownEndTimes.containsKey(uuid) && System.currentTimeMillis() < cooldownEndTimes.get(uuid)) {
            return;
        }

        // Считаем уровень и количество предметов с чаром
        int totalLvl = 0, pieceCount = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            int lvl = getLvlFromLore(armor);
            if (lvl > 0) {
                totalLvl += lvl;
                pieceCount++;
            }
        }
        if (pieceCount == 0) return;

        String type = getDamageType(event.getCause());
        if (type.equals("IGNORE")) return;

        long now = System.currentTimeMillis();
        boolean isSpam = (now - lastHitTime.getOrDefault(uuid, 0L) < 450);
        if (!isSpam) lastHitTime.put(uuid, now);

        // Если адаптация активна
        if (activeAdaptations.containsKey(uuid)) {
            if (type.equals(activeAdaptations.get(uuid))) {
                // Совпадает - защита
                spawnAdaptationParticles(player, type);

                if (superAdaptations.getOrDefault(uuid, false)) {
                    double perPieceSuper = getConfig().getDouble("settings.super-protection-per-piece", 0.125);
                    event.setDamage(event.getDamage() * (1.0 - (pieceCount * perPieceSuper)));

                    if (!isSpam && activeTimesLeft.containsKey(uuid)) {
                        double currentLeft = activeTimesLeft.get(uuid);
                        double maxTime = activeMaxTimes.getOrDefault(uuid, 4.0);
                        double bonus = getConfig().getDouble("settings.hit-bonus-super", 0.4);
                        double newLeft = Math.min(maxTime, currentLeft + bonus);
                        activeTimesLeft.put(uuid, newLeft);
                    }
                } else {
                    double perPieceNormal = getConfig().getDouble("settings.normal-protection-per-piece", 0.075);
                    event.setDamage(event.getDamage() * (1.0 - (pieceCount * perPieceNormal)));

                    if (!isSpam && activeTimesLeft.containsKey(uuid)) {
                        double currentLeft = activeTimesLeft.get(uuid);
                        double maxTime = activeMaxTimes.getOrDefault(uuid, 10.0);
                        double bonus = getConfig().getDouble("settings.hit-bonus-normal", 0.2);
                        double newLeft = Math.min(maxTime, currentLeft + bonus);
                        activeTimesLeft.put(uuid, newLeft);
                    }

                    // Счетчик для супер-адаптации
                    if (!isSpam) {
                        superDamageCounters.putIfAbsent(uuid, new HashMap<>());
                        int sHits = superDamageCounters.get(uuid).getOrDefault(type, 0) + 1;
                        superDamageCounters.get(uuid).put(type, sHits);

                        int requiredSuperHits = getConfig().getInt("settings.required-super-hits", 8);
                        if (sHits >= requiredSuperHits) {
                            activateSuper(player, type);
                        }
                    }
                }
            } else {
                // НЕ совпадает - штраф (фича!)
                double penaltyPerPiece = getConfig().getDouble("settings.penalty-per-piece", 0.10);
                event.setDamage(event.getDamage() * (1.0 + (pieceCount * penaltyPerPiece)));

                // Показываем, что адаптация не работает
                player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 5, 0.2, 0.3, 0.2, 0.05);
            }
            return;
        }

        // Если адаптация не активна - накапливаем счетчик
        if (isSpam) return;

        double avg = (double) totalLvl / pieceCount;
        int req = (avg > 2.0) ? getConfig().getInt("settings.required-hits.lvl3", 6)
                : (avg > 1.0) ? getConfig().getInt("settings.required-hits.lvl2", 8)
                : getConfig().getInt("settings.required-hits.lvl1", 10);

        damageCounters.putIfAbsent(uuid, new HashMap<>());
        int hits = damageCounters.get(uuid).getOrDefault(type, 0) + 1;
        damageCounters.get(uuid).put(type, hits);

        if (hits >= req) {
            activateNormal(player, type);
        }
    }

    private void spawnAdaptationParticles(Player player, String type) {
        Particle.DustOptions dustOptions;
        switch (type) {
            case "MELEE":
                dustOptions = meleeDust;
                break;
            case "RANGED":
                dustOptions = rangedDust;
                break;
            case "MAGIC":
                dustOptions = magicDust;
                break;
            default:
                return;
        }
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.0, dustOptions);
    }

    private void activateNormal(Player player, String type) {
        UUID uuid = player.getUniqueId();
        // Очищаем старые данные
        cleanupPlayerData(uuid, true);

        activeAdaptations.put(uuid, type);
        damageCounters.remove(uuid);
        superDamageCounters.remove(uuid);

        playBell(player, 0.9f, 20L);

        String typeStr = type.equals("MELEE") ? "БЛИЖ. УРОН!" : type.equals("RANGED") ? "СНАРЯДАМ!" : "МАГИИ!";
        ChatColor color = type.equals("MELEE") ? ChatColor.RED : type.equals("RANGED") ? ChatColor.GREEN : ChatColor.LIGHT_PURPLE;
        String line = ChatColor.WHITE + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: " + color + ChatColor.BOLD + typeStr;

        BarColor barColor = type.equals("MELEE") ? BarColor.RED : type.equals("RANGED") ? BarColor.GREEN : BarColor.PURPLE;
        int duration = getConfig().getInt("settings.duration-normal", 10);
        createBossBarTimer(player, line, barColor, duration, false);
    }

    private void activateSuper(Player player, String type) {
        UUID uuid = player.getUniqueId();
        // Очищаем старые данные
        cleanupPlayerData(uuid, true);

        superAdaptations.put(uuid, true);
        superDamageCounters.remove(uuid);

        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 1, false, false, true));
        playBell(player, 1.4f, 15L);

        String prefix = ChatColor.WHITE + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "ПОВЫШ. АДАПТАЦИЯ К: ";
        String typeStr = type.equals("MELEE") ? "БЛИЖ. УРОН!" : type.equals("RANGED") ? "СНАРЯДАМ!" : "МАГИИ!";
        ChatColor color = type.equals("MELEE") ? ChatColor.DARK_RED : type.equals("RANGED") ? ChatColor.DARK_GREEN : ChatColor.DARK_PURPLE;
        String line = prefix + color + ChatColor.UNDERLINE + "" + ChatColor.BOLD + typeStr;

        BarColor barColor = type.equals("MELEE") ? BarColor.RED : type.equals("RANGED") ? BarColor.GREEN : BarColor.PURPLE;
        int duration = getConfig().getInt("settings.duration-super", 4);
        createBossBarTimer(player, line, barColor, duration, true);
    }

    private void createBossBarTimer(Player player, String msg, BarColor color, int sec, boolean wasSuper) {
        UUID uuid = player.getUniqueId();
        BossBar bossBar = Bukkit.createBossBar(msg, color, BarStyle.SOLID);
        bossBar.addPlayer(player);
        activeBossBars.put(uuid, bossBar);

        activeTimesLeft.put(uuid, (double) sec);
        activeMaxTimes.put(uuid, (double) sec);

        // ОПТИМИЗАЦИЯ: таймер теперь обновляется каждые 2 тика вместо 1
        activeTimers.put(uuid, new BukkitRunnable() {
            boolean isCooldownMode = false;
            double maxTime = sec;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                Double timeLeft = activeTimesLeft.get(uuid);

                if (p == null || !p.isOnline() || timeLeft == null) {
                    cleanupPlayerData(uuid, false);
                    cancel();
                    return;
                }

                if (!isCooldownMode && timeLeft <= 0) {
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
                    p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.05);

                    activeAdaptations.remove(uuid);
                    superAdaptations.remove(uuid);
                    superDamageCounters.remove(uuid); // Сбрасываем счетчик супер-адаптации

                    int cdSec = wasSuper ? getConfig().getInt("settings.cooldown-super", 4)
                            : getConfig().getInt("settings.cooldown-normal", 2);
                    cooldownEndTimes.put(uuid, System.currentTimeMillis() + (cdSec * 1000L));

                    isCooldownMode = true;
                    maxTime = cdSec;
                    timeLeft = 0.0;

                    bossBar.setTitle(ChatColor.GRAY + "" + ChatColor.BOLD + "ПЕРЕЗАРЯДКА АДАПТАЦИИ");
                    bossBar.setColor(BarColor.WHITE);
                }

                if (isCooldownMode) {
                    if (timeLeft >= maxTime) {
                        cleanupPlayerData(uuid, false);
                        cancel();
                        return;
                    }
                    bossBar.setProgress(timeLeft / maxTime);
                    activeTimesLeft.put(uuid, timeLeft + 0.05);
                } else {
                    bossBar.setProgress(timeLeft / maxTime);
                    activeTimesLeft.put(uuid, timeLeft - 0.05);
                }
            }
        }.runTaskTimer(this, 0L, 2L)); // <-- период 2 тика вместо 1
    }

    private void cleanupPlayerData(UUID uuid, boolean keepCooldown) {
        activeTimesLeft.remove(uuid);
        activeMaxTimes.remove(uuid);
        if (activeTimers.containsKey(uuid)) {
            activeTimers.get(uuid).cancel();
            activeTimers.remove(uuid);
        }
        activeAdaptations.remove(uuid);
        superAdaptations.remove(uuid);
        superDamageCounters.remove(uuid);

        if (activeBossBars.containsKey(uuid)) {
            activeBossBars.get(uuid).removeAll();
            activeBossBars.remove(uuid);
        }

        if (!keepCooldown) {
            cooldownEndTimes.remove(uuid);
        }
    }

    private void cleanupAll() {
        activeTimers.values().forEach(BukkitTask::cancel);
        activeBossBars.values().forEach(BossBar::removeAll);

        damageCounters.clear();
        superDamageCounters.clear();
        activeTimers.clear();
        activeAdaptations.clear();
        superAdaptations.clear();
        lastHitTime.clear();
        activeBossBars.clear();
        activeTimesLeft.clear();
        activeMaxTimes.clear();
        cooldownEndTimes.clear();
    }

    private void playBell(Player player, float pitch, long per) {
        if (player == null) return;
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch);
        UUID uuid = player.getUniqueId();

        new BukkitRunnable() {
            int count = 1;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline() || count >= 3) {
                    cancel();
                    return;
                }
                p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch);
                count++;
            }
        }.runTaskTimer(this, per, per);
    }

    private String getDamageType(DamageCause c) {
        if (c == DamageCause.PROJECTILE || c == DamageCause.BLOCK_EXPLOSION || c == DamageCause.ENTITY_EXPLOSION) {
            return "RANGED";
        }
        if (c == DamageCause.MAGIC || c == DamageCause.POISON || c == DamageCause.WITHER || c == DamageCause.DRAGON_BREATH) {
            return "MAGIC";
        }
        if (c.name().equals("SONIC_BOOM")) {
            return "MAGIC";
        }
        if (c == DamageCause.VOID || c == DamageCause.STARVATION || c.name().equals("CUSTOM")) {
            return "IGNORE";
        }
        return "MELEE";
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        damageCounters.remove(id);
        superDamageCounters.remove(id);
        lastHitTime.remove(id);
        cleanupPlayerData(id, false);
    }
}
