package com.yourserver.adaptation;

import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.capture.ServerActivation;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.provider.AudioFrameProvider;
import su.plo.voice.api.server.audio.provider.AudioFrameResult;
import su.plo.voice.api.server.audio.source.AudioSender;
import su.plo.voice.api.server.audio.source.ServerDirectSource;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEndEvent;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent;
import su.plo.voice.api.server.player.VoiceServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Addon(id = "f8_profiles", name = "Профили F8", version = "9.7", authors = {"F8"})
public final class PlasmoProfileVoice implements ProfileVoiceBridge, AddonInitializer {
    @InjectPlasmoVoice private PlasmoVoiceServer voice;
    private volatile ServerSourceLine line;
    private final Map<UUID, ProfileVoiceBuffer> recording = new ConcurrentHashMap<>();
    private final Map<UUID, Playback> playback = new ConcurrentHashMap<>();

    private record Playback(AudioSender sender, ServerDirectSource source, AtomicBoolean stopped) {
        void stop() { stopped.set(true); sender.stop(); source.remove(); }
    }

    public PlasmoProfileVoice() { }
    @Override public void initialize() { PlasmoVoiceServer.getAddonsLoader().load(this); }

    @Override public void onAddonInitialize() {
        line = voice.getSourceLineManager().createBuilder(this, "f8_profile", "Голосовые профили", "minecraft:textures/item/spyglass.png", 0)
                .setDefaultVolume(1.0).build();
    }

    @Override public String problem(UUID player, boolean capture) {
        if (voice == null || line == null) return "unavailable";
        VoiceServerPlayer target = voice.getPlayerManager().getPlayerById(player, false).orElse(null);
        if (target == null || !target.hasVoiceChat()) return "no-client";
        if (target.isVoiceDisabled()) return "disabled";
        if (capture && target.isMicrophoneMuted()) return "microphone-muted";
        if (capture && voice.getMuteManager().getMute(player).isPresent()) return "server-muted";
        return null;
    }

    @Override public void capture(UUID player, ProfileVoiceBuffer buffer) { recording.put(player, buffer); }
    @Override public void release(UUID player) { recording.remove(player); }

    @EventSubscribe(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void audio(PlayerSpeakEvent event) {
        ProfileVoiceBuffer buffer = recording.get(event.getPlayer().getInstance().getUuid());
        if (buffer == null) return;
        // Отмена ДО штатной активации: запись не передаётся в обычный голосовой канал.
        event.setCancelled(true); event.setResult(ServerActivation.Result.HANDLED);
        try {
            var packet = event.getPacket();
            byte[] opus = voice.getDefaultEncryption().decrypt(packet.getData());
            buffer.add(System.nanoTime(), packet.getSequenceNumber(), packet.getActivationId(), packet.isStereo(), opus);
        } catch (Exception ex) { buffer.fail("bad-audio"); }
    }

    @EventSubscribe(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void audioEnd(PlayerSpeakEndEvent event) {
        ProfileVoiceBuffer buffer = recording.get(event.getPlayer().getInstance().getUuid());
        if (buffer == null) return;
        event.setCancelled(true); event.setResult(ServerActivation.Result.HANDLED);
        buffer.endBurst(event.getPacket().getActivationId());
    }

    @Override public void play(UUID listener, ProfileVoiceClip clip, Consumer<Boolean> finished) {
        stop(listener);
        VoiceServerPlayer player = voice.getPlayerManager().getPlayerById(listener, false).orElseThrow();
        if (!player.hasVoiceChat() || line == null) throw new IllegalStateException("no-client");
        ServerDirectSource source = line.createDirectSource(player, clip.stereo());
        source.setIconVisible(false);
        source.setName("Голосовое описание");
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean stopped = new AtomicBoolean();
        long started = System.nanoTime();
        AudioFrameProvider provider = new AudioFrameProvider() {
            int next;
            int previousTick = -2;
            boolean ended;
            @Override public AudioFrameResult provide20ms() {
                try {
                    long elapsed = (System.nanoTime() - started) / 1_000_000L;
                    if (elapsed >= ProfileVoiceClip.DURATION_MS || stopped.get()) return AudioFrameResult.Finished.INSTANCE;
                    if (next >= clip.frames().size()) {
                        if (!ended) { ended = true; return AudioFrameResult.EndOfStream.INSTANCE; }
                        return new AudioFrameResult.Provided(null);
                    }
                    var frame = clip.frames().get(next);
                    if (!ended && next > 0 && (frame.startOfBurst() || frame.tick() > previousTick + 1)) {
                        ended = true;
                        return AudioFrameResult.EndOfStream.INSTANCE;
                    }
                    if (elapsed < frame.tick() * 20L) return new AudioFrameResult.Provided(null);
                    next++; previousTick = frame.tick(); ended = false;
                    return new AudioFrameResult.Provided(voice.getDefaultEncryption().encrypt(frame.opus()));
                } catch (Exception ex) {
                    failed.set(true);
                    return AudioFrameResult.Finished.INSTANCE;
                }
            }
        };
        AudioSender sender = source.createAudioSender(provider);
        Playback session = new Playback(sender, source, stopped);
        playback.put(listener, session);
        sender.onStop(() -> {
            Runnable cleanup = () -> {
                playback.remove(listener, session);
                source.remove();
                finished.accept(!failed.get() && !stopped.get());
            };
            // Дать клиентскому аудиобуферу доиграть последний кадр, не обрезать последнее слово.
            if (failed.get() || stopped.get()) cleanup.run();
            else try { voice.getBackgroundExecutor().schedule(cleanup, 1, TimeUnit.SECONDS); }
            catch (RuntimeException ex) { cleanup.run(); }
        });
        sender.start();
    }

    @EventSubscribe
    public void disconnected(UdpClientDisconnectedEvent event) {
        UUID player = event.getConnection().getPlayer().getInstance().getUuid();
        ProfileVoiceBuffer buffer = recording.get(player);
        if (buffer != null) buffer.fail("no-client");
        stop(player);
    }

    @Override public boolean stop(UUID listener) {
        Playback current = playback.remove(listener);
        if (current == null) return false;
        current.stop();
        return true;
    }

    @Override public void onAddonShutdown() {
        line = null;
        recording.values().forEach(buffer -> buffer.fail("unavailable"));
        recording.clear();
        playback.values().forEach(Playback::stop); playback.clear();
        if (voice != null) {
            voice.getEventBus().unregister(this);
            voice.getSourceLineManager().unregister("f8_profile");
        }
    }
    @Override public void close() { PlasmoVoiceServer.getAddonsLoader().unload(this); onAddonShutdown(); }
}
