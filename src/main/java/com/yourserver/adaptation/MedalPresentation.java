package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Общие строки предмета и подсказки; без ItemStack и серверных объектов. */
final class MedalPresentation {
    private MedalPresentation() { }

    static List<Component> lore(ProfileMedal medal, MedalSettings settings, DateTimeFormatter date, List<String> hints) {
        List<Component> lines = new ArrayList<>();
        lines.add(ProfileItems.text(settings.style(medal.metal()).label(), NamedTextColor.GRAY));
        lines.add(Component.empty());
        for (int i = 0; i < medal.reasons().size(); i++) {
            if (i > 0) lines.add(Component.empty());
            appendReason(lines, medal.reasons().get(i), 36);
        }
        if (!hints.isEmpty()) {
            lines.add(Component.empty());
            for (String hint : hints) lines.add(ProfileItems.text(hint, NamedTextColor.DARK_GRAY));
        }
        lines.add(Component.empty());
        lines.add(ProfileItems.text("Получена: " + date.format(Instant.ofEpochMilli(medal.awardedAt())), NamedTextColor.GRAY));
        return List.copyOf(lines);
    }

    static List<Component> tooltip(ProfileMedal medal, MedalSettings settings, DateTimeFormatter date) {
        List<Component> lines = new ArrayList<>();
        lines.add(settings.title(medal.title(), medal.metal()));
        lines.add(ProfileItems.text(settings.style(medal.metal()).label(), NamedTextColor.GRAY));
        lines.add(Component.empty());
        int shown = Math.min(3, medal.reasons().size());
        for (int i = 0; i < shown; i++) {
            String reason = medal.reasons().get(i);
            if (ProfileText.length(reason) > 64) reason = reason.substring(0, reason.offsetByCodePoints(0, 63)) + "…";
            appendReason(lines, reason, 24);
        }
        if (medal.reasons().size() > shown) lines.add(ProfileItems.text("Ещё заслуг: " + (medal.reasons().size() - shown) + " · /profile", NamedTextColor.GRAY));
        lines.add(Component.empty());
        lines.add(ProfileItems.text("Получена: " + date.format(Instant.ofEpochMilli(medal.awardedAt())), NamedTextColor.GRAY));
        return List.copyOf(lines);
    }

    private static void appendReason(List<Component> target, String source, int width) {
        String value = ProfileText.clean(source);
        while (value.startsWith("— ") || value.startsWith("– ") || value.startsWith("- ")) value = value.substring(2).stripLeading();
        List<String> wrapped = ProfileText.wrap(value, width - 2);
        for (int i = 0; i < wrapped.size(); i++) target.add(ProfileItems.text((i == 0 ? "— " : "  ") + wrapped.get(i), NamedTextColor.WHITE));
    }
}
