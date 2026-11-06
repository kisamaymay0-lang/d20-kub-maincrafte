package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

/**
 * Плавающая надпись над питомцем, короной или игроком.
 *
 * <p>Общий вид у всех надписей одинаковый: billboard, тень, масштаб 0.5 и без
 * фона. Буквы появляются по одной — короткие реплики печатаются по символу за
 * тик, длинные сообщения игрока ускоряются, чтобы уложиться в половину
 * времени жизни. Текст мягко проявляется в начале, держится и гаснет в конце,
 * а сама надпись каждый тик следует за своим якорем.</p>
 */
final class SpeechText {
    /** Секунда на появление и секунда на затухание. */
    static final int FADE_TICKS = 20;
    private static final float SCALE = 0.5f;
    private static final int LINE_WIDTH = 300;
    /** Ниже этого значения клиент считает текст полностью непрозрачным. */
    private static final int MIN_OPACITY = 4;
    private static final int MAX_OPACITY = 255;

    private final TextDisplay display;
    private final String content;
    private final int lifetimeTicks;
    private final int charsPerTick;
    private int age;
    private int revealed;
    private int fadeOutAge = -1;
    private int fadeOutStartOpacity = MAX_OPACITY;

    private SpeechText(TextDisplay display, String content, int lifetimeTicks, int charsPerTick) {
        this.display = display;
        this.content = content;
        this.lifetimeTicks = Math.max(1, lifetimeTicks);
        this.charsPerTick = charsPerTick;
        this.revealed = Math.min(content.length(), charsPerTick);
    }

    static SpeechText spawn(
            Location at,
            String content,
            int lifetimeTicks,
            int interpolationTicks,
            NamespacedKey marker
    ) {
        String text = content == null ? "" : content;
        int lifetime = Math.max(1, lifetimeTicks);
        int perTick = charsPerTick(text.length(), lifetime);
        String firstPart = visiblePart(text, perTick);
        Location spawnAt = anchorLocation(at);

        TextDisplay display = spawnAt.getWorld().spawn(spawnAt, TextDisplay.class, entity -> {
            entity.text(Component.text(firstPart));
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setTextOpacity(opacityFor(0, lifetime));
            Transformation transformation = entity.getTransformation();
            transformation.getScale().set(SCALE, SCALE, SCALE);
            entity.setTransformation(transformation);
            entity.setLineWidth(LINE_WIDTH);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setTeleportDuration(Math.max(0, interpolationTicks));
            entity.setInterpolationDuration(0);
            if (marker != null) {
                entity.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            }
        });
        return new SpeechText(display, text, lifetime, perTick);
    }

    /**
     * Один серверный тик: допечатать буквы, обновить прозрачность и переехать
     * за якорем. Возвращает {@code false}, когда надпись пора убрать.
     */
    boolean tick(Location anchor) {
        if (!display.isValid() || display.isDead()) {
            return false;
        }

        age++;
        if (fadeOutAge >= 0) {
            fadeOutAge++;
            if (fadeOutAge >= FADE_TICKS) {
                return false;
            }
            double remaining = 1.0D - fadeOutAge / (double) FADE_TICKS;
            int opacity = (int) Math.round(MIN_OPACITY + (fadeOutStartOpacity - MIN_OPACITY) * remaining);
            display.setTextOpacity((byte) Math.max(MIN_OPACITY, opacity));
        } else {
            if (age >= lifetimeTicks) {
                return false;
            }
            display.setTextOpacity(opacityFor(age, lifetimeTicks));
            revealNextChars();
        }

        moveTo(anchor);
        return true;
    }

    /** Начать затухание сразу: например, когда игрок отвёл взгляд. */
    void fadeOut() {
        if (fadeOutAge >= 0) {
            return;
        }
        fadeOutAge = 0;
        fadeOutStartOpacity = Byte.toUnsignedInt(display.getTextOpacity());
    }

    void remove() {
        if (display.isValid()) {
            display.remove();
        }
    }

    TextDisplay display() {
        return display;
    }

    private void revealNextChars() {
        if (revealed >= content.length()) {
            return;
        }
        revealed = Math.min(content.length(), revealed + charsPerTick);
        display.text(Component.text(visiblePart(content, revealed)));
    }

    private void moveTo(Location anchor) {
        Location target = anchorLocation(anchor);
        if (!display.getWorld().equals(target.getWorld())
                || display.getLocation().distanceSquared(target) > 1.0E-6) {
            display.teleport(target);
        }
    }

    private static Location anchorLocation(Location anchor) {
        Location target = anchor.clone();
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        return target;
    }

    /**
     * Сколько букв открывать за тик. Короткая реплика печатается по одной
     * букве, длинное сообщение игрока успевает появиться за половину времени
     * жизни, а не висит пустым.
     */
    static int charsPerTick(int length, int lifetimeTicks) {
        if (length <= 0) {
            return 1;
        }
        int typingBudget = Math.max(1, Math.min(length, Math.max(1, lifetimeTicks) / 2));
        return Math.max(1, (int) Math.ceil(length / (double) typingBudget));
    }

    /** Видимая часть строки; суррогатная пара (эмодзи) никогда не рвётся пополам. */
    static String visiblePart(String content, int revealed) {
        if (content == null || content.isEmpty() || revealed <= 0) {
            return "";
        }
        int cut = Math.min(content.length(), revealed);
        if (cut < content.length()
                && cut > 0
                && Character.isHighSurrogate(content.charAt(cut - 1))
                && Character.isLowSurrogate(content.charAt(cut))) {
            cut++;
        }
        return content.substring(0, cut);
    }

    /** Прозрачность: секунда появления, полная видимость и секунда затухания. */
    static byte opacityFor(int age, int lifetimeTicks) {
        int lifetime = Math.max(1, lifetimeTicks);
        int fade = Math.min(FADE_TICKS, Math.max(1, lifetime / 2));
        double progress;
        if (age < fade) {
            progress = age / (double) fade;
        } else if (age > lifetime - fade) {
            progress = (lifetime - age) / (double) fade;
        } else {
            progress = 1.0D;
        }
        progress = Math.max(0.0D, Math.min(1.0D, progress));
        // Значения 0..3 клиент трактует как непрозрачные, поэтому минимум — 4.
        return (byte) Math.round(MIN_OPACITY + (MAX_OPACITY - MIN_OPACITY) * progress);
    }
}
