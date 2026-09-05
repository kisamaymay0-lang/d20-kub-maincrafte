package com.yourserver.adaptation;

/** Плавный обратимый переход без привязки к кадровой частоте клиента. */
final class SkyFade {
    private double progress;

    double advance(boolean visible, int elapsedTicks, int durationTicks) {
        double step = durationTicks <= 0 ? 1.0 : (double) Math.max(0, elapsedTicks) / durationTicks;
        progress = Math.clamp(progress + (visible ? step : -step), 0.0, 1.0);
        return value();
    }

    double value() { return progress * progress * (3.0 - 2.0 * progress); }
    boolean hidden() { return progress <= 0.0; }
    void reset() { progress = 0.0; }
}
