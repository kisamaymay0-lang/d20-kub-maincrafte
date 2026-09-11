package com.yourserver.adaptation;

/** Состояние интерфейса только для слушателя, не изменяет профиль и не пишет ничего на диск. */
record VoicePlaybackView(int second, String hint) {
    static final String HINT = "Нажатие — воспроизведение/стоп";
    static final VoicePlaybackView IDLE = new VoicePlaybackView(0, HINT);

    String caption() { return "▶ " + second + ":" + ProfileVoiceClip.DURATION_MS / 1000; }

    static VoicePlaybackView playing(long startedNanos, long nowNanos) {
        int second = (int) Math.clamp((nowNanos - startedNanos) / 1_000_000_000L, 0L, ProfileVoiceClip.DURATION_MS / 1000L);
        return new VoicePlaybackView(second, HINT);
    }
}
