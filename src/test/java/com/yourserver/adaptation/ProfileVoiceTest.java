package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProfileVoiceTest {
    private final UUID owner = UUID.randomUUID();

    @Test
    void audioContainerRoundTripsWithOwnershipIntegrityAndBurstBoundaries() throws Exception {
        ProfileVoiceClip clip = new ProfileVoiceClip(owner, true, List.of(
                new ProfileVoiceClip.Frame(0, new byte[]{1, 2, 3}, true),
                new ProfileVoiceClip.Frame(50, new byte[]{4, 5}, true)));
        byte[] bytes = clip.encode();
        ProfileVoiceClip restored = ProfileVoiceClip.decode(owner, bytes);
        assertTrue(restored.stereo());
        assertEquals(2, restored.frames().size());
        assertArrayEquals(new byte[]{4, 5}, restored.frames().get(1).opus());
        assertTrue(restored.frames().get(1).startOfBurst());
        assertThrows(IOException.class, () -> ProfileVoiceClip.decode(UUID.randomUUID(), bytes));
        bytes[bytes.length - 10] ^= 1;
        assertThrows(IOException.class, () -> ProfileVoiceClip.decode(owner, bytes));
    }

    @Test
    void recordingStopsAtTenSecondsAndDoesNotAcceptDuplicatePackets() {
        UUID activation = UUID.randomUUID();
        long start = 100_000_000L;
        ProfileVoiceBuffer buffer = new ProfileVoiceBuffer(owner, start);
        buffer.add(start, 10, activation, false, new byte[]{1});
        buffer.add(start + 500_000_000L, 10, activation, false, new byte[]{2});
        buffer.add(start + 9_980_000_000L, 11, activation, false, new byte[]{3});
        buffer.add(start + 10_000_000_000L, 12, activation, false, new byte[]{4});
        ProfileVoiceClip clip = buffer.finish();
        assertEquals(2, clip.frames().size());
        assertEquals(499, clip.frames().getLast().tick());
        assertArrayEquals(new byte[]{3}, clip.frames().getLast().opus());
    }

    @Test
    void silenceCancellationAndFormatChangesDoNotProduceASavedClip() {
        ProfileVoiceBuffer empty = new ProfileVoiceBuffer(owner, 0);
        assertThrows(IllegalStateException.class, empty::finish);
        ProfileVoiceBuffer cancelled = new ProfileVoiceBuffer(owner, 0);
        UUID activation = UUID.randomUUID();
        cancelled.add(0, 0, activation, false, new byte[]{1});
        cancelled.cancel();
        assertThrows(IllegalStateException.class, cancelled::finish);
        ProfileVoiceBuffer format = new ProfileVoiceBuffer(owner, 0);
        format.add(0, 0, activation, false, new byte[]{1});
        format.add(20_000_000L, 1, activation, true, new byte[]{1});
        assertEquals("format-changed", format.failure());
        assertThrows(IllegalStateException.class, format::finish);
    }

    @Test
    void voiceReplacesTextOnlyForItsOwnerAndSurvivesProfileSerialization() throws Exception {
        ProfileData data = new ProfileData(owner, "Player");
        data.describe(owner, "Текст");
        ProfileVoiceNote note = new ProfileVoiceNote(UUID.randomUUID(), owner);
        assertFalse(data.voice(UUID.randomUUID(), note));
        assertEquals("Текст", data.description());
        assertTrue(data.voice(owner, note));
        assertEquals("", data.description());
        ProfileData restored = ProfileCodec.decode(owner, ProfileCodec.encodeProfile(data));
        assertEquals(note, restored.voice());
        assertTrue(restored.displayedDescription().contains("10"));
        assertTrue(restored.describe(owner, "Новый текст"));
        assertNull(restored.voice());
    }

    @Test
    void cloneCopiesLivePresentationButTestVotesCannotChangeTheRealProfile() {
        ProfileData real = new ProfileData(owner, "Player");
        real.describe(owner, "Описание");
        real.voice(owner, new ProfileVoiceNote(UUID.randomUUID(), owner));
        UUID clone = UUID.randomUUID();
        ProfileData preview = real.previewCopy(clone);
        assertEquals(owner, preview.skinOwner());
        assertEquals(real.voice(), preview.voice());
        assertEquals(real.name(), preview.name());
        assertTrue(preview.vote(owner, ProfileData.Vote.LIKE));
        assertEquals(1, preview.likes());
        assertEquals(0, real.likes());
        assertFalse(preview.describe(owner, "Нельзя изменить настоящий профиль"));
    }

    @Test
    void frameArraysAndSizeLimitsProtectTheSavedAudio() {
        byte[] input = {1, 2};
        ProfileVoiceClip.Frame frame = new ProfileVoiceClip.Frame(0, input);
        input[0] = 9;
        frame.opus()[0] = 8;
        assertArrayEquals(new byte[]{1, 2}, frame.opus());
        assertThrows(IllegalArgumentException.class, () -> new ProfileVoiceClip.Frame(1500, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ProfileVoiceClip.Frame(0, new byte[2049]));
        assertThrows(IOException.class, () -> ProfileVoiceClip.decode(owner, new byte[ProfileVoiceClip.MAX_FILE_BYTES + 1]));
    }
    @Test
    void pushToTalkBurstsCanRestartTheirSequenceAndKeepPauses() {
        UUID activation = UUID.randomUUID();
        ProfileVoiceBuffer buffer = new ProfileVoiceBuffer(owner, 0);
        buffer.add(0, 15, activation, false, new byte[]{1});
        buffer.endBurst(activation);
        buffer.add(1_000_000_000L, 0, activation, false, new byte[]{2});
        var clip = buffer.finish();
        assertEquals(2, clip.frames().size());
        assertEquals(50, clip.frames().getLast().tick());
        assertTrue(clip.frames().getLast().startOfBurst());
    }

    @Test
    void playbackCaptionAdvancesInSecondsAndIsCappedAtTen() {
        long start = 500_000_000L;
        assertEquals("▶ 0:10", VoicePlaybackView.IDLE.caption());
        for (int second = 0; second <= 10; second++) {
            assertEquals("▶ " + second + ":10", VoicePlaybackView.playing(start, start + second * 1_000_000_000L).caption());
        }
        assertEquals("▶ 10:10", VoicePlaybackView.playing(start, start + 20_000_000_000L).caption());
        assertEquals("Нажатие — воспроизведение/стоп", VoicePlaybackView.IDLE.hint());
    }

    @Test
    void olderThirtySecondFilesRemainReadableWithoutExtendingNewRecordings() throws Exception {
        ProfileVoiceClip old = new ProfileVoiceClip(owner, false, List.of(new ProfileVoiceClip.Frame(1499, new byte[]{1})));
        assertEquals(1499, ProfileVoiceClip.decode(owner, old.encode()).frames().getFirst().tick());
        assertEquals(500, ProfileVoiceClip.RECORDING_FRAMES);
        assertEquals(10_000, ProfileVoiceClip.DURATION_MS);
    }

}
