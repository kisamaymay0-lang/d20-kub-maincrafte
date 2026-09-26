package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Tool;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Клешня краба — улов болот (обычного и мангрового).
 *
 * В любой руке она добавляет три блока к дальности взаимодействия: атрибуты
 * {@code block_interaction_range} и {@code entity_interaction_range} клиент получает сам,
 * поэтому дотянуться действительно можно.
 *
 * А в правой руке она копает «чужими» инструментами из инвентаря. Клешне подставляется компонент
 * {@code minecraft:tool} — тот самый, которым описаны настоящие инструменты, — а правила берутся
 * у инструментов игрока: ванильный набор блоков плюс скорость и дроп этого инструмента. Поэтому
 * клешня копает ровно так, как копал бы он сам: игра считает и скорость, и дроп, и полосу копания
 * на клиенте, и защиту регионов (блок ломает сама игра, а не плагин). Нет подходящего инструмента —
 * компонента нет, и клешня копает как обычная рука. Инструмент, которым сломали блок, тратит вдвое
 * больше прочности; сама клешня не изнашивается.
 *
 * Сообщений в чат и над хотбаром механика не пишет: всё видно по игре.
 */
final class CrabClaw implements Listener {
    /** Как часто сверяем руку с инвентарём: раз в четверть секунды. */
    private static final int REFRESH_TICKS = 5;

    /** Правило, которое клешня отдаёт Minecraft: те же блоки, скорость и дроп, что у инструмента. */
    private record Rule(RegistryKeySet<BlockType> blocks, Set<String> anchors,
                        double speed, boolean drops, int slot) {
        CrabClawRules.Rule plain() {
            return new CrabClawRules.Rule(anchors, speed, drops);
        }
    }

    /** Взятый в займы инструмент: что он даёт клешне и в каком слоте лежит. */
    private record Borrow(int slot, String material, CrabClawRules.Family family,
                          List<Rule> rules, int efficiency) { }

    private final JavaPlugin plugin;
    private final WinterItems items;
    private final NamespacedKey reachKey;
    /** Подпись собранных правил лежит в самом предмете: так мы не пересылаем его зря. */
    private final NamespacedKey toolboxKey;
    private final BukkitTask task;
    /** Наборы блоков — собираем один раз на всё время работы. */
    private final Map<String, RegistryKeySet<BlockType>> sets = new HashMap<>();

    CrabClaw(JavaPlugin plugin, WinterItems items) {
        this.plugin = plugin;
        this.items = items;
        this.reachKey = new NamespacedKey(plugin, "crab_claw_reach");
        this.toolboxKey = new NamespacedKey(plugin, "crab_claw_toolbox");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, REFRESH_TICKS, REFRESH_TICKS);
    }

    void disable() {
        task.cancel();
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

    // ===== обновление руки =====

    private void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
    }

    private void refresh(Player player) {
        setReach(player, holdsClaw(player));
        applyMining(player);
    }

    /** Слот меняется уже после события, поэтому смотрим руку следующим тиком. */
    private void nextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> refresh(player));
    }

    @EventHandler
    public void join(PlayerJoinEvent event) { refresh(event.getPlayer()); }

    @EventHandler
    public void respawn(PlayerRespawnEvent event) { refresh(event.getPlayer()); }

    @EventHandler
    public void held(PlayerItemHeldEvent event) { nextTick(event.getPlayer()); }

    @EventHandler
    public void swap(PlayerSwapHandItemsEvent event) { nextTick(event.getPlayer()); }

    // ===== копание: клешня получает компонент инструмента =====

    /** Собирает клешне правила по инструментам в инвентаре — только когда она в правой руке. */
    private void applyMining(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (items.kind(held) != WinterItems.Kind.CLAW) return;
        Map<CrabClawRules.Family, Borrow> toolbox = toolbox(player);
        String signature = signature(toolbox);
        ItemMeta meta = held.getItemMeta();
        if (signature.equals(meta.getPersistentDataContainer().get(toolboxKey, PersistentDataType.STRING))) return;
        List<Rule> rules = rules(toolbox);
        meta.getPersistentDataContainer().set(toolboxKey, PersistentDataType.STRING, signature);
        ItemStack updated = held.clone();
        updated.setItemMeta(meta);
        if (rules.isEmpty()) updated.unsetData(DataComponentTypes.TOOL);
        else updated.setData(DataComponentTypes.TOOL, component(rules));
        player.getInventory().setItemInMainHand(updated);
    }

    /** Компонент инструмента: скорость копания, износ самой клешни — ноль (она не изнашивается). */
    private Tool component(List<Rule> rules) {
        Tool.Builder builder = Tool.tool()
                .defaultMiningSpeed(1.0f)
                .damagePerBlock(0)
                .canDestroyBlocksInCreative(true);
        for (Rule rule : rules) {
            builder.addRule(Tool.rule(rule.blocks(), (float) rule.speed(),
                    rule.drops() ? TriState.TRUE : TriState.FALSE));
        }
        return builder.build();
    }

    /** Правила всех взятых инструментов: семейства идут по старшинству, как в ванильных наборах. */
    private List<Rule> rules(Map<CrabClawRules.Family, Borrow> toolbox) {
        List<Rule> out = new ArrayList<>();
        for (CrabClawRules.Family family : CrabClawRules.ORDER) {
            Borrow borrow = toolbox.get(family);
            if (borrow != null) out.addAll(borrow.rules());
        }
        return List.copyOf(out);
    }

    /** Подпись набора инструментов: по ней видно, что клешне уже выданы нужные правила. */
    private String signature(Map<CrabClawRules.Family, Borrow> toolbox) {
        StringBuilder out = new StringBuilder(Integer.toString(toolbox.size()));
        for (CrabClawRules.Family family : CrabClawRules.ORDER) {
            Borrow borrow = toolbox.get(family);
            if (borrow == null) continue;
            out.append('|').append(family).append(' ').append(borrow.material())
                    .append(" эф").append(borrow.efficiency())
                    .append(" x").append(borrow.rules().size())
                    .append('@').append(borrow.rules().stream().mapToDouble(Rule::speed).sum());
        }
        return out.toString();
    }

    // ===== инструменты из инвентаря =====

    /** Лучший инструмент каждого семейства: сильнее по уровню, при равной силе — быстрее. */
    private Map<CrabClawRules.Family, Borrow> toolbox(Player player) {
        Map<CrabClawRules.Family, Borrow> found = new EnumMap<>(CrabClawRules.Family.class);
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) add(found, storage[slot], slot);
        add(found, inventory.getItemInOffHand(), -1);
        return found;
    }

    private void add(Map<CrabClawRules.Family, Borrow> found, ItemStack stack, int slot) {
        Borrow borrow = borrow(stack, slot);
        if (borrow == null) return;
        Borrow current = found.get(borrow.family());
        if (current == null) {
            found.put(borrow.family(), borrow);
            return;
        }
        int score = score(borrow);
        int best = score(current);
        boolean wins = score > best || (score == best
                && CrabClawRules.topSpeed(plain(borrow)) > CrabClawRules.topSpeed(plain(current)));
        if (wins) found.put(borrow.family(), borrow);
    }

    /** Что предмет может дать клешне: null — это не инструмент, брать нечего. */
    private Borrow borrow(ItemStack stack, int slot) {
        if (stack == null || stack.getType().isAir()) return null;
        if (items.kind(stack) != null) return null;                    // чужой улов не одалживаем
        if (stack.getType().getMaxDurability() <= 0) return null;      // не инструмент — изнашивать нечего
        String material = stack.getType().name();
        CrabClawRules.Family family = CrabClawRules.family(material);
        int efficiency = Math.max(0, stack.getEnchantmentLevel(Enchantment.EFFICIENCY));
        double bonus = efficiency > 0 ? (double) efficiency * efficiency + 1.0 : 0.0;
        List<Rule> rules = new ArrayList<>();
        Tool component = stack.getData(DataComponentTypes.TOOL);
        if (component != null) {
            // Настоящий инструмент: берём его собственные правила, поэтому и скорость, и дроп
            // получаются такими же, как если бы игрок копал им самим. Зачарование усиливает
            // только те правила, где инструмент действительно копает быстрее руки.
            for (Tool.Rule rule : component.rules()) {
                double speed = rule.speed() == null ? component.defaultMiningSpeed() : rule.speed();
                if (speed > 1.0) speed += bonus;
                rules.add(new Rule(rule.blocks(), anchors(rule.blocks()), speed,
                        rule.correctForDrops() == TriState.TRUE, slot));
            }
            if (family == CrabClawRules.Family.NONE) family = CrabClawRules.family(anchors(rules));
        } else {
            if (family == CrabClawRules.Family.NONE) return null;
            double speed = CrabClawRules.withEfficiency(CrabClawRules.tierSpeed(material), efficiency);
            int tier = Math.max(0, CrabClawRules.toolTier(material));
            for (CrabClawRules.Spec spec : CrabClawRules.fallback(family, tier, speed)) {
                RegistryKeySet<BlockType> blocks = blocks(spec.blocks());
                if (blocks == null) continue;
                rules.add(new Rule(blocks, anchors(blocks), spec.speed(), spec.drops(), slot));
            }
        }
        if (family == CrabClawRules.Family.NONE || rules.isEmpty()) return null;
        return new Borrow(slot, material, family, List.copyOf(rules), efficiency);
    }

    private static int score(Borrow borrow) {
        return CrabClawRules.score(plain(borrow));
    }

    private static List<CrabClawRules.Rule> plain(Borrow borrow) {
        List<CrabClawRules.Rule> out = new ArrayList<>();
        for (Rule rule : borrow.rules()) out.add(rule.plain());
        return out;
    }

    /** Опорные блоки правил: по ним узнаём семейство незнакомого инструмента и его силу. */
    private static Set<String> anchors(List<Rule> rules) {
        Set<String> found = new LinkedHashSet<>();
        for (Rule rule : rules) found.addAll(rule.anchors());
        return found;
    }

    private static Set<String> anchors(RegistryKeySet<BlockType> blocks) {
        Set<String> found = new LinkedHashSet<>();
        for (String anchor : CrabClawRules.ANCHORS) {
            if (blocks.contains(TypedKey.create(RegistryKey.BLOCK, NamespacedKey.minecraft(anchor)))) found.add(anchor);
        }
        return found;
    }

    // ===== износ чужого инструмента =====

    /** Блок сломала сама игра — остаётся износить инструмент, которым клешня копала. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broke(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || !clawInMainHand(player)) return;
        TypedKey<BlockType> block = TypedKey.create(RegistryKey.BLOCK, event.getBlock().getType().getKey());
        for (Rule rule : rules(toolbox(player))) {
            if (!rule.blocks().contains(block)) continue;
            wear(player, rule.slot());
            return;
        }
    }

    private void wear(Player player, int slot) {
        int amount = wearPerBlock();
        if (amount <= 0) return;
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = slot < 0 ? inventory.getItemInOffHand() : inventory.getItem(slot);
        if (stack == null || stack.getType().isAir()) return;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable damage)) return;
        int max = stack.getType().getMaxDurability();
        int next = damage.getDamage() + amount;
        if (max > 0 && next >= max) {          // инструмент стёрся до конца — как в ванили
            ItemStack broken = stack.clone();
            setTool(inventory, slot, null);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            Bukkit.getPluginManager().callEvent(new PlayerItemBreakEvent(player, broken));
            return;
        }
        damage.setDamage(next);
        stack.setItemMeta(meta);
        setTool(inventory, slot, stack);
    }

    private static void setTool(PlayerInventory inventory, int slot, ItemStack tool) {
        ItemStack value = tool == null ? new ItemStack(Material.AIR) : tool;
        if (slot < 0) inventory.setItem(EquipmentSlot.OFF_HAND, value);
        else inventory.setItem(slot, value);
    }

    // ===== наборы блоков =====

    /** Ванильный тег как набор блоков: у Paper он и есть набор ключей, поэтому в предмете
     *  остаётся короткая ссылка «#тег», а не сотня отдельных блоков. */
    private RegistryKeySet<BlockType> tagSet(String tag) {
        Registry<BlockType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK);
        TagKey<BlockType> key = TagKey.create(RegistryKey.BLOCK, NamespacedKey.minecraft(tag));
        return registry.hasTag(key) ? registry.getTag(key) : null;
    }

    /** Набор блоков: ванильный тег или перечень блоков — с кэшем, чтобы не дёргать реестр зря. */
    private RegistryKeySet<BlockType> blocks(CrabClawRules.Blocks spec) {
        String key = spec.tag() != null ? "#" + spec.tag() : String.join(",", spec.names());
        if (sets.containsKey(key)) return sets.get(key);
        RegistryKeySet<BlockType> set = null;
        try {
            if (spec.tag() != null) {
                set = tagSet(spec.tag());
            } else {
                List<BlockType> found = spec.names().stream()
                        .map(Material::matchMaterial).filter(Objects::nonNull)
                        .map(Material::asBlockType).filter(Objects::nonNull).toList();
                if (!found.isEmpty()) set = RegistrySet.keySetFromValues(RegistryKey.BLOCK, found);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Клешня краба: набор блоков " + key + " не собрался (" + ex.getMessage() + ")");
        }
        if (set == null) plugin.getLogger().warning("Клешня краба: набор блоков " + key + " не нашёлся");
        sets.put(key, set);
        return set;
    }
}
