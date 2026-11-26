package com.yourserver.adaptation;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

/** Пиктограммы используют PNG ресурспака и прозрачность TextDisplay, без масштабирования при появлении. */
final class ProfileIcons {
    static final Key FONT = Key.key("f8resurs", "profile_ui");
    /** Первый символ глифов префиксов в font/profile_ui.json (файл pref1 = E106 … pref10 = E10F). */
    static final int PREFIX_GLYPH_START = 0xE106;
    /** Сколько глифов префиксов идёт в комплекте ресурспака (в font/profile_ui.json). */
    static final int PREFIX_GLYPH_COUNT = 10;
    /** Дальше этого номера глиф не ищем: в шрифте столько символов не бывает. */
    private static final int MAX_GLYPH_NUMBER = 120;
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

    /**
     * Картинка префикса на сейчас: у анимированного берётся тот кадр, который
     * должен идти по времени, у обычного — единственный {@code file}.
     */
    static Component prefixIcon(PrefixCatalog.Prefix prefix) {
        return prefixIcon(prefix, System.currentTimeMillis());
    }

    /** Картинка префикса в заданный момент (миллисекунды от эпохи) — так анимацию видят тесты. */
    static Component prefixIcon(PrefixCatalog.Prefix prefix, long millis) {
        if (prefix == null) return Component.empty();
        List<String> frames = prefix.iconFrames();
        return icon(frames.get(frameIndex(prefix, millis)));
    }

    /** Какой кадр анимации показывать: 0 у статичной иконки, иначе по кругу. */
    static int frameIndex(PrefixCatalog.Prefix prefix, long millis) {
        if (prefix == null) return 0;
        int count = prefix.iconFrames().size();
        if (count < 2) return 0;
        long step = prefix.frameMillis();
        long ticks = Math.floorDiv(Math.max(0L, millis), step);
        return (int) Math.floorMod(ticks, (long) count);
    }

    /**
     * Картинка по имени файла: {@code prefN} отвечает глифу {@code E106 + N - 1}
     * (pref1 → E106 … pref10 → E10F). Так работают и свои кадры: добавили
     * {@code pref11.png} и строку в font/profile_ui.json — глиф найдётся сам.
     */
    static Component icon(String file) {
        int number = PrefixCatalog.glyphNumber(file);

        if (number < 1 || number > MAX_GLYPH_NUMBER) return Component.empty();

        return glyph((char) (PREFIX_GLYPH_START + number - 1));
    }

    /** Иконка префикса, пробел и ник: {@code (префикс) Ник}. Сам ник всегда белый обычным шрифтом —
     *  префикс его не меняет. Шрифт иконки ставится только на саму иконку: если его унаследуют
     *  буквы ника, клиент покажет их пустыми квадратами (в шрифте иконок нет букв). */
    static Component prefixedName(PrefixCatalog.Prefix prefix, String name) {
        return prefixedName(prefix, name, System.currentTimeMillis());
    }

    /** Ник с картинкой префикса в заданный момент времени. */
    static Component prefixedName(PrefixCatalog.Prefix prefix, String name, long millis) {
        if (prefix == null) return ProfileItems.nick(name);
        return withIcon(prefix, millis, ProfileItems.nick(name));
    }

    /** Иконка префикса, пробел и текст: {@code (префикс) Название}. Пробел — часть отступа
     *  между картинкой и буквами, без него ник липнет к иконке. */
    static Component withIcon(PrefixCatalog.Prefix prefix, Component tail) {
        return withIcon(prefix, System.currentTimeMillis(), tail);
    }

    /** То же в заданный момент времени (анимация префикса). */
    static Component withIcon(PrefixCatalog.Prefix prefix, long millis, Component tail) {
        if (prefix == null) return tail;
        return Component.empty().append(prefixIcon(prefix, millis)).append(Component.space()).append(tail);
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
