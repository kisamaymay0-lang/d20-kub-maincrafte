package com.yourserver.adaptation;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StarTargetingTest {

    private static Constellation constellation(String id, boolean pinned) {
        Constellation result = new Constellation(id);
        result.pinned = pinned;
        return result;
    }

    private static Constellation.StarDef star(Constellation constellation, String id, double azimuth) {
        Constellation.StarDef star = new Constellation.StarDef(id, azimuth, 45);
        constellation.stars.put(id, star);
        return star;
    }

    private static String aimed(List<Constellation> sky, Vector3f look, String excluded) {
        return StarTargeting.closest(sky, look, 2.0, excluded, Constellation.StarDef::direction);
    }

    @Test
    void previewCanTargetAnotherConstellationWithoutRequiringAnEdge() {
        Constellation first = constellation("first", true);
        Constellation second = constellation("second", true);
        star(first, "selected", 0);
        Constellation.StarDef target = star(second, "target", 40);
        assertEquals("second:target", aimed(List.of(first, second), target.direction(), "first:selected"));
        assertTrue(first.edges.isEmpty());
        assertTrue(second.edges.isEmpty());
    }

    @Test
    void edgesAndConstellationMembershipCannotChangeThePreviewAnswer() {
        Constellation first = constellation("first", true);
        Constellation second = constellation("second", true);
        star(first, "selected", 0);
        Constellation.StarDef target = star(first, "target", 40);
        List<Constellation> sky = List.of(first, second);
        assertEquals("first:target", aimed(sky, target.direction(), "first:selected"));
        first.edges.add(new Constellation.EdgeDef("selected", "target"));
        assertEquals("first:target", aimed(sky, target.direction(), "first:selected"));
        first.stars.remove("target");
        second.stars.put("target", target);
        assertEquals("second:target", aimed(sky, target.direction(), "first:selected"));
    }

    @Test
    void onlyTheSelectedGlobalKeyIsExcludedNotTheSameNameInOtherConstellations() {
        Constellation first = constellation("first", true);
        Constellation second = constellation("second", true);
        star(first, "Alpha", 0);
        Constellation.StarDef target = star(second, "Alpha", 30);
        assertEquals("second:Alpha", aimed(List.of(first, second), target.direction(), "first:Alpha"));
        assertNull(aimed(List.of(first), first.stars.get("Alpha").direction(), "first:Alpha"));
    }

    @Test
    void unpinnedConstellationsAndMissesHaveNoTarget() {
        Constellation visible = constellation("visible", true);
        Constellation hidden = constellation("hidden", false);
        star(visible, "Alpha", 0);
        Constellation.StarDef hiddenStar = star(hidden, "Beta", 90);
        assertNull(aimed(List.of(visible, hidden), hiddenStar.direction(), null));
        assertNull(aimed(List.of(visible), new Vector3f(0, -1, 0), null));
    }

    @Test
    void closestStarWinsRegardlessOfWhichConstellationIsFirst() {
        Constellation first = constellation("first", true);
        Constellation second = constellation("second", true);
        star(first, "near", 20);
        Constellation.StarDef centre = star(second, "centre", 21);
        assertEquals("second:centre", aimed(List.of(first, second), centre.direction(), null));
        assertEquals("second:centre", aimed(List.of(second, first), centre.direction(), null));
    }
    @Test
    void cachedLookupMatchesTheOriginalAndUsesTheInterpolatedAnchor() {
        Constellation first = constellation("first", true);
        Constellation second = constellation("second", true);
        star(first, "Alpha", 0);
        Constellation.StarDef target = star(second, "Beta", 40);
        var sky = List.of(first, second);
        java.util.Map<String, Vector3f> offsets = new java.util.LinkedHashMap<>();
        for (Constellation c : sky) {
            for (var entry : c.stars.entrySet()) {
                offsets.put(c.id + ":" + entry.getKey(), entry.getValue().direction().mul(80));
            }
        }
        Vector3f relativeEye = new Vector3f(0.4f, -0.2f, 0.1f);
        Vector3f look = SkyGeometry.directionToStar(relativeEye, target.direction().mul(80));
        String oldLookup = StarTargeting.closest(sky, look, 2, "first:Alpha",
                definition -> SkyGeometry.directionToStar(relativeEye, definition.direction().mul(80)));
        assertEquals(oldLookup, StarTargeting.closestOffsets(offsets, look, relativeEye, 2, "first:Alpha"));
        assertEquals("second:Beta", oldLookup);
        assertNull(StarTargeting.closestOffsets(offsets, new Vector3f(0, -1, 0), relativeEye, 2, null));
        assertEquals(80f, offsets.get("second:Beta").length(), 0.0001f);
    }

}
