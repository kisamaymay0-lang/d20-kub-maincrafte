package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Обычные Minecraft-слоты, серые панели и тёплое золото — как в меню F8. */
final class ProfileItems {
    private final DateTimeFormatter date;
    private final Map<UUID, com.destroystokyo.paper.profile.PlayerProfile> skins = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, com.destroystokyo.paper.profile.PlayerProfile> eldest) {
            return size() > 256;
        }
    };

    ProfileItems(ZoneId zone) { date = DateTimeFormatter.ofPattern("dd.MM.uuuu").withZone(zone); }

    static Component text(String value, TextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    static ItemStack item(Material material, String name, TextColor color, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name, color));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack filler() { return item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of()); }

    ItemStack head(ProfileData data, String title, boolean editHint) {
        List<Component> lore = new ArrayList<>();
        for (String line : ProfileText.wrap(data.displayedDescription(), 34)) lore.add(text(line, NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("Лайки: " + data.likes(), NamedTextColor.GREEN));
        lore.add(text("Дизлайки: " + data.dislikes(), NamedTextColor.RED));
        if (editHint) {
            lore.add(Component.empty());
            lore.add(text("Нажмите, чтобы изменить описание", NamedTextColor.DARK_GRAY));
        }
        ItemStack item = item(Material.PLAYER_HEAD, title, NamedTextColor.GOLD, lore);
        SkullMeta skull = (SkullMeta) item.getItemMeta();
        Player player = Bukkit.getPlayer(data.owner);
        if (player != null) skins.put(data.owner, player.getPlayerProfile());
        var skin = skins.get(data.owner);
        if (skin != null) skull.setPlayerProfile(skin); // Не делаем сетевой поиск при выходе владельца из игры.
        item.setItemMeta(skull);
        return item;
    }

    ItemStack vote(ProfileData.Vote vote, boolean selected, boolean self) {
        boolean like = vote == ProfileData.Vote.LIKE;
        List<Component> lore = new ArrayList<>();
        lore.add(text(self ? "Свой профиль оценивать нельзя" : selected ? "Ваша текущая оценка" : "Одна оценка от каждого игрока", NamedTextColor.GRAY));
        if (!self) lore.add(text(selected ? "Нажмите ещё раз, чтобы снять" : "Другая оценка заменит предыдущую", NamedTextColor.DARK_GRAY));
        ItemStack item = item(like ? Material.GREEN_DYE : Material.RED_DYE,
                like ? "+ Поставить лайк." : "+ Поставить дизлайк.", like ? NamedTextColor.GREEN : NamedTextColor.RED, lore);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(selected);
        item.setItemMeta(meta);
        return item;
    }

    private static TextColor medalColor(ProfileMedal.Metal metal) {
        return switch (metal) {
            case COPPER -> TextColor.color(0xD89465);
            case SILVER -> TextColor.color(0xD8E2EA);
            case GOLD -> NamedTextColor.GOLD;
        };
    }

    private static ItemStack medalBase(ProfileMedal.Metal metal, String title, List<Component> lore) {
        Material material = switch (metal) {
            case COPPER -> Material.COPPER_NUGGET;
            case SILVER -> Material.IRON_NUGGET;
            case GOLD -> Material.GOLD_NUGGET;
        };
        ItemStack item = item(material, title, medalColor(metal), lore);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("f8resurs", metal.model));
        item.setItemMeta(meta);
        return item;
    }

    ItemStack settings(int medals, boolean owner) {
        return medalBase(ProfileMedal.Metal.GOLD, "Настроить медали", List.of(
                text("В коллекции: " + medals, NamedTextColor.GRAY),
                text(owner ? "Выберите медаль и место для неё" : "Настройка доступна только владельцу", NamedTextColor.DARK_GRAY)));
    }

    ItemStack medal(ProfileMedal medal, List<String> hints) {
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < medal.reasons().size(); i++) {
            if (i > 0) lore.add(Component.empty());
            for (String line : ProfileText.wrap(medal.reasons().get(i), 38)) lore.add(text(line, NamedTextColor.WHITE));
        }
        if (!hints.isEmpty()) {
            lore.add(Component.empty());
            for (String hint : hints) lore.add(text(hint, NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.empty());
        // Дата всегда самая нижняя строка, включая экран выбора медалей.
        lore.add(text("Получена: " + date.format(Instant.ofEpochMilli(medal.awardedAt())), NamedTextColor.GRAY));
        return medalBase(medal.metal(), medal.title(), lore);
    }

    ItemStack destination(ProfileMedal previous) {
        return item(Material.WHITE_STAINED_GLASS_PANE, "Поставить медаль", NamedTextColor.WHITE,
                previous == null ? List.of(text("Свободное место", NamedTextColor.GRAY))
                        : List.of(text("Будет заменена: " + previous.title(), NamedTextColor.GRAY),
                        text("Прежняя медаль останется в коллекции", NamedTextColor.DARK_GRAY)));
    }

    ItemStack page(boolean next, int page, int total) {
        return item(Material.ARROW, next ? "Следующая страница" : "Предыдущая страница", NamedTextColor.GOLD,
                List.of(text("Страница " + (page + 1) + " из " + total, NamedTextColor.GRAY)));
    }
}
