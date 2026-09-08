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
    private MedalSettings medals;
    private long styleRevision;
    private final Map<UUID, com.destroystokyo.paper.profile.PlayerProfile> skins = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, com.destroystokyo.paper.profile.PlayerProfile> eldest) {
            return size() > 256;
        }
    };

    ProfileItems(ZoneId zone) { this(zone, MedalSettings.defaults()); }
    ProfileItems(ZoneId zone, MedalSettings settings) {
        date = DateTimeFormatter.ofPattern("dd.MM.uuuu").withZone(zone);
        medals = settings;
    }
    void settings(MedalSettings settings) { medals = settings; styleRevision++; }
    long styleRevision() { return styleRevision; }
    List<Component> tooltip(ProfileMedal medal) { return MedalPresentation.tooltip(medal, medals, date); }

    static Component text(String value, TextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    static Component bold(String value, TextColor color) {
        return Component.text(value, color).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(Material material, Component display, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(display);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack model(ItemStack item, String file) {
        if (file == null || file.isEmpty()) return item;
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("f8resurs", file));
        item.setItemMeta(meta);
        return item;
    }

    /** Кнопка «Настроить префикс»: случайная иконка префикса при каждом открытии профиля. */
    ItemStack prefixButton(PrefixCatalog.Prefix icon, PrefixCatalog.Prefix equipped, boolean owner) {
        List<Component> lore = new ArrayList<>();
        if (equipped != null) lore.add(text("Текущий: ", NamedTextColor.GRAY).append(text(equipped.name(), equipped.color())));
        else lore.add(text("Префикс не выбран", NamedTextColor.DARK_GRAY));
        lore.add(text(owner ? "Выберите префикс или откройте кейс" : "Настраивать можно только свой профиль", NamedTextColor.DARK_GRAY));
        return model(named(Material.NAME_TAG, medals.title("Настроить префикс", ProfileMedal.Metal.GOLD), lore),
                icon == null ? null : icon.file());
    }

    /** Префикс в списке выбора. Чужой показан красным «У вас нету этого префикса!». */
    ItemStack prefixEntry(PrefixCatalog.Prefix prefix, boolean owned, boolean equipped) {
        if (!owned) {
            ItemStack item = named(Material.NAME_TAG, bold("У вас нету этого префикса!", NamedTextColor.RED), List.of(
                    text("Префикс: ", NamedTextColor.GRAY).append(text(prefix.name(), prefix.color())),
                    text("Получите его из кейса префиксов", NamedTextColor.DARK_GRAY)));
            return model(item, prefix.file());
        }
        List<Component> lore = new ArrayList<>();
        if (equipped) {
            lore.add(text("Выбран. Shift + клик — снять префикс", NamedTextColor.DARK_GRAY));
        } else {
            lore.add(text("Нажмите, чтобы надеть префикс", NamedTextColor.DARK_GRAY));
        }
        lore.add(text("Отображается перед ником", NamedTextColor.GRAY));
        ItemStack item = named(Material.NAME_TAG, bold(prefix.name(), prefix.color()), lore);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(equipped);
        item.setItemMeta(meta);
        return model(item, prefix.file());
    }

    /** Кнопка «Кейс префиксов»: показывает число кейсов у игрока. */
    ItemStack prefixCase(int cases) {
        String count = cases == 1 ? "Есть 1 кейс префиксов" : "Есть " + cases + " кейсов префиксов";
        ItemStack item = named(Material.CHEST, medals.title("Кейс префиксов", ProfileMedal.Metal.GOLD), List.of(
                text(count, cases > 0 ? NamedTextColor.GRAY : NamedTextColor.RED),
                text(cases > 0 ? "Нажмите, чтобы открыть" : "Кейсы выдаёт администратор", NamedTextColor.DARK_GRAY)));
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(cases > 0);
        item.setItemMeta(meta);
        return item;
    }

    /** Карточка префикса во время вскрытия кейса (5 → 1). */
    ItemStack prefixReveal(PrefixCatalog.Prefix prefix) {
        return model(named(Material.NAME_TAG, bold(prefix.name(), prefix.color()), List.of(
                text("Вскрытие кейса…", NamedTextColor.DARK_GRAY))), prefix.file());
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
        if (title.equals(data.name())) skull.displayName(text(title, NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
        Player player = Bukkit.getPlayer(data.skinOwner());
        if (player != null) skins.put(data.skinOwner(), player.getPlayerProfile());
        var skin = skins.get(data.skinOwner());
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

    private ItemStack medalBase(ProfileMedal.Metal metal, String title, List<Component> lore) {
        Material material = switch (metal) {
            case COPPER -> Material.COPPER_NUGGET;
            case SILVER -> Material.IRON_NUGGET;
            case GOLD -> Material.GOLD_NUGGET;
        };
        ItemStack item = item(material, title, NamedTextColor.WHITE, lore);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(medals.title(title, metal));
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
        return medalBase(medal.metal(), medal.title(), MedalPresentation.lore(medal, medals, date, hints));
    }

    ItemStack publicMedal(ProfileMedal medal) {
        return medalBase(medal.metal(), medal.title(), MedalPresentation.publicLore(medal, medals, date));
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
