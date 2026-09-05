package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Неизменяемые настройки оформления и сообщений из medals/config.yml. */
final class MedalSettings {
    record Style(String label, String rarity, TextColor end) { }
    private final Map<ProfileMedal.Metal, Style> styles;
    private final Map<String, String> messages;
    final String astronomyTitle;
    final List<String> astronomyReasons;

    private MedalSettings(Map<ProfileMedal.Metal, Style> styles, Map<String, String> messages, String title, List<String> reasons) {
        this.styles = Map.copyOf(styles); this.messages = Map.copyOf(messages);
        astronomyTitle = title; astronomyReasons = List.copyOf(reasons);
    }

    static MedalSettings defaults() {
        Map<ProfileMedal.Metal, Style> styles = new EnumMap<>(ProfileMedal.Metal.class);
        styles.put(ProfileMedal.Metal.COPPER, new Style("Медная медаль", "Медную медаль", TextColor.color(0xCB8754)));
        styles.put(ProfileMedal.Metal.SILVER, new Style("Серебряная медаль", "Серебряную медаль", TextColor.color(0x71879F)));
        styles.put(ProfileMedal.Metal.GOLD, new Style("Золотая медаль", "Золотую медаль", TextColor.color(0xE6B94B)));
        Map<String, String> messages = new HashMap<>();
        messages.put("public", "{player} получил <green>[{rarity}]</green>!");
        messages.put("personal", "Вы получили новую медаль! Подробнее /profile.");
        messages.put("given", "<gray>Медаль «{title}» добавлена игроку {player}.</gray>");
        messages.put("taken", "<gray>У игрока {player} забрано медалей: {count}.</gray>");
        messages.put("list", "<gold>Медали игрока {player}:</gold>");
        messages.put("not-found", "<red>Медаль не найдена. Посмотрите /profile medal list.</red>");
        messages.put("reloaded", "<green>Настройки и медали перезагружены.</green>");
        messages.put("error", "<red>Изменение медалей не применено: {error}</red>");
        messages.put("pending-error", "<red>Медаль пока не удалось записать. Право на неё сохранено; обратитесь к администратору.</red>");
        messages.put("no-permission", "<red>Нет прав управлять медалями.</red>");
        return new MedalSettings(styles, messages, "Астрономия!", List.of("Собрано 1 созвездие."));
    }

    static MedalSettings load(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        MedalSettings defaults = defaults();
        Map<ProfileMedal.Metal, Style> styles = new EnumMap<>(ProfileMedal.Metal.class);
        for (ProfileMedal.Metal metal : ProfileMedal.Metal.values()) {
            String key = "types." + metal.name().toLowerCase(java.util.Locale.ROOT);
            Style fallback = defaults.style(metal);
            TextColor color = TextColor.fromHexString(yaml.getString(key + ".gradient-end", fallback.end().asHexString()));
            if (color == null) throw new IllegalArgumentException("Неверный цвет " + key);
            String label = ProfileText.clean(yaml.getString(key + ".label", fallback.label()));
            String rarity = ProfileText.clean(yaml.getString(key + ".rarity", fallback.rarity()));
            if (label.isEmpty() || rarity.isEmpty()) throw new IllegalArgumentException("Не заполнено название " + key);
            styles.put(metal, new Style(label, rarity, color));
        }
        Map<String, String> messages = new HashMap<>(defaults.messages);
        for (String name : messages.keySet()) messages.put(name, yaml.getString("messages." + name, messages.get(name)));
        String title = ProfileText.clean(yaml.getString("astronomy.title", defaults.astronomyTitle));
        List<String> reasons = yaml.isList("astronomy.reasons") ? yaml.getStringList("astronomy.reasons") : defaults.astronomyReasons;
        // Те же ограничения, что у выдаваемой медали; неправильный конфиг не заменит действующий.
        ProfileMedal validation = new ProfileMedal(java.util.UUID.randomUUID(), ProfileMedal.Metal.COPPER, title, reasons, 0, "");
        MedalSettings settings = new MedalSettings(styles, messages, validation.title(), validation.reasons());
        for (String key : messages.keySet()) settings.message(key, "Player", "Медную медаль", "Медаль", 1, "Ошибка");
        return settings;
    }

    Style style(ProfileMedal.Metal metal) { return styles.get(metal); }

    Component title(String text, ProfileMedal.Metal metal) {
        int[] points = ProfileText.clean(text).codePoints().toArray();
        Component result = Component.empty().decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
        TextColor end = style(metal).end();
        for (int i = 0; i < points.length; i++) {
            float t = points.length <= 1 ? 0 : (float) i / (points.length - 1);
            result = result.append(Component.text(new String(Character.toChars(points[i])), TextColor.lerp(t, NamedTextColor.WHITE, end))
                    .decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        return result;
    }

    Component message(String key, String player, String rarity, String title, int count, String error) {
        String template = messages.getOrDefault(key, "");
        for (String placeholder : List.of("player", "rarity", "title", "count", "error")) template = template.replace("{" + placeholder + "}", "<" + placeholder + ">");
        return MiniMessage.miniMessage().deserialize(template,
                Placeholder.unparsed("player", player), Placeholder.unparsed("rarity", rarity),
                Placeholder.unparsed("title", title), Placeholder.unparsed("count", Integer.toString(count)), Placeholder.unparsed("error", error));
    }

    ProfileMedal migrate(ProfileMedal medal) {
        if (medal.source().equals(ProfileMedal.FIRST_CONSTELLATION) && medal.title().equals("Медная медаль")) {
            return new ProfileMedal(medal.id(), medal.metal(), astronomyTitle, astronomyReasons, medal.awardedAt(), medal.source());
        }
        return medal;
    }
}
