package com.yourserver.adaptation;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProfilePanelGeometryTest {
    private final Vector3d eye = new Vector3d(0, 65.27, -4);
    private final Vector3d feet = new Vector3d(0, 64, 0);

    @Test
    void panelIsAtBodyLevelAndSlightlyShorterThanThePlayer() {
        var panel = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        assertEquals(1.62, panel.height(), 1e-9);
        assertEquals(64.9, panel.center().y, 1e-9);
        assertTrue(panel.center().y - panel.height() / 2 > feet.y);
        assertTrue(panel.center().y + panel.height() / 2 < feet.y + 1.8);
    }

    @Test
    void movingTheCursorOntoThePanelDoesNotMoveThePanel() {
        var panel = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        var bodyRay = new Vector3d(feet).add(0, 1.3, 0).sub(eye).normalize();
        var panelRay = new Vector3d(panel.center()).sub(eye).normalize();
        assertNotEquals(bodyRay, panelRay);
        var hit = panel.intersect(eye, panelRay, 12);
        assertNotNull(hit);
        assertTrue(panel.focusBounds().contains(hit.x(), hit.y()));
        assertTrue(panel.approximately(ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8)));
    }

    @Test
    void iconAndSecondaryTooltipAreIndependentHoverSurfaces() {
        var panel = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        var icon = panel.medal();
        Vector3d ray = panel.point(icon.x(), icon.y(), 0).sub(eye).normalize();
        var hit = panel.intersect(eye, ray, 12);
        assertNotNull(hit);
        assertTrue(icon.contains(hit.x(), hit.y()));
        var tooltip = panel.tooltip(1.2, 1.1);
        var tipHit = panel.intersect(eye, panel.point(tooltip.x(), tooltip.y(), 0).sub(eye), 12);
        assertNotNull(tipHit);
        assertTrue(tooltip.contains(tipHit.x(), tipHit.y()));
        assertFalse(panel.bounds().contains(tipHit.x(), tipHit.y()));
        var bridge = panel.tooltipBridge(tooltip);
        assertTrue(bridge.contains(bridge.x(), bridge.y()));
    }

    @Test
    void backwardsParallelAndTooDistantRaysAreRejected() {
        var panel = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        assertNull(panel.intersect(eye, new Vector3d(panel.right()), 12));
        assertNull(panel.intersect(eye, new Vector3d(eye).sub(panel.center()), 12));
        assertNull(panel.intersect(eye, new Vector3d(panel.center()).sub(eye), 0.5));
    }

    @Test
    void coordinatesNearTheWorldBorderKeepCentimeterPrecision() {
        Vector3d origin = new Vector3d(29_000_000.125, 64, -29_000_000.125);
        Vector3d observer = new Vector3d(origin).add(0, 1.27, -4);
        var panel = ProfilePanelGeometry.beside(observer, origin, 0.6, 1.8);
        var icon = panel.medal();
        var hit = panel.intersect(observer, panel.point(icon.x(), icon.y(), 0).sub(observer), 12);
        assertNotNull(hit);
        assertEquals(icon.x(), hit.x(), 1e-6);
        assertEquals(icon.y(), hit.y(), 1e-6);
    }
    @Test
    void attachedPanelMovesWithItsOwnerWithoutOrbitingOrChangingOrientation() {
        var initial = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        Vector3d movement = new Vector3d(3, 0.5, 2);
        var moved = initial.translated(movement);
        assertEquals(initial.normal(), moved.normal());
        assertEquals(initial.right(), moved.right());
        assertEquals(new Vector3d(initial.center()).add(movement), moved.center());
        assertEquals(initial.height(), moved.height());
        // Позиция нового наблюдателя намеренно не участвует в переносе закреплённой панели.
        var recomputed = ProfilePanelGeometry.beside(new Vector3d(4, 65, 0), new Vector3d(feet).add(movement), 0.6, 1.8);
        assertNotEquals(recomputed.normal(), moved.normal());
    }

    @Test
    void openAndVoteRegionsMatchTheirRowsAndDoNotOverlap() {
        var panel = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        var open = panel.openProfile();
        var like = panel.vote(true, 6, 0);
        var dislike = panel.vote(false, 6, 0);
        assertEquals(ProfilePanelGeometry.Action.OPEN, panel.action(new ProfilePanelGeometry.Hit(open.x(), open.y(), 4, new Vector3d()), 6, 0));
        assertEquals(ProfilePanelGeometry.Action.LIKE, panel.action(new ProfilePanelGeometry.Hit(like.x(), like.y(), 4, new Vector3d()), 6, 0));
        assertEquals(ProfilePanelGeometry.Action.DISLIKE, panel.action(new ProfilePanelGeometry.Hit(dislike.x(), dislike.y(), 4, new Vector3d()), 6, 0));
        assertEquals(ProfilePanelGeometry.Action.NONE, panel.action(new ProfilePanelGeometry.Hit(0, panel.height() / 3, 4, new Vector3d()), 6, 0));
        assertFalse(like.contains(dislike.x(), dislike.y()));
        assertFalse(open.contains(panel.medal().x(), panel.medal().y()));
    }

    @Test
    void controlsAreLargerWithoutOverlappingOrExtendingOutsideThePanel() {
        var panel = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        assertEquals(panel.height() * 0.36, panel.footerScale(), 1e-9);
        var like = panel.vote(true, 6, 0);
        var dislike = panel.vote(false, 6, 0);
        assertTrue(like.width() > 0.4);
        assertTrue(panel.openProfile().width() > 1.4);
        assertTrue(panel.medal().width() > 0.3);
        assertFalse(like.contains(dislike.x(), dislike.y()));
        assertFalse(panel.openProfile().contains(panel.medal().x(), panel.medal().y()));
        for (var rect : java.util.List.of(like, dislike, panel.openProfile(), panel.medal())) {
            assertTrue(Math.abs(rect.x()) + rect.width() / 2 <= panel.width() / 2);
            assertTrue(Math.abs(rect.y()) + rect.height() / 2 <= panel.height() / 2);
        }
    }

    @Test
    void voiceDescriptionHasItsOwnClickAreaWithoutChangingOtherControls() {
        var frame = ProfilePanelGeometry.beside(eye, feet, 0.6, 1.8);
        var voice = frame.voiceButton();
        var hit = new ProfilePanelGeometry.Hit(voice.x(), voice.y(), 4, new Vector3d());
        assertEquals(ProfilePanelGeometry.Action.PLAY_VOICE, frame.action(hit, 0, 0, true));
        assertEquals(ProfilePanelGeometry.Action.NONE, frame.action(hit, 0, 0, false));
        assertFalse(frame.openProfile().contains(voice.x(), voice.y()));
        assertTrue(voice.width() >= frame.width() * 0.79);
        assertTrue(voice.height() >= frame.height() * 0.30);
        assertTrue(voice.contains(0, frame.height() * 0.225));
        assertTrue(voice.contains(0, frame.height() * 0.12));
        assertFalse(voice.contains(0, frame.height() * 0.43));
    }

}
