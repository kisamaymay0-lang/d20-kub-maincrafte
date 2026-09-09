package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MessageUtils — визуальный помощник плагина.
 *
 * Отвечает ТОЛЬКО за внешний вид:
 *  - все сообщения чата и action bar проходят через MiniMessage
 *    (HEX-цвета, градиенты, <rainbow>, кликабельные/ховер-части);
 *  - legacy-строки (названия предметов, заголовки босс-баров, рамки GUI)
 *    поддерживают &-коды и HEX в формате &#RRGGBB;
 *  - здесь же живут глобальные переключатели эффектов (effects.*),
 *    чтобы выключать партиклы/звуки/босс-бары БЕЗ изменения механик.
 *
 * Никакая игровая логика здесь не меняется: при отсутствии ключа в config.yml
 * используется встроенный запасной текст (тот же, что и в config.yml по умолчанию).
 */
public final class MessageUtils {

    /* Единая палитра сообщений (используется в значениях по умолчанию). */
    public static final String SUCCESS = "#00FF00"; // ✔ Успех
    public static final String ERROR = "#FF4444";   // ✖ Ошибка
    public static final String WARN = "#FFAA00";    // ⚠ Предупреждение
    public static final String INFO = "#8ec5fc";    // информация

    private static JavaPlugin plugin;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** (&#RRGGBB | #RRGGBB) -> §x§R§R§G§G§B§B */
    private static final Pattern HEX_COLOR = Pattern.compile("&?#([0-9a-fA-F]{6})");

    /**
     * Запасные тексты на случай, если в config.yml (старой версии) нет раздела messages.
     * Полные пути. Значения — в формате MiniMessage.
     */
    private static final Map<String, String> FALLBACKS = new HashMap<>();

    static {
        FALLBACKS.put("messages.adaptation.broken",
                "<" + ERROR + ">✖ Бафф чара «Адаптация» был разбит критическим ударом врага!</" + ERROR + ">");
        FALLBACKS.put("messages.adaptation.ended",
                "<" + WARN + ">⚠ Адаптация закончилась! Перезарядка <yellow>{seconds}</yellow> сек.</" + WARN + ">");
        FALLBACKS.put("messages.adaptation.ready",
                "<" + SUCCESS + ">✔ Перезарядка закончилась — адаптация снова доступна!</" + SUCCESS + ">");

        FALLBACKS.put("messages.d20.players-only",
                "<" + ERROR + ">✖ Эту команду может использовать только игрок.</" + ERROR + ">");
        FALLBACKS.put("messages.d20.usage",
                "<" + WARN + ">⚠ Использование /d20:</" + WARN + ">\n"
                        + "<white>•</white> <click:suggest_command:'/d20 give '>"
                        + "<hover:show_text:'<gray>Нажмите, чтобы вставить команду</gray>'><" + INFO + ">/d20 give <gold>«игрок»</gold></" + INFO + "></hover></click>"
                        + " <gray>— выдать книгу «Бросок I»</gray>\n"
                        + "<white>•</white> <click:suggest_command:'/d20 cheat 20'>"
                        + "<hover:show_text:'<gray>Нажмите, чтобы вставить команду</gray>'><" + INFO + ">/d20 cheat <gold>«1-20»</gold></" + INFO + "></hover></click>"
                        + " <gray>— гарантированный результат удара</gray>\n"
                        + "<white>•</white> <click:suggest_command:'/d20 enchant mace'>"
                        + "<hover:show_text:'<gray>Нажмите, чтобы вставить команду</gray>'><" + INFO + ">/d20 enchant <gold>«ID»</gold></" + INFO + "></hover></click>"
                        + " <gray>— зачаровать предмет</gray>");
        FALLBACKS.put("messages.d20.cheat-cooldown",
                "<" + ERROR + ">✖ Подождите <yellow>{seconds}</yellow> сек. перед следующим использованием чита!</" + ERROR + ">");
        FALLBACKS.put("messages.d20.cheat-off",
                "<" + ERROR + ">✖ Чит-режим отключен. Роллы снова случайны.</" + ERROR + ">");
        FALLBACKS.put("messages.d20.cheat-need-number",
                "<" + ERROR + ">✖ Укажите число! Пример: "
                        + "<click:suggest_command:'/d20 cheat 20'><gold>/d20 cheat 20</gold></click></" + ERROR + ">");
        FALLBACKS.put("messages.d20.cheat-range",
                "<" + ERROR + ">✖ Число кубика должно быть строго от 1 до 20!</" + ERROR + ">");
        FALLBACKS.put("messages.d20.cheat-on",
                "<" + SUCCESS + ">✔ Чит-режим активирован! Следующий удар гарантированно выдаст: "
                        + "<yellow><bold>[{roll}]</bold></yellow></" + SUCCESS + ">");
        FALLBACKS.put("messages.d20.cheat-bad-number",
                "<" + ERROR + ">✖ Некорректное число! Пример: "
                        + "<click:suggest_command:'/d20 cheat 7'><gold>/d20 cheat 7</gold></click></" + ERROR + ">");
        FALLBACKS.put("messages.d20.enchant-need-id",
                "<" + ERROR + ">✖ Укажите ID предмета! Пример: "
                        + "<click:suggest_command:'/d20 enchant mace'><gold>/d20 enchant mace</gold></click></" + ERROR + ">");
        FALLBACKS.put("messages.d20.enchant-not-found",
                "<" + ERROR + ">✖ Предмет с ID «{item}» не найден в базе Minecraft!</" + ERROR + ">");
        FALLBACKS.put("messages.d20.enchant-given",
                "<" + SUCCESS + ">✔ Вам успешно выдан предмет <yellow>{item}</yellow> с чаром «Бросок I»!</" + SUCCESS + ">");
        FALLBACKS.put("messages.d20.give-not-found",
                "<" + ERROR + ">✖ Игрок не найден.</" + ERROR + ">");
        FALLBACKS.put("messages.d20.give-given",
                "<" + SUCCESS + ">✔ Книга «Бросок I» выдана игроку <yellow>{player}</yellow></" + SUCCESS + ">");
        FALLBACKS.put("messages.d20.unknown",
                "<" + ERROR + ">✖ Неизвестный аргумент. Используйте <gold>give</gold>, <gold>cheat</gold> "
                        + "или <gold>enchant</gold>.</" + ERROR + ">");
        FALLBACKS.put("messages.d20.buff-cancelled",
                "<" + WARN + ">⚠ Бафф чара «Бросок I» был отменен, так как вы сменили предмет в руке!</" + WARN + ">");
        FALLBACKS.put("messages.d20.time-expired",
                "<" + ERROR + ">✖ Время для удара истекло!</" + ERROR + ">");
        FALLBACKS.put("messages.d20.crit-fail",
                "<" + ERROR + "><bold>✖ КРИТИЧЕСКИЙ ПРОВАЛ!</bold> Текущий удар нанес 0 урона.</" + ERROR + ">");
        FALLBACKS.put("messages.d20.divine",
                "<gradient:#FFD700:#FF8C00><bold>✦ БОЖЕСТВЕННОЕ ВЕЗЕНИЕ!</bold></gradient> "
                        + "<white>Мощная взрывная волна откинула врага: Скорость II и урон <yellow>×4.0</yellow>!</white>");

        FALLBACKS.put("messages.flask.no-perm",
                "<" + ERROR + ">✖ У вас нет прав на использование этой команды!</" + ERROR + ">");
        FALLBACKS.put("messages.flask.usage",
                "<" + WARN + ">⚠ Использование: "
                        + "<click:suggest_command:'/flask give '><hover:show_text:'<gray>Нажмите, чтобы вставить команду</gray>'><" + INFO + ">/flask give «игрок» «water/poison» «количество»</" + INFO + "></hover></click></" + WARN + ">");
        FALLBACKS.put("messages.flask.unknown-sub",
                "<" + ERROR + ">✖ Неизвестная подкоманда. Используйте: /flask give «игрок» «water/poison»</" + ERROR + ">");
        FALLBACKS.put("messages.flask.player-not-found",
                "<" + ERROR + ">✖ Игрок не найден или оффлайн!</" + ERROR + ">");
        FALLBACKS.put("messages.flask.bad-type",
                "<" + ERROR + ">✖ Тип флакона должен быть 'water' или 'poison'!</" + ERROR + ">");
        FALLBACKS.put("messages.flask.bad-amount",
                "<" + ERROR + ">✖ Количество должно быть от 1 до 64!</" + ERROR + ">");
        FALLBACKS.put("messages.flask.bad-number",
                "<" + ERROR + ">✖ Некорректное количество!</" + ERROR + ">");
        FALLBACKS.put("messages.flask.given",
                "<" + SUCCESS + ">✔ Флакон <yellow>{type}</yellow> выдан игроку <yellow>{player}</yellow> "
                        + "в количестве <yellow>{amount}</yellow></" + SUCCESS + ">");
        FALLBACKS.put("messages.flask.give-fail",
                "<" + ERROR + ">✖ Не удалось выдать флакон!</" + ERROR + ">");
        FALLBACKS.put("messages.flask.poison-timer",
                "<gray>☠ Отравление: <white>{time}</white></gray>");

        FALLBACKS.put("messages.rollback.no-perm",
                "<" + ERROR + ">✖ У вас нет прав на использование этой команды!</" + ERROR + ">");
        FALLBACKS.put("messages.rollback.usage",
                "<" + WARN + ">⚠ Использование: "
                        + "<click:suggest_command:'/rollback give '><" + INFO + ">/rollback give «игрок»</" + INFO + "></click></" + WARN + ">");
        FALLBACKS.put("messages.rollback.give-usage",
                "<" + WARN + ">⚠ Укажите имя игрока! Пример: "
                        + "<click:suggest_command:'/rollback give '><gold>/rollback give PlayerName</gold></click></" + WARN + ">");
        FALLBACKS.put("messages.rollback.player-not-found",
                "<" + ERROR + ">✖ Игрок не найден или оффлайн!</" + ERROR + ">");
        FALLBACKS.put("messages.rollback.given",
                "<" + SUCCESS + ">✔ Тотем бессмертия выдан игроку <yellow>{player}</yellow></" + SUCCESS + ">");
        FALLBACKS.put("messages.rollback.give-fail",
                "<" + ERROR + ">✖ Не удалось выдать тотем!</" + ERROR + ">");
        FALLBACKS.put("messages.rollback.unknown-sub",
                "<" + ERROR + ">✖ Неизвестная подкоманда. Используйте: /rollback give «игрок»</" + ERROR + ">");

        FALLBACKS.put("messages.menu.given",
                "<" + SUCCESS + ">✔ {item} — в вашем инвентаре!</" + SUCCESS + ">");
    }

    private MessageUtils() {
    }

    /** Вызывается в onEnable() ДО регистрации слушателей. */
    public static void init(JavaPlugin instance) {
        plugin = instance;
    }

    /* ===================== Доступ к конфигу ===================== */

    private static String cfgString(String path, String fallback) {
        if (plugin == null) {
            return fallback;
        }
        String value = plugin.getConfig().getString(path);
        return value != null ? value : fallback;
    }

    private static boolean cfgBool(String path, boolean fallback) {
        if (plugin == null) {
            return fallback;
        }
        return plugin.getConfig().getBoolean(path, fallback);
    }

    /* ===================== Чат (MiniMessage) ===================== */

    /** Сырой текст сообщения из messages.* (с подстановкой {token}). */
    public static String raw(String key, String... replacements) {
        String fallback = FALLBACKS.getOrDefault("messages." + key, "");
        String text = cfgString("messages." + key, fallback);
        return applyReplacements(text, replacements);
    }

    /** MiniMessage-компонент сообщения messages.{key}. */
    public static Component msg(String key, String... replacements) {
        return parse(raw(key, replacements));
    }

    /**
     * Распарсить произвольную MiniMessage-строку (с подстановкой {token}).
     * При ошибке разметки в конфиге возвращает обычный текст без цветов —
     * плагин никогда не падает из-за опечатки в messages.
     */
    public static Component parse(String mini, String... replacements) {
        String text = applyReplacements(mini, replacements);
        try {
            return MINI_MESSAGE.deserialize(text);
        } catch (Exception ignored) {
            return Component.text(
                    org.bukkit.ChatColor.stripColor(text)
            );
        }
    }

    /** Отправить сообщение любому CommandSender (игрок/консоль) с цветами MiniMessage. */
    public static void send(CommandSender target, String key, String... replacements) {
        if (target == null) {
            return;
        }
        target.sendMessage(msg(key, replacements));
    }

    /** Отправить сообщение в action bar (игрок). */
    public static void action(Player player, String key, String... replacements) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendActionBar(msg(key, replacements));
    }

    /* ===================== Legacy-строки (GUI, босс-бары, предметы) ===================== */

    /**
     * Переводит строку с '&'-кодами и HEX '&#RRGGBB' в legacy-формат '§',
     * который понимают заголовки инвентарей, босс-баров и названия предметов.
     */
    public static String legacy(String text, String... replacements) {
        if (text == null) {
            return "";
        }
        text = applyReplacements(text, replacements);

        Matcher matcher = HEX_COLOR.matcher(text);
        StringBuilder builder = new StringBuilder(text.length() + 16);

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder legacyHex = new StringBuilder("§x");
            for (char c : hex.toLowerCase().toCharArray()) {
                legacyHex.append('§').append(c);
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(legacyHex.toString()));
        }
        matcher.appendTail(builder);

        return org.bukkit.ChatColor.translateAlternateColorCodes('&', builder.toString());
    }

    /** Legacy-строка из gui.* конфига с подстановкой {token}. */
    public static String legacyKey(String key, String fallback, String... replacements) {
        return legacy(cfgString(key, fallback), replacements);
    }

    /* ===================== Глобальные переключатели эффектов ===================== */

    /** Партиклы (effects.particles). */
    public static boolean particles() {
        return cfgBool("effects.particles", true);
    }

    /** Звуки (effects.sounds). */
    public static boolean sounds() {
        return cfgBool("effects.sounds", true);
    }

    /** Босс-бары (effects.bossbar). */
    public static boolean bossBars() {
        return cfgBool("effects.bossbar", true);
    }

    /** Action bar (effects.actionbar). */
    public static boolean actionBars() {
        return cfgBool("effects.actionbar", true);
    }

    /** Произвольный булевый флаг из конфига. */
    public static boolean bool(String path, boolean fallback) {
        return cfgBool(path, fallback);
    }

    /* ===================== Внутреннее ===================== */

    private static String applyReplacements(String text, String... replacements) {
        if (replacements == null || replacements.length == 0 || text == null) {
            return text;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String token = replacements[i];
            String value = replacements[i + 1] == null ? "" : replacements[i + 1];
            text = text.replace(token, value);
        }
        return text;
    }
}
