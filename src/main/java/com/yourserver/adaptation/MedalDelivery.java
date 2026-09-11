package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.function.Consumer;

/** Единый порядок уведомления: общий чат, ровно одно личное сообщение, звук получателю. */
final class MedalDelivery {
    private MedalDelivery() { }

    static void send(Component announcement, Component personal, Consumer<Component> broadcast,
                     Consumer<Component> recipient, Runnable recipientSound) {
        if (!PlainTextComponentSerializer.plainText().serialize(announcement).isBlank()) broadcast.accept(announcement);
        if (!PlainTextComponentSerializer.plainText().serialize(personal).isBlank()) recipient.accept(personal);
        recipientSound.run();
    }
}
