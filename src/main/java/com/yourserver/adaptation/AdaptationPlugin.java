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

    private final Particle.DustOptions meleeDust = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.2f);
    private final Particle.DustOptions rangedDust = new Particle.DustOptions(Color.fromRGB(0, 255, 0), 1.2f);
    private final Particle.DustOptions magicDust = new Particle.DustOptions(Color.fromRGB(200, 0, 255), 1.2f);

    private final String ADAPTATION_LORE = "§dАдаптация";
    private final String D20_LORE = "§dБросок I";

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
            meta.setDisplayName("§bЧародейская книга");
            String strLvl = lvl == 1 ? "I" : lvl == 2 ? "II" : "III";
            List<String> lore = new ArrayList<>();
            lore.add("§dАдаптация " + strLvl);
            meta.setLore(lore);
            meta.setEnchantmentGlintOverride(true);
            book.setItemMeta(meta);
        }

        target.getInventory().addItem(book);
        sender.sendMessage(ChatColor.GREEN + "Книга Адаптация " + args[2] + " выдана игроку " + target.getName());
        return true;
    }

    public void breakAdaptation(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        if (!activeAdaptations.containsKey(uuid)) return;

        if (activeTimers.containsKey(uuid)) {
            activeTimers.get(uuid).cancel();
            activeTimers.remove(uuid);
        }

        if (activeBossBars.containsKey(uuid)) {
            activeBossBars.get(uuid).removeAll();
            activeBossBars.remove(uuid);
        }

        activeAdaptations.remove(uuid);
        superAdaptations.remove(uuid);
        superDamageCounters.remove(uuid);
        damageCounters.remove(uuid);

        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
        player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.05);

        player.sendMessage(ChatColor.RED + "Бафф чара \"Адаптация\" был разбит критическим ударом врага!");

        cooldownEndTimes.put(uuid, System.currentTimeMillis() + 4000L);
    }

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

    private boolean hasD20Lore(ItemStack item) {
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

    private int getAdaptationLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return 0;
        for (String line : item.getItemMeta().getLore()) {
            String cleanLine = ChatColor.stripColor(line).trim();
            if (cleanLine.contains("Адаптация III")) return 3;
            if (cleanLine.contains("Адаптация II")) return 2;
            if (cleanLine.contains("Адаптация I")) return 1;
        }
        return 0;
    }

    private void addAdaptationToItem(ItemStack item, int level) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(line -> ChatColor.stripColor(line).trim().startsWith("Адаптация"));

        String strLvl = level == 1 ? "I" : level == 2 ? "II" : "III";
        lore.add("§dАдаптация " + strLvl);
        meta.setLore(lore);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    private boolean isArmorItem(Material material) {
        String name = material.name();
        return name.contains("HELMET") || name.contains("CHESTPLATE") ||
               name.contains("LEGGINGS") || name.contains("BOOTS");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);

        if (left == null || right == null) return;

        boolean leftHasAdaptation = hasAdaptationLore(left);
        boolean rightHasAdaptation = hasAdaptationLore(right);
        boolean leftHasD20 = hasD20Lore(left);
        boolean rightHasD20 = hasD20Lore(right);

        if ((leftHasAdaptation && rightHasD20) || (leftHasD20 && rightHasAdaptation)) {
            event.setResult(null);
            return;
        }

        if (!leftHasAdaptation && !rightHasAdaptation) return;

        boolean isArmor = isArmorItem(left.getType());
        boolean isBook = left.getType() == Material.ENCHANTED_BOOK;

        if (!isArmor && !isBook) {
            event.setResult(null);
            return;
        }

        ItemStack result = event.getResult();
        if (result == null || result.getType() == Material.AIR) {
            result = left.clone();
        }

        if (right.getType() == Material.ENCHANTED_BOOK && rightHasAdaptation && isArmor) {
            int bookLevel = getAdaptationLevel(right);
            int currentLevel = getAdaptationLevel(left);

            if (currentLevel > 0) {
                if (currentLevel == bookLevel && currentLevel < 3) {
                    int newLevel = currentLevel + 1;
                    addAdaptationToItem(result, newLevel);
                    event.setResult(result);
                    try { inv.setRepairCost(5); } catch (Exception ignored) {}
                    return;
                } else {
                    event.setResult(null);
                    return;
                }
            } else {
                addAdaptationToItem(result, bookLevel);
                event.setResult(result);
                try { inv.setRepairCost(5); } catch (Exception ignored) {}
                return;
            }
        }

        if (left.getType() == Material.ENCHANTED_BOOK && right.getType() == Material.ENCHANTED_BOOK) {
            if (leftHasAdaptation && rightHasAdaptation) {
                int lvlLeft = getAdaptationLevel(left);
                int lvlRight = getAdaptationLevel(right);

                if (lvlLeft == lvlRight && lvlLeft < 3) {
                    int newLevel = lvlLeft + 1;
                    ItemStack newBook = left.clone();
                    addAdaptationToItem(newBook, newLevel);
                    event.setResult(newBook);
                    try { inv.setRepairCost(5); } catch (Exception ignored) {}
                    return;
                } else {
                    event.setResult(null);
                    return;
                }
            }
        }

        event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        // Проверяем, можно ли считать удары
        boolean canCountHits = true;

        if (superAdaptations.containsKey(uuid) && superAdaptations.get(uuid)) {
            canCountHits = false;
        }

        if (cooldownEndTimes.containsKey(uuid) && System.currentTimeMillis() < cooldownEndTimes.get(uuid)) {
            canCountHits = false;
        }

        if (activeAdaptations.containsKey(uuid)) {
            canCountHits = false;
        }

        // ===== ВСЕГДА применяем эффекты адаптации, если она активна =====
        if (activeAdaptations.containsKey(uuid)) {
            applyAdaptationEffects(player, event, uuid);
        }

        // Если нельзя считать удары — выходим (но эффекты уже применены)
        if (!canCountHits) {
            return;
        }

        // ===== СЧЕТ УДАРОВ ДЛЯ АКТИВАЦИИ АДАПТАЦИИ =====
        int totalLvl = 0, pieceCount = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            int lvl = getAdaptationLevel(armor);
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

    private void applyAdaptationEffects(Player player, EntityDamageEvent event, UUID uuid) {
        if (!activeAdaptations.containsKey(uuid)) return;

        String type = getDamageType(event.getCause());
        if (type.equals("IGNORE")) return;

        int pieceCount = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (getAdaptationLevel(armor) > 0) pieceCount++;
        }
        if (pieceCount == 0) return;

        if (type.equals(activeAdaptations.get(uuid))) {
            // Совпадает — защита + добавление времени
            spawnAdaptationParticles(player, type);

            if (superAdaptations.getOrDefault(uuid, false)) {
                // ===== СУПЕР-АДАПТАЦИЯ =====
                double perPieceSuper = getConfig().getDouble("settings.super-protection-per-piece", 0.125);
                event.setDamage(event.getDamage() * (1.0 - (pieceCount * perPieceSuper)));

                // ===== ДОБАВЛЯЕМ ВРЕМЯ =====
                if (activeTimesLeft.containsKey(uuid)) {
                    double currentLeft = activeTimesLeft.get(uuid);
                    double maxTime = activeMaxTimes.getOrDefault(uuid, 40.0);
                    double bonus = getConfig().getDouble("settings.hit-bonus-super", 0.4);
                    double newLeft = Math.min(maxTime, currentLeft + (bonus * 10.0));
                    activeTimesLeft.put(uuid, newLeft);
                }
            } else {
                // ===== ОБЫЧНАЯ АДАПТАЦИЯ =====
                double perPieceNormal = getConfig().getDouble("settings.normal-protection-per-piece", 0.075);
                event.setDamage(event.getDamage() * (1.0 - (pieceCount * perPieceNormal)));

                // ===== ДОБАВЛЯЕМ ВРЕМЯ =====
                if (activeTimesLeft.containsKey(uuid)) {
                    double currentLeft = activeTimesLeft.get(uuid);
                    double maxTime = activeMaxTimes.getOrDefault(uuid, 100.0);
                    double bonus = getConfig().getDouble("settings.hit-bonus-normal", 0.2);
                    double newLeft = Math.min(maxTime, currentLeft + (bonus * 10.0));
                    activeTimesLeft.put(uuid, newLeft);
                }

                // ===== СЧЕТЧИК ДЛЯ СУПЕР-АДАПТАЦИИ =====
                superDamageCounters.putIfAbsent(uuid, new HashMap<>());
                int sHits = superDamageCounters.get(uuid).getOrDefault(type, 0) + 1;
                superDamageCounters.get(uuid).put(type, sHits);

                int requiredSuperHits = getConfig().getInt("settings.required-super-hits", 8);
                if (sHits >= requiredSuperHits) {
                    activateSuper(player, type);
                }
            }
        } else {
            // ===== ШТРАФ =====
            double penaltyPerPiece;
            if (superAdaptations.getOrDefault(uuid, false)) {
                penaltyPerPiece = getConfig().getDouble("settings.super-penalty-per-piece", 0.125);
            } else {
                penaltyPerPiece = getConfig().getDouble("settings.penalty-per-piece", 0.10);
            }
            event.setDamage(event.getDamage() * (1.0 + (pieceCount * penaltyPerPiece)));
            player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 5, 0.2, 0.3, 0.2, 0.05);
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

        double totalTicks = sec * 10.0;
        activeTimesLeft.put(uuid, totalTicks);
        activeMaxTimes.put(uuid, totalTicks);

        activeTimers.put(uuid, new BukkitRunnable() {
            boolean isCooldownMode = false;
            double maxTime = totalTicks;

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
                    superDamageCounters.remove(uuid);
                    damageCounters.remove(uuid);

                    int cdSec = wasSuper ? getConfig().getInt("settings.cooldown-super", 4)
                            : getConfig().getInt("settings.cooldown-normal", 2);
                    cooldownEndTimes.put(uuid, System.currentTimeMillis() + (cdSec * 1000L));

                    isCooldownMode = true;
                    maxTime = cdSec * 10.0;
                    timeLeft = 0.0;

                    bossBar.setTitle(ChatColor.GRAY + "" + ChatColor.BOLD + "ПЕРЕЗАРЯДКА АДАПТАЦИИ");
                    bossBar.setColor(BarColor.WHITE);
                }

                if (isCooldownMode) {
                    if (timeLeft >= maxTime) {
                        damageCounters.remove(uuid);
                        superDamageCounters.remove(uuid);
                        cleanupPlayerData(uuid, false);
                        cancel();
                        return;
                    }
                    bossBar.setProgress(timeLeft / maxTime);
                    activeTimesLeft.put(uuid, timeLeft + 1.0);
                } else {
                    bossBar.setProgress(timeLeft / maxTime);
                    activeTimesLeft.put(uuid, timeLeft - 1.0);
                }
            }
        }.runTaskTimer(this, 0L, 2L));
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
