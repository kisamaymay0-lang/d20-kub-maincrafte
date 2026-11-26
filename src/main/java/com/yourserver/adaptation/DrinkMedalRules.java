package com.yourserver.adaptation;

import java.util.ArrayList;
import java.util.List;

/**
 * Правила «напиточных» медалей: какой напиток считается алкогольным, что такое вино,
 * совпадают ли id из настроек и из игры, и собраны ли безупречные бутылки всех вин.
 *
 * <p>Здесь нет Bukkit — всё проверяется на каталоге напитков, поэтому правила легко
 * покрыть тестами. События (сделал бутылку, выпил ром в океане, дошёл до предела)
 * приходят из {@link Shaker}, {@link BambooBarrel} и {@link DrunkManager}.
 */
final class DrinkMedalRules {

    private DrinkMedalRules() { }

    /**
     * Алкогольный ли напиток. У шейкера это {@code alcohol > 0}; у бочкового — свой
     * профиль с ненулевой крепостью хоть на одной ступени. Наследованный ромовый
     * профиль алкогольным напиток не делает: иначе «алкогольной» стала бы любая вода
     * из бочки.
     */
    static boolean isAlcoholic(String drinkId) {
        DrinkRecipes.Drink drink = DrinkRecipes.current().get(drinkId);
        if (drink == null) return false;
        if (drink.alcohol() > 0) return true;
        if (drink.method() != DrinkRecipes.Method.BARREL) return false;
        if (!BarrelDrinks.current().has(drink.id())) return false;
        BarrelDrinks.Profile profile = BarrelDrinks.current().of(drink.id());
        for (int tier = 1; tier <= 5; tier++) {
            if (profile.drunkFor(tier) > 0) return true;
        }
        return false;
    }

    /** Вино — бочковой напиток, в id которого есть «wine». Сладкое, белое, своё — любое. */
    static boolean isWine(String drinkId) {
        DrinkRecipes.Drink drink = DrinkRecipes.current().get(drinkId);
        if (drink == null || drink.method() != DrinkRecipes.Method.BARREL) return false;
        return DrinkRecipes.normalizeId(drink.id()).contains("wine");
    }

    /** Ром — бочковой напиток, в id которого есть «rum». */
    static boolean isRum(String drinkId) {
        DrinkRecipes.Drink drink = DrinkRecipes.current().get(drinkId);
        if (drink == null || drink.method() != DrinkRecipes.Method.BARREL) return false;
        return DrinkRecipes.normalizeId(drink.id()).contains("rum");
    }

    /** Все виды вин, которые сейчас есть в настройках. */
    static List<String> wineIds() {
        List<String> ids = new ArrayList<>();
        for (DrinkRecipes.Drink drink : DrinkRecipes.current().barrelDrinks()) {
            if (isWine(drink.id())) ids.add(drink.id());
        }
        return ids;
    }

    /** Маркер «безупречная бутылка такого-то вина» — он живёт в профиле навсегда. */
    static String flawlessWineMarker(String drinkId) {
        return "flawless_wine_" + DrinkRecipes.normalizeId(drinkId);
    }

    /** Собраны ли безупречные бутылки всех вин. Пустой список вин — нет. */
    static boolean allWinesFlawless(ProfileData profile) {
        if (profile == null) return false;
        List<String> ids = wineIds();
        if (ids.isEmpty()) return false;
        for (String id : ids) {
            if (!profile.hasReward(flawlessWineMarker(id))) return false;
        }
        return true;
    }

    /**
     * Совпадают ли id напитка из настроек и из игры. Регистр и дефисы не важны, а одна
     * опечатка в длинном id прощается: в файле легко написать {@code daquiri} вместо
     * {@code daiquiri}.
     */
    static boolean sameDrink(String configured, String actual) {
        if (configured == null || actual == null) return false;
        String left = DrinkRecipes.normalizeId(configured);
        String right = DrinkRecipes.normalizeId(actual);
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.equals(right)) return true;
        if (left.length() < 5 || right.length() < 5) return false;
        return distance(left, right) <= 1;
    }

    /**
     * «Напиток с этим id» — либо сам напиток, либо коктейль, в составе которого он есть.
     * Алкогольную медовуху делают именно так: медовуха плюс ром в шейкере. Условие
     * {@code drink-made} с {@code drink: mead} засчитывает и то, и другое.
     */
    static boolean drinkIncludes(String configured, String madeId) {
        if (sameDrink(configured, madeId)) return true;
        DrinkRecipes.Drink made = DrinkRecipes.current().get(madeId);
        if (made == null) return false;
        for (String base : made.baseDrinks()) {
            if (sameDrink(configured, base)) return true;
        }
        return false;
    }

    /** Расстояние Левенштейна: нужна только для «одна буква мимо». */
    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
