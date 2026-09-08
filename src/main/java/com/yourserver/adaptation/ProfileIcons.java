package com.yourserver.adaptation;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Пиктограммы используют PNG ресурспака и прозрачность TextDisplay, без масштабирования при появлении. */
final class ProfileIcons {
    static final Key FONT = Key.key("f8resurs", "profile_ui");
    /** Первый символ глифов префиксов в font/profile_ui.json (файл pref1 = E106 … pref10 = E10F). */
    static final int PREFIX_GLYPH_START = 0xE106;
    /** Сколько глифов префиксов идёт в комплекте ресурспака. */
    static final int PREFIX_GLYPH_COUNT = 10;
    private ProfileIcons() { }

    private static Component glyph(char character) {
        return Component.text(String.valueOf(character), NamedTextColor.WHITE).font(FONT)
                .decoration(TextDecoration.ITALIC, false);
    }

    static Component votes(int likes, int dislikes) {
        return ProfileItems.text(Integer.toString(likes), NamedTextColor.WHITE)
                .append(Component.space()).append(glyph('\uE101'))
                .append(ProfileItems.text("  |  ", NamedTextColor.GRAY))
                .append(ProfileItems.text(Integer.toString(dislikes), NamedTextColor.WHITE))
                .append(Component.space()).append(glyph('\uE102'));
    }

    static Component medal(ProfileMedal.Metal metal) {
        return glyph(switch (metal) {
            case COPPER -> '\uE103';
            case SILVER -> '\uE104';
            case GOLD -> '\uE105';
        });
    }

    /** Картинка префикса: файл prefN из prefixes.yml отвечает глифу N (pref1 → E106 … pref10 → E10F). */
    static Component prefixIcon(PrefixCatalog.Prefix prefix) {
        if (prefix == null) return Component.empty();
        String file = prefix.file();
        for (int i = 1; i <= PREFIX_GLYPH_COUNT; i++) {
            if (("pref" + i).equals(file)) return glyph((char) (PREFIX_GLYPH_START + i - 1));
        }
        return Component.empty();
    }

    /** Иконка префикса перед ником. Сам ник всегда белый и не меняется — префикс не красит имя. */
    static Component prefixedName(PrefixCatalog.Prefix prefix, String name) {
        Component nick = ProfileItems.text(name, NamedTextColor.WHITE);
        if (prefix == null) return nick;
        return prefixIcon(prefix).append(Component.space()).append(nick);
    }

    /** Строка глифов E101–E10F для проверки ресурспака: видны картинки — пакет и шрифт загружены. */
    static Component glyphSample() {
        Component row = Component.empty();
        for (int code = 0xE101; code <= 0xE10F; code++) {
            row = row.append(glyph((char) code)).append(Component.space());
        }
        return row;
    }

    static Component openProfile() {
        return ProfileItems.text("Открыть профиль", NamedTextColor.WHITE).decorate(TextDecoration.UNDERLINED);
    }
}
