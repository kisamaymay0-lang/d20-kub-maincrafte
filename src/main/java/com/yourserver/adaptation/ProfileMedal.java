package com.yourserver.adaptation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Медаль принадлежит профилю, а не физическому ItemStack: GUI не может её размножить. */
record ProfileMedal(UUID id, Metal metal, String title, List<String> reasons, long awardedAt, String source) {
    static final String FIRST_CONSTELLATION = "first_constellation_9_3";

    enum Metal {
        COPPER("Медная медаль", "medal_copper"),
        SILVER("Серебряная медаль", "medal_silver"),
        GOLD("Золотая медаль", "medal_gold");

        final String title;
        final String model;
        Metal(String title, String model) { this.title = title; this.model = model; }

        static Metal parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "copper", "медь", "медная" -> COPPER;
                case "silver", "серебро", "серебряная" -> SILVER;
                case "gold", "золото", "золотая" -> GOLD;
                default -> throw new IllegalArgumentException("Металл: copper, silver или gold");
            };
        }
    }

    ProfileMedal {
        Objects.requireNonNull(id);
        Objects.requireNonNull(metal);
        title = ProfileText.clean(title);
        if (title.isEmpty() || ProfileText.length(title) > 48) throw new IllegalArgumentException("Название: 1–48 символов");
        reasons = reasons.stream().map(ProfileText::clean).toList();
        if (reasons.isEmpty() || reasons.size() > 8
                || reasons.stream().anyMatch(reason -> reason.isEmpty() || ProfileText.length(reason) > 120)) {
            throw new IllegalArgumentException("Нужно 1–8 заслуг, каждая до 120 символов");
        }
        if (awardedAt < 0) throw new IllegalArgumentException("Некорректная дата медали");
        source = Objects.requireNonNullElse(source, "");
    }
}
