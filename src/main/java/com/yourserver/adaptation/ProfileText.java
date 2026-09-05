package com.yourserver.adaptation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Только простой текст: пользовательский ввод не превращается в форматирование/команды. */
final class ProfileText {
    static final String NO_DESCRIPTION = "Нет описания.";
    static final int DESCRIPTION_LIMIT = 160;
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private ProfileText() { }

    static String clean(String text) {
        if (text == null) return "";
        StringBuilder safe = new StringBuilder();
        LEGACY_COLOR.matcher(text).replaceAll("").codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp)) safe.append(' ');
            else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT && cp != '§') safe.appendCodePoint(cp);
        });
        return SPACES.matcher(safe).replaceAll(" ").trim();
    }

    static int length(String value) { return value.codePointCount(0, value.length()); }

    static List<String> wrap(String text, int columns) {
        if (columns < 1) throw new IllegalArgumentException("columns");
        List<String> lines = new ArrayList<>();
        String remaining = clean(text);
        while (length(remaining) > columns) {
            int cut = remaining.offsetByCodePoints(0, columns);
            int space = remaining.lastIndexOf(' ', cut);
            if (space > 0) cut = space;
            lines.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut).stripLeading();
        }
        if (!remaining.isEmpty() || lines.isEmpty()) lines.add(remaining);
        return List.copyOf(lines);
    }

    static int medalSlot(int inventorySlot) {
        if (inventorySlot >= 0 && inventorySlot < 9) return inventorySlot;
        if (inventorySlot >= 18 && inventorySlot < 27) return inventorySlot - 9;
        return -1;
    }

    static int inventorySlot(int medalSlot) {
        if (medalSlot < 0 || medalSlot >= 18) throw new IllegalArgumentException("medalSlot");
        return medalSlot < 9 ? medalSlot : medalSlot + 9;
    }
}
