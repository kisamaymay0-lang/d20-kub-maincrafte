package com.yourserver.adaptation;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import org.bukkit.inventory.RecipeChoice;
import java.util.ArrayList;
import java.util.Objects;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/** Предметы используют настоящие инструмент/еду и независимый от названия маркер. */
final class WinterItems {
    enum Kind {
        TOOL("icy_rime", "Заледеневшая изморозь", Material.DIAMOND_PICKAXE),
        RAW("rime", "Изморозь", Material.COD),
        DEPLETED("depleted_rime", "Опустошённая изморозь", Material.COD),
        ROE("ice_caviar", "Ледяная икра", Material.LIGHT_BLUE_DYE),
        SANDWICH("ice_caviar_sandwich", "Бутерброд с ледяной икрой", Material.BREAD);
        final String id, title;
        final Material material;
        Kind(String id, String title, Material material) { this.id = id; this.title = title; this.material = material; }
    }
    private final NamespacedKey kindKey;
    private final NamespacedKey toolKey;
    /** Своя группа кулдауна: перезарядка касается только изморози, а не всех алмазных кирок. */
    private final NamespacedKey cooldownKey;

    WinterItems(JavaPlugin plugin) {
        kindKey = new NamespacedKey(plugin, "winter_item");
        toolKey = new NamespacedKey(plugin, "winter_tool_id");
        cooldownKey = new NamespacedKey(plugin, "rime_cooldown");
    }

    ItemStack create(Kind kind) {
        ItemStack item = new ItemStack(kind.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(kind.title, WinterRules.titleColor(kind.name())));
        String description = description(kind);
        meta.lore(List.of(ProfileItems.text(description, NamedTextColor.GRAY)));
        meta.setItemModel(new NamespacedKey("f8resurs", kind.id));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind.name());
        if (kind == Kind.TOOL) {
            Damageable damage = (Damageable) meta;
            damage.setMaxDamage(WinterRules.DURABILITY); damage.setDamage(0);
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        } else if (kind == Kind.RAW || kind == Kind.DEPLETED || kind == Kind.SANDWICH) {
            var food = meta.getFood();
            food.setNutrition(kind == Kind.SANDWICH ? 6 : 2);
            food.setSaturation(kind == Kind.SANDWICH ? 14.4f : 0.4f);
            food.setCanAlwaysEat(false); meta.setFood(food);
        }
        item.setItemMeta(meta);
        if (kind == Kind.TOOL) {
            item.unsetData(DataComponentTypes.ENCHANTABLE);
            item.setData(DataComponentTypes.USE_COOLDOWN, cooldown());
        }
        return item;
    }

    private static String description(Kind kind) {
        return switch (kind) {
            case TOOL -> "Особый предмет рыбалки в зимних биомах.";
            case RAW, DEPLETED -> "Рыба с неведомых земель.";
            case ROE -> "Добывается из Изморози (shift + ПКМ)";
            case SANDWICH -> "Сытный, как золотая морковка";
        };
    }

    /** Прежнее описание изморози: его тоже заменяем на актуальное, чтобы вещи обновились. */
    private static String legacyDescription(Kind kind) {
        return kind == Kind.TOOL ? "Особый предмет рыбалки в зимних биомах." : null;
    }

    /** Описания изморози, которые когда-то выдавала механика зацепа: их возвращаем к прежнему. */
    private static final java.util.Set<String> RIME_LORE = java.util.Set.of(
            "ПКМ по стене в падении — зацеп, двойной присед — прыжок от стены.",
            "ПКМ по стене в падении — зацеп, Shift + пробел — прыжок от стены.");

    private static boolean defaultLore(Kind kind, String plain) {
        return plain.equals(description(kind)) || plain.equals(legacyDescription(kind))
                || (kind == Kind.TOOL && RIME_LORE.contains(plain))
                || (kind == Kind.ROE && plain.equals("Добывается из Изморози"));
    }

    RecipeChoice.ExactChoice recipeInput(Kind kind) {
        ItemStack current = create(kind);
        ItemStack legacy = current.clone();
        ItemMeta meta = legacy.getItemMeta();
        meta.displayName(ProfileItems.text(kind.title, NamedTextColor.WHITE));
        meta.lore(List.of(ProfileItems.text(kind == Kind.ROE ? "Добывается из Изморози" : description(kind), NamedTextColor.GRAY)));
        legacy.setItemMeta(meta);
        return new RecipeChoice.ExactChoice(List.of(current, legacy));
    }

    static boolean rune(String text) {
        String plain = ProfileText.clean(text);
        return plain.startsWith("Бросок ") || plain.startsWith("Адаптация ") || plain.startsWith("Откат ");
    }

    /** Обновление прежних вещей без сброса прочности, UUID и пользовательского имени. */
    boolean refresh(ItemStack item) {
        Kind kind = kind(item);
        if (kind == null) return false;
        ItemMeta meta = item.getItemMeta();
        ItemMeta before = meta.clone();
        String name = meta.displayName() == null ? kind.title : PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        if (kind == Kind.TOOL && name.equals("Ледяная изморозь")) name = kind.title;
        meta.displayName(ProfileItems.text(name, WinterRules.titleColor(kind.name())));
        if (kind == Kind.TOOL) {
            for (var enchantment : new ArrayList<>(meta.getEnchants().keySet())) meta.removeEnchant(enchantment);
            if (meta.lore() != null) meta.lore(meta.lore().stream()
                    .filter(line -> !rune(PlainTextComponentSerializer.plainText().serialize(line))).toList());
            if (meta.hasEnchantmentGlintOverride() && meta.getEnchantmentGlintOverride()) meta.setEnchantmentGlintOverride(false);
        }
        var lore = meta.lore();
        if (lore == null || lore.isEmpty()
                || (lore.size() == 1 && defaultLore(kind, PlainTextComponentSerializer.plainText().serialize(lore.getFirst())))) {
            meta.lore(List.of(ProfileItems.text(description(kind), NamedTextColor.GRAY)));
        }
        boolean changed = !Objects.equals(before, meta);
        if (changed) item.setItemMeta(meta);
        if (kind == Kind.TOOL && item.hasData(DataComponentTypes.ENCHANTABLE)) {
            item.unsetData(DataComponentTypes.ENCHANTABLE); changed = true;
        }
        // Старым изморозям без группы кулдауна дописываем её, чтобы перезарядка не белила
        // обычные алмазные кирки в инвентаре.
        if (kind == Kind.TOOL && !item.hasData(DataComponentTypes.USE_COOLDOWN)) {
            item.setData(DataComponentTypes.USE_COOLDOWN, cooldown()); changed = true;
        }
        return changed;
    }

    /** Кулдаун самой изморози: секунды ванильного использования нам не нужны — время задаёт
     *  плагин (player.setCooldown), а группа нужна, чтобы белый таймер был только на изморози. */
    private UseCooldown cooldown() {
        return UseCooldown.useCooldown(0.1f).cooldownGroup(cooldownKey).build();
    }

    NamespacedKey cooldownGroup() { return cooldownKey; }

    Kind kind(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String value = item.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        if (value == null) return null;
        try { return Kind.valueOf(value); } catch (IllegalArgumentException ex) { return null; }
    }

    String toolId(ItemStack item) {
        return item == null ? null : item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    boolean holdsTool(Player player) { return kind(player.getInventory().getItemInMainHand()) == Kind.TOOL; }

    /** Расход прочности изморози: 4 за прыжок от стены, по 1 за блок быстрого скольжения.
     *  В креативе инструмент не изнашивается, как и любая обычная кирка.
     *  Возвращает false, если инструмент стёрся до конца (или его нет в руке). */
    boolean useClimb(Player player, int amount) {
        if (amount <= 0) return true;
        if (player.getGameMode() == GameMode.CREATIVE) return true;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (kind(held) != Kind.TOOL) return false;
        refresh(held);
        if (!(held.getItemMeta() instanceof Damageable meta)) return false;
        int damage = WinterRules.afterUse(meta.getDamage(), amount);
        if (WinterRules.broken(damage)) {
            player.getInventory().setItemInMainHand(create(Kind.RAW));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);
            return false;
        }
        meta.setDamage(damage); held.setItemMeta(meta);
        return true;
    }
}
