package com.yourserver.adaptation;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkyMotionTest {
    @Test
    void fadeIsGradualAndCanReverseWithoutJumping() {
        SkyFade fade = new SkyFade();
        assertEquals(0, fade.value());
        double first = fade.advance(true, 2, 40);
        assertTrue(first > 0 && first < 0.1);
        double previous = first;
        for (int i = 0; i < 8; i++) {
            double next = fade.advance(true, 2, 40);
            assertTrue(next > previous && next < 1);
            previous = next;
        }
        double reversed = fade.advance(false, 2, 40);
        assertTrue(reversed < previous && reversed > 0);
        fade.advance(true, 40, 40);
        assertEquals(1.0, fade.value());
        fade.advance(false, 40, 40);
        assertTrue(fade.hidden());
        assertEquals(0.0, fade.value());
    }

    @Test
    void daylightWithoutAnExistingSkyStaysHidden() {
        SkyFade fade = new SkyFade();
        for (int i = 0; i < 50; i++) {
            assertEquals(0.0, fade.advance(false, 2, 40));
            assertTrue(fade.hidden());
        }
    }

    @Test
    void movementInterpolatesInsteadOfSnappingToTheNewTarget() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        assertTrue(follow.follow(new Vector3d(0.4, 0, 0), 2, 3, 2));
        assertEquals(0.0, follow.sample(2).x, 1e-9);
        assertTrue(follow.sample(3).x > 0);
        assertTrue(follow.sample(3).x < follow.target().x);
        assertEquals(follow.target().x, follow.sample(5).x, 1e-9);
    }

    @Test
    void predictionRemovesSteadyWalkingLagWithoutLettingThePlayerApproachTheSky() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        for (int tick = 2; tick <= 100; tick += 2) {
            Vector3d eye = new Vector3d(tick * 0.2, 64, -20);
            if (tick == 2) follow.reset(new Vector3d(0, 64, -20), 0);
            follow.follow(eye, tick, 3, 2);
            if (tick > 30) assertEquals(eye.x, follow.sample(tick).x, 1e-6);
            assertTrue(follow.target().distance(eye) <= 2.0);
            // Сама звезда всегда далеко: +80 к общей точке неба.
            assertTrue(follow.sample(tick).x + 80 - eye.x > 79);
        }
    }

    @Test
    void stoppingAndLargeTeleportsDoNotLeaveAnUnboundedPrediction() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        follow.follow(new Vector3d(10, 0, 0), 2, 3, 0.5);
        assertEquals(10.5, follow.target().x, 1e-9);
        follow.follow(new Vector3d(10, 0, 0), 4, 3, 0.5);
        assertEquals(10.0, follow.sample(7).x, 1e-9);
        assertTrue(follow.isJump(new Vector3d(1000, 100, 1000)));
        follow.reset(new Vector3d(1000, 100, 1000), 8);
        assertEquals(new Vector3d(1000, 100, 1000), follow.sample(8));
    }

    @Test
    void stationarySkySendsNoNewTargetsAndRetainsDoublePrecisionNearWorldBorder() {
        Vector3d eye = new Vector3d(29_000_000.123, 70.62, -29_000_000.456);
        SkyFollow follow = new SkyFollow(eye, 0);
        assertFalse(follow.follow(eye, 2, 3, 2));
        assertEquals(eye, follow.sample(2));
        Vector3d moved = new Vector3d(eye).add(0.02, 0, 0);
        assertTrue(follow.follow(moved, 4, 3, 2));
        assertTrue(follow.target().x > eye.x);
        assertEquals(29_000_000.123, eye.x, 1e-9); // Не мутируем аргумент.
    }

    @Test
    void followSurvivesTheServerTickCounterWrapping() {
        int start = Integer.MAX_VALUE - 1;
        SkyFollow follow = new SkyFollow(new Vector3d(), start);
        follow.follow(new Vector3d(1, 0, 0), start + 2, 3, 2);
        assertEquals(follow.target().x, follow.sample(start + 5).x, 1e-9);
    }
}
