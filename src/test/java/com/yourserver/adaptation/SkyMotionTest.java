package com.yourserver.adaptation;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkyMotionTest {
    @Test
    void fadeIsGradualAndCanReverseWithoutJumping() {
        SkyFade fade = new SkyFade();
        double first = fade.advance(true, 1, 40);
        assertTrue(first > 0 && first < 0.1);
        double next = fade.advance(true, 1, 40);
        assertTrue(next > first);
        assertTrue(fade.advance(false, 1, 40) < next);
        fade.advance(true, 40, 40);
        assertEquals(1.0, fade.value());
        fade.advance(false, 40, 40);
        assertTrue(fade.hidden());
    }

    @Test
    void daylightWithoutAnExistingSkyStaysHidden() {
        SkyFade fade = new SkyFade();
        assertEquals(0, fade.advance(false, 40, 40));
        assertTrue(fade.hidden());
    }

    @Test
    void movementHasNoDeadZonePredictionOrInertia() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        Vector3d eye = new Vector3d(0.2, 0.4, -0.1);
        assertTrue(follow.follow(eye, 1));
        assertEquals(eye, follow.target());
        assertEquals(eye, follow.sample(2));
        assertFalse(follow.follow(eye, 2));
        assertEquals(eye, follow.sample(100));
    }

    @Test
    void movingAndJumpingNeverLetThePlayerApproachTheSphere() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        for (int tick = 1; tick <= 200; tick++) {
            Vector3d eye = new Vector3d(tick * 0.2, Math.sin(tick * 0.2), 0);
            follow.follow(eye, tick);
            assertEquals(eye, follow.target());
            assertTrue(follow.sample(tick).x + 80 - eye.x > 79);
        }
    }

    @Test
    void teleportsResetTheAnchorAndCounterWrappingIsSafe() {
        int start = Integer.MAX_VALUE - 1;
        SkyFollow follow = new SkyFollow(new Vector3d(), start);
        follow.follow(new Vector3d(1, 0, 0), start + 1);
        assertEquals(follow.target(), follow.sample(start + 2));
        assertTrue(follow.isJump(new Vector3d(1000, 100, 1000)));
        follow.reset(new Vector3d(1000, 100, 1000), start + 3);
        assertEquals(follow.target(), follow.sample(start + 3));
    }

    @Test
    void borderCoordinatesRetainDoublePrecision() {
        Vector3d origin = new Vector3d(29_000_000.123, 70.62, -29_000_000.456);
        SkyFollow follow = new SkyFollow(origin, 0);
        Vector3d moved = new Vector3d(origin).add(0.02, 0, 0);
        follow.follow(moved, 1);
        assertEquals(moved, follow.sample(2));
        assertEquals(29_000_000.123, origin.x, 1e-9);
    }

    @Test
    void orbitKeepsRadiusAndInverseRotationPreservesTargeting() {
        Vector3f local = new Vector3f(1, 2, 3).normalize().mul(80);
        float angle = 1.2f;
        Vector3f rendered = new Vector3f(local).rotateY(angle).mul(2);
        Vector3f restored = SkyOrbit.local(rendered, angle, 2);
        assertTrue(restored.distance(local) < 0.0001);
        assertEquals(160, rendered.length(), 0.0001);
    }

    @Test
    void fartherSphereIsLimitedByClientAndServerViewDistance() {
        assertEquals(1f, SkyOrbit.depthScale(100, 4, 10, 3, 0.7));
        float scale = SkyOrbit.depthScale(100, 16, 10, 3, 0.7);
        assertEquals(1.12f, scale, 0.0001);
        assertTrue(100 * scale <= 10 * 16 * 0.7 + 0.001);
        assertEquals(3f, SkyOrbit.depthScale(80, 32, 32, 3, 0.7));
        // depth-fill отодвигает сферу к краю видимой дальности: звёзды прячутся
        // за деревьями и облаками, а не наоборот.
        assertTrue(SkyOrbit.depthScale(80, 16, 16, 8, 0.95) > SkyOrbit.depthScale(80, 16, 16, 8, 0.7));
        // fill больше 1.0 обрезается до 1.0, дальше сфера ограничена множителем:
        // 16 чанков * 16 блоков / 80 = 3.2.
        assertEquals(3.2f, SkyOrbit.depthScale(80, 16, 16, 8, 2.0), 0.0001f);
    }
    @Test
    void positiveOrbitSpeedNowMovesRightAndKeepsTargetingAligned() {
        double phase = SkyOrbit.advance(0, 0.15, 20);
        Vector3f start = new Vector3f(0, 0, 80);
        Vector3f rendered = new Vector3f(start).rotateY((float) phase);
        assertTrue(rendered.x < 0); // При взгляде на юг справа запад, то есть отрицательный X.
        assertEquals(80, rendered.length(), 0.0001);
        assertTrue(SkyOrbit.local(rendered, phase, 1).distance(start) < 0.0001);
        assertEquals(0, SkyOrbit.advance(0, 0, 20));
    }

}
