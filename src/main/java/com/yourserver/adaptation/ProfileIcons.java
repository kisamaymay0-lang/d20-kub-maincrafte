package com.yourserver.adaptation;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Пиктограммы используют PNG ресурспака и прозрачность TextDisplay, без масштабирования при появлении. */
final class ProfileIcons {
    static final Key FONT = Key.key("f8resurs", "profile_ui");
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

    static Component openProfile() {
        return ProfileItems.text("Открыть профиль", NamedTextColor.WHITE).decorate(TextDecoration.UNDERLINED);
    }
}
