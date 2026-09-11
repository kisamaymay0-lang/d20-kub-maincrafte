package com.yourserver.adaptation;

import java.util.Objects;
import java.util.UUID;

/** Ссылка на голосовое описание. Только UUID, без произвольных путей/URL. */
record ProfileVoiceNote(UUID clip, UUID speaker) {
    ProfileVoiceNote { Objects.requireNonNull(clip); Objects.requireNonNull(speaker); }
}
