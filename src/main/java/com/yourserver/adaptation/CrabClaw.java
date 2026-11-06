package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.Tool;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
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
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
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
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

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
 * компонента нет, и клешня копает как обычная рука.
 *
 * <p>Зачарования клешня берёт у того инструмента, чьим правилом блок и ломается: кирка с «Шёлком»
 * кладёт камень блоками, а ножницы без «Шелка» по шерсти зачарований не получают — каждое правило
 * носит зачарования своего инструмента. Из них клешня подставляет себе ровно то, что решает дроп,
 * — «Удачу» и «Шёлковое касание»: перед поломкой блока (и на самом старте копания) она надевает
 * зачарования нужного инструмента, а после держит их невидимо — без блеска и без строчек
 * в подсказке, чтобы вид предмета остался прежним. «Прочность» считается при износе (шанс
 * уровень/(уровень+1) не тратить ничего), «Починка» — когда из блока идёт опыт: орбиты
 * не появляются, а инструмент чинится на две прочности за очко, как в ванили. «Эффективность»
 * уже сидит в скорости правил, поэтому второй раз не копируется. Инструмент, которым сломали
 * блок, тратит вдвое больше прочности; сама клешня не изнашивается.
 *
 * Сообщений в чат и над хотбаром механика не пишет: всё видно по игре.
 */
final class CrabClaw implements Listener {
    /** Как часто сверяем руку с инвентарём: раз в четверть секунды. */
    private static final int REFRESH_TICKS = 5;

    /** Правило, которое клешня отдаёт Minecraft: те же блоки, скорость и дроп, что у инструмента,
     *  плюс его «Прочность», «Починка» и зачарования дропа — они поедут вместе с этим правилом. */
    private record Rule(RegistryKeySet<BlockType> blocks, Set<String> anchors, double speed, boolean drops,
                        int slot, int unbreaking, int mending, Map<Enchantment, Integer> loot) { }

    /** Взятый в займы инструмент: что он даёт клешне и в каком слоте лежит. */
    private record Borrow(int slot, String material, CrabClawRules.Family family, List<Rule> rules,
                          int efficiency) { }

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
        Runnable tick = () -> refresh();   // лямбда, а не ссылка: у планировщика два подходящих перегруза
        task = Bukkit.getScheduler().runTaskTimer(plugin, tick, REFRESH_TICKS, REFRESH_TICKS);
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
        List<Rule> rules = rules(toolbox);
        String signature = signature(toolbox);
        ItemMeta meta = held.getItemMeta();
        boolean sameToolbox = signature.equals(meta.getPersistentDataContainer().get(toolboxKey, PersistentDataType.STRING));
        if (!sameToolbox) {
            meta.getPersistentDataContainer().set(toolboxKey, PersistentDataType.STRING, signature);
            ItemStack updated = held.clone();
            updated.setItemMeta(meta);
            if (rules.isEmpty()) updated.unsetData(DataComponentTypes.TOOL);
            else updated.setData(DataComponentTypes.TOOL, component(rules));
            player.getInventory().setItemInMainHand(updated);
        }
        // Зачарования — по тому блоку, на который игрок смотрит: у каждого инструмента они свои.
        applyLoot(player, rules, player.getTargetBlockExact(targetRange(player)));
    }

    /** Дальность, на которой ищем блок под прицелом: своя дальность игрока (у клешни она
     *  на три блока больше) плюс запас, чтобы блок у самой границы не остался без зачарований. */
    private int targetRange(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double reach = instance == null ? 4.5 : instance.getValue();
        return Math.clamp((int) Math.ceil(reach) + 1, 4, 64);
    }

    /** Зачарования дропа берём у того правила, которое сработает на этом блоке. */
    private void applyLoot(Player player, List<Rule> rules, Block block) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (items.kind(held) != WinterItems.Kind.CLAW) return;
        Map<Enchantment, Integer> loot = Map.of();
        if (block != null) {
            TypedKey<BlockType> key = TypedKey.create(RegistryKey.BLOCK, block.getType().getKey());
            for (Rule rule : rules) {
                if (!rule.blocks().contains(key)) continue;
                loot = rule.loot();
                break;
            }
        }
        if (enchantsMatch(held, loot)) return;
        applyEnchants(held, loot);
        player.getInventory().setItemInMainHand(held);
    }

    /** Невидимые зачарования клешни: блеск выключен, строчек в подсказке нет — вид предмета прежний. */
    private void applyEnchants(ItemStack claw, Map<Enchantment, Integer> enchants) {
        if (enchants.isEmpty()) {
            claw.unsetData(DataComponentTypes.ENCHANTMENTS);
            claw.unsetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
            claw.unsetData(DataComponentTypes.TOOLTIP_DISPLAY);
            return;
        }
        claw.setData(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.itemEnchantments(enchants));
        claw.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
        claw.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .hideTooltip(false)                                               // сама подсказка на месте
                .addHiddenComponents(DataComponentTypes.ENCHANTMENTS)             // а строк зачарований нет
                .build());
    }

    /** Совпадают ли зачарования клешни с теми, что положены по инвентарю: так мы чиним предмет,
     *  если зачарования с него сняли (например, точильным камнем) или он их ещё не получал. */
    private static boolean enchantsMatch(ItemStack claw, Map<Enchantment, Integer> desired) {
        ItemEnchantments current = claw.getData(DataComponentTypes.ENCHANTMENTS);
        Map<Enchantment, Integer> have = current == null ? Map.of() : current.enchantments();
        return have.equals(desired);
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

    /**
     * Инструменты, которые клешня берёт в займы: по одному на семейство — первый по порядку
     * просмотра инвентаря (справа налево, сверху вниз, затем горячий ряд и левая рука).
     * Порядок тот же, что глазами: если в верхнем ряду справа лежит кирка с «Шёлком», а дальше
     * в инвентаре есть кирка получше, клешня всё равно копает той, что лежит первой.
     */
    private Map<CrabClawRules.Family, Borrow> toolbox(Player player) {
        Map<CrabClawRules.Family, Borrow> found = new EnumMap<>(CrabClawRules.Family.class);
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot : CrabClawRules.SCAN) {
            ItemStack stack = slot == CrabClawRules.OFFHAND
                    ? inventory.getItemInOffHand()
                    : (slot >= 0 && slot < storage.length ? storage[slot] : null);
            Borrow borrow = borrow(stack, slot);
            if (borrow != null) found.putIfAbsent(borrow.family(), borrow);
        }
        return found;
    }

    /** Что предмет может дать клешне: null — это не инструмент, брать нечего. */
    private Borrow borrow(ItemStack stack, int slot) {
        if (stack == null || stack.getType().isAir()) return null;
        if (items.kind(stack) != null) return null;                    // чужой улов не одалживаем
        if (stack.getType().getMaxDurability() <= 0) return null;      // не инструмент — изнашивать нечего
        String material = stack.getType().name();
        CrabClawRules.Family family = CrabClawRules.family(material);
        ItemMeta tool = stack.getItemMeta();
        int efficiency = Math.max(0, tool.getEnchantLevel(Enchantment.EFFICIENCY));
        int unbreaking = Math.max(0, tool.getEnchantLevel(Enchantment.UNBREAKING));
        int mending = Math.max(0, tool.getEnchantLevel(Enchantment.MENDING));
        // Зачарования дропа: «Удача» и «Шёлковое касание». На обычном инструменте бывает только
        // одно из них; если выданный командой предмет несёт оба, берём то, что попалось первым.
        Map<String, Integer> levels = new LinkedHashMap<>();
        Map<String, Enchantment> keys = new LinkedHashMap<>();
        for (Map.Entry<Enchantment, Integer> enchant : tool.getEnchants().entrySet()) {
            String id = enchant.getKey().getKey().toString();
            if (!CrabClawRules.LOOT_ENCHANTS.contains(id)) continue;
            levels.put(id, enchant.getValue());
            keys.put(id, enchant.getKey());
        }
        String picked = CrabClawRules.lootEnchant(new ArrayList<>(levels.keySet()));
        Map<Enchantment, Integer> loot = Map.of();
        if (!picked.isEmpty()) loot = Map.of(keys.get(picked), levels.get(picked));
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
                        rule.correctForDrops() == TriState.TRUE, slot, unbreaking, mending, loot));
            }
            if (family == CrabClawRules.Family.NONE) family = CrabClawRules.family(anchors(rules));
        } else {
            if (family == CrabClawRules.Family.NONE) return null;
            double speed = CrabClawRules.withEfficiency(CrabClawRules.tierSpeed(material), efficiency);
            int tier = Math.max(0, CrabClawRules.toolTier(material));
            for (CrabClawRules.Spec spec : CrabClawRules.fallback(family, tier, speed)) {
                RegistryKeySet<BlockType> blocks = blocks(spec.blocks());
                if (blocks == null) continue;
                rules.add(new Rule(blocks, anchors(blocks), spec.speed(), spec.drops(),
                        slot, unbreaking, mending, loot));
            }
        }
        if (family == CrabClawRules.Family.NONE || rules.isEmpty()) return null;
        return new Borrow(slot, material, family, List.copyOf(rules), efficiency);
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

    // ===== зачарования и износ чужого инструмента =====

    /**
     * Игрок начал копать блок — сразу надеваем на клешню зачарования того инструмента, которым этот
     * блок и будет ломаться: движок читает предмет в момент поломки, так что «Удача» успевает
     * на дроп, «Шёлк» — на сам блок, а чужой инструмент этими зачарованиями не пользуется вовсе.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (!clawInMainHand(player)) return;
        applyLoot(player, rules(toolbox(player)), event.getBlock());
    }

    /** Блок сломала сама игра — остаётся надеть зачарования нужного инструмента и износить его. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broke(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!clawInMainHand(player)) return;
        List<Rule> rules = rules(toolbox(player));
        // Блок читается движком уже после события, поэтому зачарования успевают попасть в дроп.
        applyLoot(player, rules, event.getBlock());
        if (player.getGameMode() == GameMode.CREATIVE) return;
        TypedKey<BlockType> block = TypedKey.create(RegistryKey.BLOCK, event.getBlock().getType().getKey());
        for (Rule rule : rules) {
            if (!rule.blocks().contains(block)) continue;
            wear(player, rule);
            mend(player, rule, event);
            return;
        }
    }

    /** Износ с «Прочностью»: чем выше уровень, тем чаще блок не стоит ничего. */
    private void wear(Player player, Rule rule) {
        int amount = CrabClawRules.wear(wearPerBlock(), rule.unbreaking(), ThreadLocalRandom.current().nextDouble());
        if (amount <= 0) return;
        PlayerInventory inventory = player.getInventory();
        int slot = rule.slot();
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

    /** «Починка»: опыт из блока не падает орбитой, а чинит инструмент — две прочности за очко.
     *  Так же ведёт себя зачарованный инструмент в руке; если чинить нечего, опыт остаётся игроку. */
    private void mend(Player player, Rule rule, BlockBreakEvent event) {
        int experience = event.getExpToDrop();
        if (rule.mending() <= 0 || experience <= 0) return;
        PlayerInventory inventory = player.getInventory();
        int slot = rule.slot();
        ItemStack stack = slot < 0 ? inventory.getItemInOffHand() : inventory.getItem(slot);
        if (stack == null || stack.getType().isAir()) return;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable damage) || damage.getDamage() <= 0) return;
        damage.setDamage(CrabClawRules.mend(damage.getDamage(), experience));
        stack.setItemMeta(meta);
        setTool(inventory, slot, stack);
        event.setExpToDrop(0);      // опыт ушёл в инструмент, как с «Починкой» в руке
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
