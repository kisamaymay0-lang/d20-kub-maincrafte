package com.yourserver.adaptation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Шаблоны биомов из конфига: «*» — любые символы, «?» — один символ.
 *
 * Нужно, чтобы одной строкой закрыть целое семейство биомов: «*:desert» — все
 * пустыни, «*:snowy*» — все снежные, «*:*frozen*» — все мёрзлые. «*» в начале
 * заодно покрывает биомы модов: у них свой namespace вместо minecraft.
 */
final class BiomePatterns {

    private BiomePatterns() { }

    /** Это шаблон (есть «*» или «?») или точный ID биома? */
    static boolean isPattern(String key) {
        return key != null && (key.indexOf('*') >= 0 || key.indexOf('?') >= 0);
    }

    /** Совпадает ли ID биома с шаблоном; регистр не важен. */
    static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) return false;
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '*') regex.append(".*");
            else if (c == '?') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return value.toLowerCase(Locale.ROOT).matches(regex.toString());
    }
}
