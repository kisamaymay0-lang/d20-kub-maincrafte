package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Клешня краба — улов болот (обычного и мангрового).
 *
 * В любой руке она добавляет три блока к дальности взаимодействия: атрибуты
 * {@code block_interaction_range} и {@code entity_interaction_range} клиент получает сам,
 * поэтому дотянуться действительно можно. А в правой руке она копает «чужими» инструментами
 * из инвентаря: блок ломается со скоростью лучшего подходящего инструмента, а сам инструмент
 * тратит вдвое больше прочности. Нет подходящего инструмента — клешня копает как обычная рука.
 *
 * Никаких сообщений в чат и над хотбаром механика не пишет: всё видно по самой игре.
 */
final class CrabClaw implements Listener {
    /** Как часто сверяем атрибут дальности с тем, что лежит в руках. */
    private static final int REACH_CHECK_TICKS = 5;

    /** Что игрок копает прямо сейчас: блок, слот инструмента и сколько тиков нужно. */
    private static final class Mining {
        Location block;
        int slot;      // -1 — инструмент в левой руке
        int ticks;
        int elapsed;
    }

    /** Из чего клешня копает: инструмент, его слот и посчитанное время. */
    private record Borrow(ItemStack tool, int slot, int ticks) { }

    private final JavaPlugin plugin;
    private final WinterItems items;
    private final NamespacedKey reachKey;
    private final Map<UUID, Mining> mining = new HashMap<>();
    private final BukkitTask reachTask;
    private BukkitTask miningTask;
    private boolean asking;   // сами подняли BlockBreakEvent: свой обработчик его не трогает

    CrabClaw(JavaPlugin plugin, WinterItems items) {
        this.plugin = plugin;
        this.items = items;
        this.reachKey = new NamespacedKey(plugin, "crab_claw_reach");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reachTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncReach, REACH_CHECK_TICKS, REACH_CHECK_TICKS);
    }

    void disable() {
        reachTask.cancel();
        if (miningTask != null) {
            miningTask.cancel();
            miningTask = null;
        }
        mining.clear();
        for (Player player : Bukkit.getOnlinePlayers()) setReach(player, false);
    }

    private double reachBonus() {
        return Math.clamp(plugin.getConfig().getDouble("fishing.crab-claw.reach-blocks", CrabClawRules.REACH_BONUS), 0.0, 32.0);
    }

    private int wearPerBlock() {
        return Math.clamp(plugin.getConfig().getInt("fishing.crab-claw.wear-per-block", CrabClawRules.WEAR_PER_BLOCK), 0, 64);
    }

    // ===== дальность взаимодействия =====

    private boolean holdsClaw(Player player) {
        return items.kind(player.getInventory().getItemInMainHand()) == WinterItems.Kind.CLAW
                || items.kind(player.getInventory().getItemInOffHand()) == WinterItems.Kind.CLAW;
    }

    private boolean clawInMainHand(Player player) {
        return items.kind(player.getInventory().getItemInMainHand()) == WinterItems.Kind.CLAW;
    }

    private void syncReach() {
        for (Player player : Bukkit.getOnlinePlayers()) setReach(player, holdsClaw(player));
    }

    private void setReach(Player player, boolean enabled) {
        apply(player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE), enabled);
        apply(player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE), enabled);
    }

    private void apply(AttributeInstance instance, boolean enabled) {
        if (instance == null) return;
        boolean present = instance.getModifiers().stream().anyMatch(modifier -> modifier.getKey().equals(reachKey));
        if (enabled && !present) {
            instance.addModifier(new AttributeModifier(reachKey, reachBonus(), AttributeModifier.Operation.ADD_NUMBER));
        } else if (!enabled && present) {
            instance.removeModifier(reachKey);
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) { setReach(event.getPlayer(), holdsClaw(event.getPlayer())); }

    @EventHandler
    public void respawn(PlayerRespawnEvent event) { setReach(event.getPlayer(), holdsClaw(event.getPlayer())); }

    @EventHandler
    public void quit(PlayerQuitEvent event) { mining.remove(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void death(PlayerDeathEvent event) { mining.remove(event.getEntity().getUniqueId()); }

    // ===== копание чужими инструментами =====

    /** Начали копать блок: считаем, чем и как быстро его сломает клешня. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        Mining session = session(player, event.getBlock());
        if (session == null) {
            mining.remove(player.getUniqueId());
            return;
        }
        mining.put(player.getUniqueId(), session);
        ensureTask();
    }

    /** Игрок перестал копать блок — сессия больше не нужна. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void abort(BlockDamageAbortEvent event) {
        Mining session = mining.get(event.getPlayer().getUniqueId());
        if (session != null && session.block.equals(event.getBlock().getLocation())) {
            mining.remove(event.getPlayer().getUniqueId());
        }
    }

    /** Клиент сломал блок первым (мгновенный блок или медленный инструмент) — берём работу на себя. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void broke(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (asking || player.getGameMode() == GameMode.CREATIVE || !clawInMainHand(player)) return;
        Block block = event.getBlock();
        if (block.getType().getHardness() < 0) return;
        Borrow borrow = borrow(player, block);
        if (borrow == null) return;      // нечем — клешня копает как рука, всё остаётся ванильным
        event.setCancelled(true);
        mining.remove(player.getUniqueId());
        breakDirectly(player, block, borrow.slot());
    }

    /** Сколько тиков займёт этот блок этим инструментом (null — голой рукой).
     *  Всё как в ванили: скорость даёт семейство, делитель 30 вместо 100 — подходящий уровень. */
    private int breakTicks(Player player, Block block, ItemStack tool) {
        CrabClawRules.Family blockFamily = family(block);
        CrabClawRules.Family toolFamily = tool == null
                ? CrabClawRules.Family.NONE : CrabClawRules.family(tool.getType().name());
        boolean fits = CrabClawRules.fits(toolFamily, blockFamily);
        double speed = 1.0;
        if (fits) {
            speed = toolSpeed(tool, block, toolFamily);
            if (isDigger(toolFamily)) {
                speed = CrabClawRules.withEfficiency(speed, tool.getEnchantmentLevel(Enchantment.EFFICIENCY));
            }
        }
        speed *= CrabClawRules.effectMultiplier(level(player, PotionEffectType.HASTE),
                level(player, PotionEffectType.CONDUIT_POWER));
        int toolTier = tool == null ? -1 : CrabClawRules.toolTier(tool.getType().name());
        boolean correctTool = CrabClawRules.correctTool(toolFamily, toolTier, blockFamily, requiredTier(block));
        return CrabClawRules.breakTicks(speed, block.getType().getHardness(), correctTool,
                CrabClawRules.fatigueMultiplier(amplifier(player, PotionEffectType.MINING_FATIGUE)));
    }

    /** Скорость инструмента по этому блоку: у кирок и прочих — по уровню, у мечей и ножниц — своя. */
    private static double toolSpeed(ItemStack tool, Block block, CrabClawRules.Family toolFamily) {
        Material type = block.getType();
        if (toolFamily == CrabClawRules.Family.SWORD) {
            return type == Material.COBWEB ? 15.0 : 1.5;      // меч: паутина вмиг, «своё» — в полтора раза
        }
        if (toolFamily == CrabClawRules.Family.SHEARS) {
            if (type == Material.COBWEB) return 15.0;          // паутина — вмиг, как мечом
            return Tag.WOOL.isTagged(type) ? 5.0 : 2.0;        // шерсть — впятеро, прочее — вдвое
        }
        return CrabClawRules.tierSpeed(tool.getType().name());
    }

    /** Кирка, топор, лопата, мотыга — только они получают «Эффективность». */
    private static boolean isDigger(CrabClawRules.Family family) {
        return family == CrabClawRules.Family.PICKAXE || family == CrabClawRules.Family.AXE
                || family == CrabClawRules.Family.SHOVEL || family == CrabClawRules.Family.HOE;
    }

    /** Уровень, которого блок требует от инструмента: по ванильным тегам needs_*_tool. */
    private static int requiredTier(Block block) {
        Material type = block.getType();
        return CrabClawRules.requiredTier(Tag.NEEDS_STONE_TOOL.isTagged(type), Tag.NEEDS_IRON_TOOL.isTagged(type),
                Tag.NEEDS_DIAMOND_TOOL.isTagged(type));
    }

    private static int level(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    private static int amplifier(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    /** Семейство блока по ванильным тегам: кирка, топор, лопата, мотыга, меч, ножницы.
     *  Порядок важен: листва помечена и тегами мотыги, и тегами ножниц — мотыга идёт первой,
     *  как в ванили, где мотыга рвёт листву быстрее всего. */
    private static CrabClawRules.Family family(Block block) {
        Material type = block.getType();
        if (Tag.MINEABLE_PICKAXE.isTagged(type)) return CrabClawRules.Family.PICKAXE;
        if (Tag.MINEABLE_AXE.isTagged(type)) return CrabClawRules.Family.AXE;
        if (Tag.MINEABLE_SHOVEL.isTagged(type)) return CrabClawRules.Family.SHOVEL;
        if (Tag.MINEABLE_HOE.isTagged(type)) return CrabClawRules.Family.HOE;
        if (Tag.SWORD_EFFICIENT.isTagged(type) || Tag.SWORD_INSTANTLY_MINES.isTagged(type)) {
            return CrabClawRules.Family.SWORD;
        }
        if (type == Material.COBWEB || Tag.WOOL.isTagged(type)) return CrabClawRules.Family.SHEARS;
        if (type == Material.VINE || type == Material.GLOW_LICHEN || type == Material.HANGING_ROOTS
                || type == Material.TRIPWIRE) return CrabClawRules.Family.SHEARS;   // то, что режут ножницами
        return CrabClawRules.Family.NONE;
    }

    /** Новая сессия копания: null — копать будет сама рука или блок ломается мгновенно. */
    private Mining session(Player player, Block block) {
        if (player.getGameMode() == GameMode.CREATIVE || !clawInMainHand(player)) return null;
        if (block.getType().getHardness() < 0) return null;            // неразрушимый блок
        Borrow borrow = borrow(player, block);
        if (borrow == null || borrow.ticks() <= 0) return null;
        Mining session = new Mining();
        session.block = block.getLocation();
        session.slot = borrow.slot();
        session.ticks = borrow.ticks();
        session.elapsed = 0;
        return session;
    }

    /** Лучший подходящий инструмент: тот, которым блок сломается быстрее всего — и быстрее руки. */
    private Borrow borrow(Player player, Block block) {
        PlayerInventory inventory = player.getInventory();
        int handTicks = breakTicks(player, block, null);
        Borrow best = null;
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            best = better(best, candidate(player, block, storage[slot], slot, handTicks));
        }
        return better(best, candidate(player, block, inventory.getItemInOffHand(), -1, handTicks));
    }

    private Borrow candidate(Player player, Block block, ItemStack stack, int slot, int handTicks) {
        if (stack == null || stack.getType().isAir() || items.kind(stack) != null) return null;
        if (!(stack.getItemMeta() instanceof Damageable)) return null;  // не инструмент — изнашивать нечего
        if (CrabClawRules.family(stack.getType().name()) == CrabClawRules.Family.NONE) return null;
        int ticks = breakTicks(player, block, stack);
        return CrabClawRules.borrowable(ticks, handTicks) ? new Borrow(stack.clone(), slot, ticks) : null;
    }

    private static Borrow better(Borrow best, Borrow candidate) {
        if (candidate == null) return best;
        return best == null || candidate.ticks() < best.ticks() ? candidate : best;
    }

    private void ensureTask() {
        if (miningTask != null) return;
        miningTask = Bukkit.getScheduler().runTaskTimer(plugin, this::mineTick, 1L, 1L);
    }

    private void mineTick() {
        if (mining.isEmpty()) {
            miningTask.cancel();
            miningTask = null;
            return;
        }
        Iterator<Map.Entry<UUID, Mining>> entries = mining.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Mining> entry = entries.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Mining session = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead() || !clawInMainHand(player)
                    || player.getGameMode() == GameMode.CREATIVE || !reachable(player, session)) {
                entries.remove();
                continue;
            }
            session.elapsed++;
            if (session.elapsed < session.ticks) {
                player.sendBlockDamage(session.block,
                        (float) Math.min(0.99, (double) session.elapsed / session.ticks), player.getEntityId());
                continue;
            }
            entries.remove();
            breakFromTick(player, session);
        }
    }

    /** Блок ещё тот же и игрок рядом с ним: иначе сессию бросаем. */
    private boolean reachable(Player player, Mining session) {
        Block block = session.block.getBlock();
        if (block.getType().isAir() || block.getType().getHardness() < 0) return false;
        Location eye = player.getEyeLocation();
        if (!eye.getWorld().equals(session.block.getWorld())) return false;
        AttributeInstance range = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double reach = range == null ? 4.5 : range.getValue();
        return eye.toVector().distance(session.block.toVector().add(new Vector(0.5, 0.5, 0.5))) <= reach + 1.0;
    }

    /** Пришли своим таймером: спрашиваем другие плагины (приваты) и ломаем блок инструментом. */
    private void breakFromTick(Player player, Mining session) {
        Block block = session.block.getBlock();
        if (block.getType().isAir()) return;
        BlockBreakEvent asked = new BlockBreakEvent(block, player);
        asking = true;
        try {
            Bukkit.getPluginManager().callEvent(asked);
        } finally {
            asking = false;
        }
        if (asked.isCancelled() || block.getType().isAir()) return;
        breakDirectly(player, block, session.slot);
    }

    /** Ломает блок «чужим» инструментом: дроп и опыт как у инструмента, а износ у инструмента вдвое. */
    private void breakDirectly(Player player, Block block, int slot) {
        ItemStack tool = toolAt(player, slot);
        if (tool == null) {
            block.breakNaturally(true, true);
            return;
        }
        block.breakNaturally(tool, true, true);
        wear(player, slot);
    }

    private void wear(Player player, int slot) {
        int amount = wearPerBlock();
        if (amount <= 0) return;
        ItemStack tool = toolAt(player, slot);
        if (tool == null || !(tool.getItemMeta() instanceof Damageable meta)) return;
        int max = tool.getType().getMaxDurability();
        int next = meta.getDamage() + amount;
        if (max > 0 && next >= max) {          // инструмент стёрся до конца — как в ванили
            ItemStack broken = tool.clone();
            setTool(player, slot, null);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            Bukkit.getPluginManager().callEvent(new PlayerItemBreakEvent(player, broken));
            return;
        }
        meta.setDamage(next);
        tool.setItemMeta(meta);
        setTool(player, slot, tool);
    }

    private static ItemStack toolAt(Player player, int slot) {
        ItemStack tool = slot < 0 ? player.getInventory().getItemInOffHand() : player.getInventory().getItem(slot);
        return tool == null || tool.getType().isAir() ? null : tool;
    }

    private static void setTool(Player player, int slot, ItemStack tool) {
        ItemStack value = tool == null ? new ItemStack(Material.AIR) : tool;
        if (slot < 0) player.getInventory().setItem(EquipmentSlot.OFF_HAND, value);
        else player.getInventory().setItem(slot, value);
    }
}
