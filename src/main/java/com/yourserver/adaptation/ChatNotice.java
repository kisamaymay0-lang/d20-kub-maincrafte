package com.yourserver.adaptation;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Короткое уведомление в чат <b>без звука</b>.
 *
 * <h2>Почему обычного sendMessage мало</h2>
 *
 * Обычное сообщение уходит клиентом как «чат»: у игрока в настройках
 * (Звуки и музыка → Уведомления → Сообщения чата) на него отвечает звук.
 * Системные сообщения ({@code MessageType.SYSTEM}) этим звуком не
 * озвучиваются — они нужны именно для тихих уведомлений плагина.
 *
 * <h2>Где это используется</h2>
 *
 * Лайк и дизлайк чужому профилю: владелец профиля видит в чате, что ему
 * поставили оценку, а звука нет — оценка не должна «пикать» среди игры.
 */
final class ChatNotice {

    private ChatNotice() { }

    /** Отправить сообщение тихо: цвет задаёт вызывающий. */
    static void silent(Audience target, Component message) {
        target.sendMessage(Identity.nil(), message, MessageType.SYSTEM);
    }

    /** Тихая строка одним цветом, без курсива. */
    static void silent(Audience target, String message, NamedTextColor color) {
        silent(target, ProfileItems.text(message, color));
    }
}
