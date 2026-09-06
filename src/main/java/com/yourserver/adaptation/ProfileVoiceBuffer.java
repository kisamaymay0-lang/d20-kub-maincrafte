package com.yourserver.adaptation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Только кадры добровольно начатой записи; доступ из потока UDP синхронизирован. */
final class ProfileVoiceBuffer {
    final UUID owner;
    final long started;
    private final List<ProfileVoiceClip.Frame> frames = new ArrayList<>();
    private UUID activation;
    private boolean stereo;
    private boolean initialized;
    private long lastSequence;
    private int lastTick = -1;
    private String failure;
    private boolean closed;
    private boolean nextBurst = true;

    ProfileVoiceBuffer(UUID owner, long startedNanos) { this.owner = owner; this.started = startedNanos; }

    synchronized void add(long nowNanos, long sequence, UUID activation, boolean stereo, byte[] opus) {
        if (closed || failure != null) return;
        long elapsed = nowNanos - started;
        if (elapsed < 0 || elapsed >= 30_000_000_000L) return;
        if (opus == null || opus.length == 0 || opus.length > ProfileVoiceClip.MAX_FRAME_BYTES) { failure = "bad-audio"; return; }
        int tick = (int) (elapsed / 20_000_000L);
        if (initialized) {
            if (!this.activation.equals(activation)) return; // Не удваивать одновременно включённые каналы.
            if (this.stereo != stereo) { failure = "format-changed"; return; }
            if (!nextBurst && sequence <= lastSequence) return; // Поздний/повторный UDP пакет.
            tick = Math.max(tick, lastTick + 1);
        } else {
            this.activation = activation; this.stereo = stereo; initialized = true;
        }
        if (tick >= ProfileVoiceClip.MAX_FRAMES) return;
        frames.add(new ProfileVoiceClip.Frame(tick, opus, nextBurst));
        lastSequence = sequence; lastTick = tick; nextBurst = false;
    }

    synchronized void endBurst(UUID activation) {
        if (this.activation == null || this.activation.equals(activation)) nextBurst = true;
    }

    synchronized void fail(String reason) { if (failure == null) failure = reason; }
    synchronized String failure() { return failure; }
    synchronized boolean empty() { return frames.isEmpty(); }
    synchronized ProfileVoiceClip finish() {
        closed = true;
        if (failure != null || frames.isEmpty()) throw new IllegalStateException(failure == null ? "no-audio" : failure);
        return new ProfileVoiceClip(owner, stereo, frames);
    }
    synchronized void cancel() { closed = true; frames.clear(); }
}
