package com.yourserver.adaptation;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Короткое уведомление в чат <b>без звука</b>.
 *
 * <h2>Почему это не озвучивается</h2>
 *
 * {@link Audience#sendMessage(Component)} в Adventure — это
 * <b>системное</b> сообщение (section «system messages» в самом интерфейсе):
 * клиент показывает его в чате, но не отвечает звуком уведомления. Звук
 * привязан к сообщениям с типом чата — {@code sendMessage(component, chatType)},
 * который здесь не используется. Плюс плагин сам не играет никаких звуков:
 * у лайка не должно быть «пика», в отличие от клика по кнопке.
 *
 * <h2>Где используется</h2>
 *
 * Лайк и дизлайк чужому профилю: владелец видит в чате, что ему поставили
 * оценку, — зелёной строкой за лайк и красной за дизлайк.
 */
final class ChatNotice {

    private ChatNotice() { }

    /** Отправить сообщение тихо (системное, без звука уведомления). */
    static void silent(Audience target, Component message) {
        target.sendMessage(message);
    }

    /** Тихая строка одним цветом, без курсива. */
    static void silent(Audience target, String message, NamedTextColor color) {
        silent(target, ProfileItems.text(message, color));
    }
}
