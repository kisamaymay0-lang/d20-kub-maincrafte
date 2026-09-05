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
}
