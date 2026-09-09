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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
    private RollbackListener rollbackListener;
    private FlaskListener flaskListener;

    private final Particle.DustOptions meleeDust =
            new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.2f);

    private final Particle.DustOptions rangedDust =
            new Particle.DustOptions(Color.fromRGB(0, 255, 0), 1.2f);

    private final Particle.DustOptions magicDust =
            new Particle.DustOptions(Color.fromRGB(200, 0, 255), 1.2f);

@Override
public void onEnable() {
    saveDefaultConfig();

    MessageUtils.init(this);

    getServer().getPluginManager().registerEvents(
            this,
            this
    );

    diceRollListener =
            new DiceRollListener(this);

    getServer().getPluginManager().registerEvents(
            diceRollListener,
            this
    );

    flaskListener =
            new FlaskListener(this);

    getServer().getPluginManager().registerEvents(
            flaskListener,
            this
    );

    rollbackListener =
            new RollbackListener(this);

    getServer().getPluginManager().registerEvents(
            rollbackListener,
            this
    );

    CopperBlockListener copperBlockListener =
            new CopperBlockListener(this);

    getServer().getPluginManager().registerEvents(
            copperBlockListener,
            this
    );

    F8Command f8Command =
            new F8Command(
                    this,
                    diceRollListener,
                    flaskListener,
                    rollbackListener,
                    copperBlockListener
            );

    getServer().getPluginManager().registerEvents(
            f8Command,
            this
    );

    if (getCommand("f8") != null) {
        getCommand("f8").setExecutor(
                f8Command
        );
    }

    getLogger().info(
            "AdaptationPlugin успешно запущен."
    );
}
    
    @Override
    public void onDisable() {
        cleanupAll();

        if (diceRollListener != null) {
            diceRollListener.disable();
        }

        if (rollbackListener != null) {
            rollbackListener.disable();
        }

        if (flaskListener != null) {
            flaskListener.disable();
        }
    }

    public void breakAdaptation(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();

        if (!activeAdaptations.containsKey(uuid)) {
            return;
        }

        cancelTimer(uuid);
        removeBossBar(uuid);

        activeAdaptations.remove(uuid);
        superAdaptations.remove(uuid);
        superDamageCounters.remove(uuid);
        damageCounters.remove(uuid);
        activeTimesLeft.remove(uuid);
        activeMaxTimes.remove(uuid);

        int cooldown = getConfig().getInt(
                "settings.cooldown-super",
                4
        );

        cooldownEndTimes.put(
                uuid,
                System.currentTimeMillis() + cooldown * 1000L
        );

        if (MessageUtils.sounds()) {
            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.BLOCK_GLASS_BREAK,
                    1.0f,
                    0.8f
            );
        }

        if (MessageUtils.particles()) {
            player.getWorld().spawnParticle(
                    Particle.SMOKE,
                    player.getLocation().add(0, 1, 0),
                    10,
                    0.2,
                    0.3,
                    0.2,
                    0.05
            );
        }

        MessageUtils.send(
                player,
                "adaptation.broken"
        );
    }

    private boolean hasAdaptationLore(ItemStack item) {
        if (item == null ||
                item.getType() == Material.AIR ||
                !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null || !meta.hasLore()) {
            return false;
        }

        for (String line : meta.getLore()) {
            String clean = ChatColor.stripColor(line).trim();

            if (clean.startsWith("Адаптация")) {
                return true;
            }
        }

        return false;
    }

    private boolean hasD20Lore(ItemStack item) {
        if (item == null ||
                item.getType() == Material.AIR ||
                !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null || !meta.hasLore()) {
            return false;
        }

        for (String line : meta.getLore()) {
            String clean = ChatColor.stripColor(line).trim();

            if (clean.equals("Бросок I")) {
                return true;
            }
        }

        return false;
    }

    private int getAdaptationLevel(ItemStack item) {
        if (item == null ||
                !item.hasItemMeta() ||
                !item.getItemMeta().hasLore()) {
            return 0;
        }

        for (String line : item.getItemMeta().getLore()) {
            String clean = ChatColor.stripColor(line).trim();

            if (clean.contains("Адаптация III")) {
                return 3;
            }

            if (clean.contains("Адаптация II")) {
                return 2;
            }

            if (clean.contains("Адаптация I")) {
                return 1;
            }
        }

        return 0;
    }

    private void addAdaptationToItem(ItemStack item, int level) {
        if (item == null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return;
        }

        List<String> lore = meta.hasLore()
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();

        lore.removeIf(line ->
                ChatColor.stripColor(line)
                        .trim()
                        .startsWith("Адаптация")
        );

        String strLvl =
                level == 1 ? "I" :
                level == 2 ? "II" : "III";

        lore.add("§dАдаптация " + strLvl);

        meta.setLore(lore);
        meta.setEnchantmentGlintOverride(true);

        item.setItemMeta(meta);
    }

    private boolean isArmorItem(Material material) {
        String name = material.name();

        return name.contains("HELMET") ||
               name.contains("CHESTPLATE") ||
               name.contains("LEGGINGS") ||
               name.contains("BOOTS");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {

        AnvilInventory inv = event.getInventory();

        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);

        if (left == null || right == null) {
            return;
        }

        boolean leftAdaptation = hasAdaptationLore(left);
        boolean rightAdaptation = hasAdaptationLore(right);

        boolean leftD20 = hasD20Lore(left);
        boolean rightD20 = hasD20Lore(right);

        if ((leftAdaptation && rightD20) ||
                (leftD20 && rightAdaptation)) {

            event.setResult(null);
            return;
        }

        if (!leftAdaptation && !rightAdaptation) {
            return;
        }

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

        if (right.getType() == Material.ENCHANTED_BOOK &&
                rightAdaptation &&
                isArmor) {

            int bookLevel = getAdaptationLevel(right);
            int currentLevel = getAdaptationLevel(left);

            if (currentLevel > 0) {

                if (currentLevel == bookLevel &&
                        currentLevel < 3) {

                    addAdaptationToItem(
                            result,
                            currentLevel + 1
                    );

                    event.setResult(result);

                    try {
                        inv.setRepairCost(5);
                    } catch (Exception ignored) {
                    }

                    return;
                }

                event.setResult(null);
                return;

            } else {

                addAdaptationToItem(result, bookLevel);

                event.setResult(result);

                try {
                    inv.setRepairCost(5);
                } catch (Exception ignored) {
                }

                return;
            }
        }

        if (left.getType() == Material.ENCHANTED_BOOK &&
                right.getType() == Material.ENCHANTED_BOOK &&
                leftAdaptation &&
                rightAdaptation) {

            int leftLevel = getAdaptationLevel(left);
            int rightLevel = getAdaptationLevel(right);

            if (leftLevel == rightLevel &&
                    leftLevel < 3) {

                ItemStack newBook = left.clone();

                addAdaptationToItem(
                        newBook,
                        leftLevel + 1
                );

                event.setResult(newBook);

                try {
                    inv.setRepairCost(5);
                } catch (Exception ignored) {
                }

                return;
            }

            event.setResult(null);
            return;
        }

        event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageEvent event) {

        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        /*
         * Если адаптация уже активна,
         * сначала применяем её защиту.
         */
        if (activeAdaptations.containsKey(uuid)) {
            applyAdaptationEffects(
                    player,
                    event,
                    uuid
            );

            return;
        }

        /*
         * Во время cooldown новые адаптации
         * не набираются.
         */
        if (cooldownEndTimes.containsKey(uuid)) {

            if (System.currentTimeMillis() <
                    cooldownEndTimes.get(uuid)) {

                return;

            } else {

                cooldownEndTimes.remove(uuid);
            }
        }

        int totalLevel = 0;
        int pieceCount = 0;

        for (ItemStack armor :
                player.getInventory().getArmorContents()) {

            int level = getAdaptationLevel(armor);

            if (level > 0) {
                totalLevel += level;
                pieceCount++;
            }
        }

        if (pieceCount == 0) {
            return;
        }

        String type = getDamageType(event.getCause());

        if (type.equals("IGNORE")) {
            return;
        }

        long now = System.currentTimeMillis();

        boolean spam =
                now - lastHitTime.getOrDefault(uuid, 0L) < 450L;

        if (spam) {
            return;
        }

        lastHitTime.put(uuid, now);

        double average =
                (double) totalLevel / pieceCount;

        int requiredHits;

        if (average > 2.0) {

            requiredHits =
                    getConfig().getInt(
                            "settings.required-hits.lvl3",
                            6
                    );

        } else if (average > 1.0) {

            requiredHits =
                    getConfig().getInt(
                            "settings.required-hits.lvl2",
                            8
                    );

        } else {

            requiredHits =
                    getConfig().getInt(
                            "settings.required-hits.lvl1",
                            10
                    );
        }

        damageCounters.putIfAbsent(
                uuid,
                new HashMap<>()
        );

        Map<String, Integer> counters =
                damageCounters.get(uuid);

        int hits =
                counters.getOrDefault(type, 0) + 1;

        counters.put(type, hits);

        if (hits >= requiredHits) {
            activateNormal(player, type);
        }
    }

    private void applyAdaptationEffects(
            Player player,
            EntityDamageEvent event,
            UUID uuid
    ) {

        String activeType =
                activeAdaptations.get(uuid);

        if (activeType == null) {
            return;
        }

        String damageType =
                getDamageType(event.getCause());

        if (damageType.equals("IGNORE")) {
            return;
        }

        int pieceCount = 0;

        for (ItemStack armor :
                player.getInventory().getArmorContents()) {

            if (getAdaptationLevel(armor) > 0) {
                pieceCount++;
            }
        }

        if (pieceCount == 0) {
            return;
        }

        if (damageType.equals(activeType)) {

            spawnAdaptationParticles(
                    player,
                    damageType
            );

            if (superAdaptations.getOrDefault(
                    uuid,
                    false
            )) {

                double protection =
                        getConfig().getDouble(
                                "settings.super-protection-per-piece",
                                0.125
                        );

                event.setDamage(
                        event.getDamage() *
                        (1.0 - pieceCount * protection)
                );

                /*
                 * ПОВЫШЕННАЯ АДАПТАЦИЯ:
                 *
                 * каждый подходящий удар
                 * добавляет +0.4 секунды.
                 *
                 * Таймер хранится в десятых долях
                 * секунды, поэтому:
                 *
                 * 0.4 * 10 = 4.
                 */
                if (activeTimesLeft.containsKey(uuid)) {

                    double current =
                            activeTimesLeft.get(uuid);

                    double max =
                            activeMaxTimes.getOrDefault(
                                    uuid,
                                    40.0
                            );

                    double bonus =
                            getConfig().getDouble(
                                    "settings.hit-bonus-super",
                                    0.4
                            );

                    double newTime =
                            Math.min(
                                    max,
                                    current + bonus * 10.0
                            );

                    activeTimesLeft.put(
                            uuid,
                            newTime
                    );
                }

            } else {

                double protection =
                        getConfig().getDouble(
                                "settings.normal-protection-per-piece",
                                0.075
                        );

                event.setDamage(
                        event.getDamage() *
                        (1.0 - pieceCount * protection)
                );

                /*
                 * Обычная адаптация:
                 * +0.2 секунды за подходящий удар.
                 */
                if (activeTimesLeft.containsKey(uuid)) {

                    double current =
                            activeTimesLeft.get(uuid);

                    double max =
                            activeMaxTimes.getOrDefault(
                                    uuid,
                                    100.0
                            );

                    double bonus =
                            getConfig().getDouble(
                                    "settings.hit-bonus-normal",
                                    0.2
                            );

                    double newTime =
                            Math.min(
                                    max,
                                    current + bonus * 10.0
                            );

                    activeTimesLeft.put(
                            uuid,
                            newTime
                    );
                }

                /*
                 * Набор ударов для повышенной адаптации.
                 */
                superDamageCounters.putIfAbsent(
                        uuid,
                        new HashMap<>()
                );

                Map<String, Integer> counters =
                        superDamageCounters.get(uuid);

                int hits =
                        counters.getOrDefault(
                                damageType,
                                0
                        ) + 1;

                counters.put(
                        damageType,
                        hits
                );

                int required =
                        getConfig().getInt(
                                "settings.required-super-hits",
                                8
                        );

                if (hits >= required) {
                    activateSuper(
                            player,
                            damageType
                    );
                }
            }

        } else {

            /*
             * Неподходящий тип урона:
             * увеличенный урон.
             */
            double penalty;

            if (superAdaptations.getOrDefault(
                    uuid,
                    false
            )) {

                penalty =
                        getConfig().getDouble(
                                "settings.super-penalty-per-piece",
                                0.125
                        );

            } else {

                penalty =
                        getConfig().getDouble(
                                "settings.penalty-per-piece",
                                0.10
                        );
            }

            event.setDamage(
                    event.getDamage() *
                    (1.0 + pieceCount * penalty)
            );

            if (MessageUtils.particles()) {
                player.getWorld().spawnParticle(
                        Particle.SMOKE,
                        player.getLocation().add(0, 1, 0),
                        5,
                        0.2,
                        0.3,
                        0.2,
                        0.05
                );
            }
        }
    }

    private void spawnAdaptationParticles(
            Player player,
            String type
    ) {

        if (!MessageUtils.particles()) {
            return;
        }

        Particle.DustOptions dust;

        switch (type) {

            case "MELEE":
                dust = meleeDust;
                break;

            case "RANGED":
                dust = rangedDust;
                break;

            case "MAGIC":
                dust = magicDust;
                break;

            default:
                return;
        }

        player.getWorld().spawnParticle(
                Particle.DUST,
                player.getLocation().add(0, 1, 0),
                6,
                0.3,
                0.4,
                0.3,
                0.0,
                dust
        );
    }

    private void activateNormal(
            Player player,
            String type
    ) {

        UUID uuid = player.getUniqueId();

        cleanupPlayerData(
                uuid,
                true
        );

        activeAdaptations.put(
                uuid,
                type
        );

        superAdaptations.remove(uuid);

        damageCounters.remove(uuid);
        superDamageCounters.remove(uuid);

        playBell(
                player,
                0.9f,
                20L
        );

        String title =
                buildAdaptationTitle(
                        type,
                        false
                );

        BarColor barColor =
                type.equals("MELEE")
                        ? BarColor.RED
                        : type.equals("RANGED")
                        ? BarColor.GREEN
                        : BarColor.PURPLE;

        int duration =
                getConfig().getInt(
                        "settings.duration-normal",
                        10
                );

        createBossBarTimer(
                player,
                title,
                barColor,
                duration,
                false
        );
    }

    private void activateSuper(
            Player player,
            String type
    ) {

        UUID uuid = player.getUniqueId();

        cleanupPlayerData(
                uuid,
                true
        );

        /*
         * КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ:
         *
         * раньше здесь сохранялся только boolean
         * superAdaptations, но НЕ type.
         *
         * Поэтому applyAdaptationEffects()
         * не знала, к какому типу урона
         * относится повышенная адаптация.
         */
        activeAdaptations.put(
                uuid,
                type
        );

        superAdaptations.put(
                uuid,
                true
        );

        damageCounters.remove(uuid);
        superDamageCounters.remove(uuid);

        player.addPotionEffect(
                new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.ABSORPTION,
                        160,
                        1,
                        false,
                        false,
                        true
                )
        );

        playBell(
                player,
                1.4f,
                15L
        );

        String title =
                buildAdaptationTitle(
                        type,
                        true
                );

        BarColor barColor =
                type.equals("MELEE")
                        ? BarColor.RED
                        : type.equals("RANGED")
                        ? BarColor.GREEN
                        : BarColor.PURPLE;

        int duration =
                getConfig().getInt(
                        "settings.duration-super",
                        4
                );

        createBossBarTimer(
                player,
                title,
                barColor,
                duration,
                true
        );
    }

    /* ===================== Визуал (только оформление) ===================== */

    /**
     * Название типа урона из gui.labels (например, "БЛИЖ. УРОН!").
     */
    private String typeLabel(String type) {
        return getConfig().getString(
                "gui.labels." + type.toLowerCase(),
                type.equals("MELEE")
                        ? "БЛИЖ. УРОН!"
                        : type.equals("RANGED")
                        ? "СНАРЯДАМ!"
                        : "МАГИИ!"
        );
    }

    /**
     * HEX/legacy-цвет типа урона для заголовка босс-бара (gui.labels.*).
     */
    private String typeColor(String type, boolean superMode) {
        String path = "gui.labels."
                + type.toLowerCase()
                + (superMode ? "-super-color" : "-color");
        return getConfig().getString(
                path,
                superMode
                        ? type.equals("MELEE")
                        ? "&#AA0000"
                        : type.equals("RANGED")
                        ? "&#00AA00"
                        : "&#AA00AA"
                        : type.equals("MELEE")
                        ? "&#FF5555"
                        : type.equals("RANGED")
                        ? "&#55FF55"
                        : "&#FF55FF"
        );
    }

    /**
     * Готовый legacy-заголовок босс-бара адаптации (gui.bossbar.active-*).
     * Метод используется только для отображения: механика таймера не меняется.
     */
    private String buildAdaptationTitle(String type, boolean superMode) {
        String template = getConfig().getString(
                superMode
                        ? "gui.bossbar.active-super"
                        : "gui.bossbar.active-normal",
                superMode
                        ? "&6&l&nПОВЫШ. АДАПТАЦИЯ К: {color}&l&n{label}"
                        : "&f&lАДАПТАЦИЯ К: {color}&l{label}"
        );

        return MessageUtils.legacy(
                template,
                "{color}", typeColor(type, superMode),
                "{label}", typeLabel(type)
        );
    }

    private void createBossBarTimer(
            Player player,
            String message,
            BarColor color,
            int seconds,
            boolean superMode
    ) {

        UUID uuid = player.getUniqueId();

        BossBar bossBar =
                Bukkit.createBossBar(
                        message,
                        color,
                        BarStyle.SOLID
                );

        /*
         * effects.bossbar=false: бар создаётся, но не показывается игроку.
         * Таймер и перезарядки продолжают работать как раньше.
         */
        if (MessageUtils.bossBars()) {
            bossBar.addPlayer(player);
        }

        activeBossBars.put(
                uuid,
                bossBar
        );

        double totalTime =
                seconds * 10.0;

        activeTimesLeft.put(
                uuid,
                totalTime
        );

        activeMaxTimes.put(
                uuid,
                totalTime
        );

        BukkitTask task =
                new BukkitRunnable() {

                    boolean cooldown = false;
                    double cooldownMax = totalTime;

                    @Override
                    public void run() {

                        Player p =
                                Bukkit.getPlayer(uuid);

                        Double time =
                                activeTimesLeft.get(uuid);

                        if (p == null ||
                                !p.isOnline() ||
                                time == null) {

                            cleanupPlayerData(
                                    uuid,
                                    false
                            );

                            cancel();
                            return;
                        }

                        if (!cooldown &&
                                time <= 0) {

                            activeAdaptations.remove(uuid);
                            superAdaptations.remove(uuid);
                            superDamageCounters.remove(uuid);
                            damageCounters.remove(uuid);

                            int cooldownSeconds =
                                    superMode
                                            ? getConfig().getInt(
                                                    "settings.cooldown-super",
                                                    4
                                            )
                                            : getConfig().getInt(
                                                    "settings.cooldown-normal",
                                                    4
                                            );

                            cooldownEndTimes.put(
                                    uuid,
                                    System.currentTimeMillis()
                                            + cooldownSeconds * 1000L
                            );

                            double cooldownTime =
                                    cooldownSeconds * 10.0;

                            activeTimesLeft.put(
                                    uuid,
                                    cooldownTime
                            );

                            cooldownMax =
                                    cooldownTime;

                            cooldown = true;

                            if (MessageUtils.sounds()) {
                                p.getWorld().playSound(
                                        p.getLocation(),
                                        Sound.BLOCK_GLASS_BREAK,
                                        1.0f,
                                        0.8f
                                );
                            }

                            if (MessageUtils.particles()) {
                                p.getWorld().spawnParticle(
                                        Particle.SMOKE,
                                        p.getLocation().add(0, 1, 0),
                                        10,
                                        0.2,
                                        0.3,
                                        0.2,
                                        0.05
                                );
                            }

                            MessageUtils.send(
                                    p,
                                    "adaptation.ended",
                                    "{seconds}", String.valueOf(cooldownSeconds)
                            );

                            bossBar.setTitle(
                                    MessageUtils.legacyKey(
                                            "gui.bossbar.cooldown",
                                            "&c&lПЕРЕЗАРЯДКА: &f{seconds}с",
                                            "{seconds}", String.valueOf(cooldownSeconds)
                                    )
                            );

                            bossBar.setColor(
                                    BarColor.RED
                            );

                            bossBar.setProgress(1.0);

                            return;
                        }

                        if (cooldown) {

                            time -= 1.0;

                            activeTimesLeft.put(
                                    uuid,
                                    time
                            );

                            double progress =
                                    Math.max(
                                            0.0,
                                            time / cooldownMax
                                    );

                            bossBar.setProgress(
                                    Math.min(
                                            1.0,
                                            progress
                                    )
                            );

                            int secondsLeft =
                                    (int) Math.ceil(
                                            time / 10.0
                                    );

                            bossBar.setTitle(
                                    MessageUtils.legacyKey(
                                            "gui.bossbar.cooldown",
                                            "&c&lПЕРЕЗАРЯДКА: &f{seconds}с",
                                            "{seconds}", String.valueOf(secondsLeft)
                                    )
                            );

                            if (time <= 0) {

                                activeTimesLeft.remove(uuid);
                                activeMaxTimes.remove(uuid);
                                cooldownEndTimes.remove(uuid);

                                bossBar.removeAll();
                                activeBossBars.remove(uuid);

                                MessageUtils.send(
                                        p,
                                        "adaptation.ready"
                                );

                                cancel();
                            }

                            return;
                        }

                        /*
                         * Таймер адаптации.
                         *
                         * 1 единица = 0.1 секунды.
                         *
                         * Задача запускается каждые 2 тика
                         * = каждые 0.1 секунды.
                         */
                        time -= 1.0;

                        activeTimesLeft.put(
                                uuid,
                                time
                        );

                        double max =
                                activeMaxTimes.getOrDefault(
                                        uuid,
                                        totalTime
                                );

                        double progress =
                                Math.max(
                                        0.0,
                                        time / max
                                );

                        bossBar.setProgress(
                                Math.min(
                                        1.0,
                                        progress
                                )
                        );

                        int secondsLeft =
                                (int) Math.ceil(
                                        Math.max(
                                                0.0,
                                                time
                                        ) / 10.0
                                );

                        bossBar.setTitle(
                                message +
                                " " +
                                MessageUtils.legacyKey(
                                        superMode
                                                ? "gui.bossbar.time-super"
                                                : "gui.bossbar.time-normal",
                                        superMode
                                                ? "&7[&6{seconds}с&7]"
                                                : "&7[&f{seconds}с&7]",
                                        "{seconds}", String.valueOf(secondsLeft)
                                )
                        );
                    }

                }.runTaskTimer(
                        this,
                        0L,
                        2L
                );

        activeTimers.put(
                uuid,
                task
        );
    }

    private void playBell(
            Player player,
            float pitch,
            long delay
    ) {

        if (!MessageUtils.sounds()) {
            return;
        }

        for (int i = 0; i < 3; i++) {

            final int index = i;

            Bukkit.getScheduler().runTaskLater(
                    this,
                    () -> {

                        if (player.isOnline()) {

                            player.getWorld().playSound(
                                    player.getLocation(),
                                    Sound.BLOCK_BELL_USE,
                                    1.0f,
                                    pitch + index * 0.1f
                            );
                        }

                    },
                    delay * (i + 1)
            );
        }
    }

    private void cancelTimer(UUID uuid) {

        BukkitTask task =
                activeTimers.remove(uuid);

        if (task != null) {
            task.cancel();
        }
    }

    private void removeBossBar(UUID uuid) {

        BossBar bar =
                activeBossBars.remove(uuid);

        if (bar != null) {
            bar.removeAll();
        }
    }

    private void cleanupPlayerData(
            UUID uuid,
            boolean keepCooldown
    ) {

        cancelTimer(uuid);
        removeBossBar(uuid);

        activeTimesLeft.remove(uuid);
        activeMaxTimes.remove(uuid);

        if (!keepCooldown) {
            cooldownEndTimes.remove(uuid);
        }
    }

    private void cleanupAll() {

        for (UUID uuid :
                new ArrayList<>(activeTimers.keySet())) {

            cancelTimer(uuid);
        }

        for (BossBar bar :
                activeBossBars.values()) {

            bar.removeAll();
        }

        activeBossBars.clear();

        activeTimesLeft.clear();
        activeMaxTimes.clear();

        activeAdaptations.clear();
        superAdaptations.clear();

        damageCounters.clear();
        superDamageCounters.clear();

        cooldownEndTimes.clear();
        lastHitTime.clear();
    }

    private String getDamageType(DamageCause cause) {

        switch (cause) {

            case ENTITY_ATTACK:
            case ENTITY_SWEEP_ATTACK:
                return "MELEE";

            case PROJECTILE:
                return "RANGED";

            case MAGIC:
            case POISON:
            case WITHER:
            case DRAGON_BREATH:
            case THORNS:
                return "MAGIC";

            default:
                return "IGNORE";
        }
    }

    public void disableDiceRollListener() {

        if (diceRollListener != null) {
            diceRollListener.disable();
        }
    }

    /*
     * Визуальный градиентный префикс игрока в табе (gui.playerlist).
     * Влияет ТОЛЬКО на список игроков (клавиша Tab); никак не меняет
     * механику и не трогает чат/наментеги (они управляются командами/тимами).
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!MessageUtils.bool("gui.playerlist.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        String format = getConfig().getString(
                "gui.playerlist.format",
                "<gradient:#9ec5fe:#e0aaff>F8</gradient> <white>»</white> {name}"
        );

        player.playerListName(MessageUtils.parse(
                format,
                "{name}", player.getName()
        ));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID uuid =
                event.getPlayer().getUniqueId();

        cleanupPlayerData(
                uuid,
                false
        );

        activeAdaptations.remove(uuid);
        superAdaptations.remove(uuid);

        damageCounters.remove(uuid);
        superDamageCounters.remove(uuid);

        lastHitTime.remove(uuid);
    }
}
