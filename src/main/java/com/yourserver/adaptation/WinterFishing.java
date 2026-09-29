package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.potion.PotionMix;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Превращения вида рыбы и предметные события зимней рыбалки.
 *
 * Особый зимний предмет («изморозь») больше НЕ выпадает вместо рыбы: единственный
 * способ получить его — миниигра особого улова (см. SpecialCatch), в зимних и
 * ледяных биомах она даёт как раз изморозь.
 */
final class WinterFishing implements Listener {
    private record Replacement(int slot, ItemStack fish) { }
    private final JavaPlugin plugin;
    final WinterItems items;
    private final WinterMovement movement;
    private final CrabClaw crabClaw;
    private final Map<UUID, List<Replacement>> replacements = new HashMap<>();
    private final List<NamespacedKey> recipes = new ArrayList<>();
    private final List<NamespacedKey> potionMixes = new ArrayList<>();
    private final NamespacedKey rimeCloudKey;

    WinterFishing(JavaPlugin plugin) {
        this.plugin = plugin;
        items = new WinterItems(plugin);
        movement = new WinterMovement(plugin, items);
        rimeCloudKey = new NamespacedKey(plugin, "rime_potion_cloud");
        plugin.getServer().getPluginManager().registerEvents(movement, plugin);
        crabClaw = new CrabClaw(plugin, items);
        registerRecipes();
        new WinterItemGuard(plugin, items);
    }

    private NamespacedKey recipeKey(String name) {
        NamespacedKey key = new NamespacedKey(plugin, name);
        Bukkit.removeRecipe(key); recipes.add(key); return key;
    }

    private void registerRecipes() {
        ItemStack sandwiches = items.create(WinterItems.Kind.SANDWICH); sandwiches.setAmount(2);
        ShapelessRecipe sandwich = new ShapelessRecipe(recipeKey("ice_caviar_sandwich"), sandwiches);
        sandwich.addIngredient(Material.BREAD);
        sandwich.addIngredient(items.recipeInput(WinterItems.Kind.ROE));
        Bukkit.addRecipe(sandwich);
        for (WinterItems.Kind kind : List.of(WinterItems.Kind.RAW, WinterItems.Kind.DEPLETED)) {
            ItemStack fish = items.create(kind);
            // Нагрев возвращает ту же рыбу. Нулевой опыт не превращает повторный нагрев в ферму XP.
            Bukkit.addRecipe(new FurnaceRecipe(recipeKey(kind.id + "_furnace"), fish, items.recipeInput(kind), 0f, 200));
            Bukkit.addRecipe(new SmokingRecipe(recipeKey(kind.id + "_smoker"), fish, items.recipeInput(kind), 0f, 100));
            Bukkit.addRecipe(new CampfireRecipe(recipeKey(kind.id + "_campfire"), fish, items.recipeInput(kind), 0f, 600));
        }
        registerRimePotionMix("rime_potion", Material.POTION, WinterItems.Kind.RIME_POTION);
        registerRimePotionMix("rime_potion_splash", Material.SPLASH_POTION, WinterItems.Kind.RIME_POTION_SPLASH);
        registerRimePotionMix("rime_potion_lingering", Material.LINGERING_POTION, WinterItems.Kind.RIME_POTION_LINGERING);
    }

    private void registerRimePotionMix(String id, Material inputMaterial, WinterItems.Kind outputKind) {
        NamespacedKey key = new NamespacedKey(plugin, id);
        Bukkit.getPotionBrewer().removePotionMix(key);
        potionMixes.add(key);
        ItemStack input = new ItemStack(inputMaterial);
        if (input.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(PotionType.WATER);
            input.setItemMeta(meta);
        }
        Bukkit.getPotionBrewer().addPotionMix(new PotionMix(key, items.create(outputKind),
                new RecipeChoice.ExactChoice(input), items.recipeInput(WinterItems.Kind.ROE)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void gut(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getPlayer().isSneaking()
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player player = event.getPlayer();
        ItemStack fish = player.getInventory().getItemInMainHand();
        if (items.kind(fish) != WinterItems.Kind.RAW) return;
        event.setCancelled(true);
        ItemStack depleted = items.create(WinterItems.Kind.DEPLETED);
        if (fish.getAmount() <= 1) player.getInventory().setItemInMainHand(depleted);
        else { fish.setAmount(fish.getAmount() - 1); give(player, depleted); }
        give(player, items.create(WinterItems.Kind.ROE));
        player.sendActionBar("§aВ рыбе нашлась икра! (+1)");
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.3f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        WinterItems.Kind kind = items.kind(event.getItem());
        boolean potion = WinterItems.isRimePotion(kind);
        if (!potion && kind != WinterItems.Kind.RAW && kind != WinterItems.Kind.DEPLETED
                && kind != WinterItems.Kind.SANDWICH) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled() && player.isOnline() && !player.isDead()) {
                if (potion) movement.freezeFor(player, 15 * 20);
                else movement.freeze(player, kind == WinterItems.Kind.SANDWICH);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void splash(PotionSplashEvent event) {
        if (items.kind(event.getPotion().getItem()) != WinterItems.Kind.RIME_POTION_SPLASH) return;
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof Player player) freezePotionTarget(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void lingering(LingeringPotionSplashEvent event) {
        if (items.kind(event.getEntity().getItem()) != WinterItems.Kind.RIME_POTION_LINGERING) return;
        AreaEffectCloud cloud = event.getAreaEffectCloud();
        event.allowsEmptyCreation(true);
        cloud.getPersistentDataContainer().set(rimeCloudKey, PersistentDataType.BYTE, (byte) 1);
        cloud.setWaitTime(0);
        cloud.setDuration(15 * 20);
        cloud.setRadius(3.0f);
        // A one-tick hidden vanilla effect makes the empty cloud produce its standard apply events.
        cloud.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 1, 0, true, false, false), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void lingeringApply(AreaEffectCloudApplyEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(rimeCloudKey, PersistentDataType.BYTE)) return;
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof Player player) freezePotionTarget(player);
        }
    }

    private void freezePotionTarget(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !player.isDead()) movement.freezeFor(player, 15 * 20);
        });
    }

    private void cook(BlockCookEvent event) {
        WinterItems.Kind kind = items.kind(event.getSource());
        if (kind == WinterItems.Kind.RAW || kind == WinterItems.Kind.DEPLETED) {
            ItemStack unchanged = event.getSource().clone(); unchanged.setAmount(1);
            items.refresh(unchanged);
            event.setResult(unchanged);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void furnace(FurnaceSmeltEvent event) { cook(event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void campfire(BlockCookEvent event) { if (!(event instanceof FurnaceSmeltEvent)) cook(event); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void broken(PlayerItemBreakEvent event) {
        ItemStack broken = event.getBrokenItem();
        if (items.kind(broken) != WinterItems.Kind.TOOL) return;
        Player player = event.getPlayer();
        movement.release(player);
        int slot = findSlot(player, broken);
        replacements.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>()).add(new Replacement(slot, items.create(WinterItems.Kind.RAW)));
        // Ваниль уменьшает количество ПОСЛЕ PlayerItemBreakEvent. Замена выполняется после этого.
        Bukkit.getScheduler().runTask(plugin, () -> drain(player));
    }

    private int findSlot(Player player, ItemStack item) {
        var inventory = player.getInventory();
        int main = inventory.getHeldItemSlot();
        if (sameTool(inventory.getItem(main), item)) return main;
        if (sameTool(inventory.getItemInOffHand(), item)) return 40;
        for (int slot = 0; slot < inventory.getSize(); slot++) if (sameTool(inventory.getItem(slot), item)) return slot;
        return -1;
    }

    private boolean sameTool(ItemStack first, ItemStack second) {
        if (items.kind(first) != WinterItems.Kind.TOOL) return false;
        String key = items.toolId(second);
        return key == null ? first.isSimilar(second) : key.equals(items.toolId(first));
    }

    private void drain(Player player) {
        List<Replacement> pending = replacements.remove(player.getUniqueId());
        if (pending == null) return;
        for (Replacement replacement : pending) {
            int slot = replacement.slot();
            ItemStack current = slot >= 0 ? player.getInventory().getItem(slot) : null;
            if (slot >= 0 && (current == null || current.getType().isAir() || current.getAmount() == 0)) {
                player.getInventory().setItem(slot, replacement.fish());
            } else give(player, replacement.fish()); // Не затирать другой предмет, перемещённый в этот слот.
        }
    }

    private void give(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { drain(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) {
        if (event.getKeepInventory()) drain(event.getEntity());
        else {
            List<Replacement> pending = replacements.remove(event.getEntity().getUniqueId());
            if (pending != null) for (Replacement replacement : pending) event.getDrops().add(replacement.fish());
        }
    }

    /** Перечитать настройки зацепа после /f8 reload: числа скольжения живут в памяти. */
    void reloadSettings() {
        movement.reloadSettings();
    }

    void disable() {
        crabClaw.disable();
        movement.disable();
        for (UUID owner : new ArrayList<>(replacements.keySet())) {
            Player player = Bukkit.getPlayer(owner); if (player != null) drain(player);
        }
        replacements.clear();
        recipes.forEach(Bukkit::removeRecipe);
        potionMixes.forEach(Bukkit.getPotionBrewer()::removePotionMix);
    }
}
