package com.yourserver.adaptation;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/** Ограниченный контейнер расшифрованных Opus кадров, не привязанный к ключу текущего подключения. */
record ProfileVoiceClip(UUID speaker, boolean stereo, List<Frame> frames) {
    static final int DURATION_MS = 30_000;
    static final int MAX_FRAMES = 1500;
    static final int MAX_FRAME_BYTES = 2048;
    static final int MAX_FILE_BYTES = 3_200_000;
    private static final int MAGIC = 0x46385631;

    record Frame(int tick, byte[] opus, boolean startOfBurst) {
        Frame(int tick, byte[] opus) { this(tick, opus, false); }
        Frame {
            if (tick < 0 || tick >= MAX_FRAMES || opus.length == 0 || opus.length > MAX_FRAME_BYTES) throw new IllegalArgumentException("Неверный голосовой кадр");
            opus = opus.clone();
        }
        @Override public byte[] opus() { return opus.clone(); }
    }

    ProfileVoiceClip {
        frames = List.copyOf(frames);
        if (speaker == null || frames.isEmpty() || frames.size() > MAX_FRAMES) throw new IllegalArgumentException("Пустая или слишком длинная запись");
        int previous = -1;
        for (Frame frame : frames) {
            if (frame.tick() <= previous) throw new IllegalArgumentException("Неверный порядок кадров");
            previous = frame.tick();
        }
    }

    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeLong(speaker.getMostSignificantBits()); out.writeLong(speaker.getLeastSignificantBits());
        out.writeBoolean(stereo); out.writeInt(frames.size());
        for (Frame frame : frames) {
            byte[] opus = frame.opus();
            out.writeShort(frame.tick()); out.writeShort(opus.length); out.writeBoolean(frame.startOfBurst()); out.write(opus);
        }
        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32(); crc.update(payload);
        out.writeLong(crc.getValue());
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_FILE_BYTES) throw new IOException("Запись слишком большая");
        return result;
    }

    static ProfileVoiceClip decode(UUID expectedSpeaker, byte[] bytes) throws IOException {
        if (bytes.length < 34 || bytes.length > MAX_FILE_BYTES) throw new IOException("Неверный размер записи");
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 8);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) throw new IOException("Неверный формат записи");
        UUID speaker = new UUID(in.readLong(), in.readLong());
        if (!speaker.equals(expectedSpeaker)) throw new IOException("Неверный владелец записи");
        boolean stereo = in.readBoolean();
        int count = in.readInt();
        if (count < 1 || count > MAX_FRAMES) throw new IOException("Неверное число кадров");
        List<Frame> frames = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                int tick = in.readUnsignedShort(); int size = in.readUnsignedShort(); boolean burst = in.readBoolean();
                if (size < 1 || size > MAX_FRAME_BYTES || size > in.available() - 8) throw new IOException("Повреждённый кадр");
                frames.add(new Frame(tick, in.readNBytes(size), burst));
            }
            if (in.readLong() != crc.getValue() || in.available() != 0) throw new IOException("Запись повреждена");
            return new ProfileVoiceClip(speaker, stereo, frames);
        } catch (IllegalArgumentException ex) { throw new IOException("Запись повреждена", ex); }
    }
}
