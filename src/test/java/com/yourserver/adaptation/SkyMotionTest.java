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
    void smallMovesAndShortJumpsDoNotMoveTheSky() {
        SkyFollow follow = new SkyFollow(new Vector3d(0, 64, 0), 0);
        assertFalse(follow.follow(new Vector3d(0.3, 64, 0), 2, 3, 6, true));
        double[] heights = {0.42, 0.85, 1.15, 1.25, 1.0, 0.6, 0.2, 0};
        for (int i = 0; i < heights.length; i++) {
            assertFalse(follow.follow(new Vector3d(0.3, 64 + heights[i], 0), 4 + i * 2, 3, 6, heights[i] == 0));
            assertEquals(64, follow.target().y, 1e-9);
        }
    }

    @Test
    void sustainedWalkingIsInertialBoundedAndDoesNotLeadThePlayer() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        for (int tick = 2; tick <= 200; tick += 2) {
            Vector3d reference = new Vector3d(tick * 0.2, 0, 0);
            follow.follow(reference, tick, 3, 6, true);
            assertTrue(follow.target().x <= reference.x);
            assertTrue(reference.x - follow.target().x <= 6.000001);
            assertTrue(follow.sample(tick).x + 80 - reference.x > 73);
        }
        assertTrue(40 - follow.target().x > 1.5); // Нет жёсткой привязки к каждому шагу.
    }

    @Test
    void movingTargetsAreStillClientInterpolated() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        assertTrue(follow.follow(new Vector3d(4, 0, 0), 2, 3, 6, true));
        assertEquals(0.0, follow.sample(2).x, 1e-9);
        assertTrue(follow.sample(3).x > 0);
        assertTrue(follow.sample(3).x < follow.target().x);
        assertEquals(follow.target().x, follow.sample(5).x, 1e-9);
    }

    @Test
    void longFlightAndTeleportsCannotLeaveTheSphereBehind() {
        SkyFollow follow = new SkyFollow(new Vector3d(), 0);
        for (int tick = 2; tick <= 200; tick += 2) {
            Vector3d position = new Vector3d(0, tick * 0.4, 0);
            follow.follow(position, tick, 3, 6, false);
            assertTrue(position.y - follow.target().y <= 6.000001);
        }
        assertTrue(follow.isJump(new Vector3d(1000, 100, 1000)));
        follow.reset(new Vector3d(1000, 100, 1000), 202);
        assertEquals(new Vector3d(1000, 100, 1000), follow.sample(202));
    }

    @Test
    void stationarySkyRetainsDoublePrecisionNearWorldBorder() {
        Vector3d eye = new Vector3d(29_000_000.123, 70.62, -29_000_000.456);
        SkyFollow follow = new SkyFollow(eye, 0);
        assertFalse(follow.follow(eye, 2, 3, 6, true));
        assertEquals(eye, follow.sample(2));
        Vector3d moved = new Vector3d(eye).add(4, 0, 0);
        assertTrue(follow.follow(moved, 4, 3, 6, true));
        assertTrue(follow.target().x > eye.x && follow.target().x < moved.x);
        assertEquals(29_000_000.123, eye.x, 1e-9);
    }

    @Test
    void followSurvivesTheServerTickCounterWrapping() {
        int start = Integer.MAX_VALUE - 1;
        SkyFollow follow = new SkyFollow(new Vector3d(), start);
        follow.follow(new Vector3d(4, 0, 0), start + 2, 3, 6, true);
        assertEquals(follow.target().x, follow.sample(start + 5).x, 1e-9);
    }
}
