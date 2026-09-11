package com.yourserver.adaptation;

import java.util.UUID;
import java.util.function.Consumer;

/** Граница необязательного Plasmo Voice. Остальной плагин не загружает классы его API. */
interface ProfileVoiceBridge extends AutoCloseable {
    void initialize();
    String problem(UUID player, boolean recording);
    void capture(UUID player, ProfileVoiceBuffer buffer);
    void release(UUID player);
    void play(UUID listener, ProfileVoiceClip clip, Consumer<Boolean> finished);
    boolean stop(UUID listener);
    @Override void close();
}
