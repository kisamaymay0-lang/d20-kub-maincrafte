package com.yourserver.adaptation;

import org.bukkit.Location;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SkyGeometryTest {

    private static final float EPSILON = 0.0001f;

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,-1", "90,1,0,0", "180,0,0,1", "270,-1,0,0"})
    void starAzimuthUsesWorldDirections(double azimuth, float x, float y, float z) {
        var star = new Constellation.StarDef("star", azimuth, 0);
        assertVector(new Vector3f(x, y, z), star.direction());
    }

    @Test
    void cameraYawAndPitchNeverRotateTheSkyAnchor() {
        for (float yaw : new float[]{-180, -90, 0, 37, 180, 359}) {
            for (float pitch : new float[]{-90, -40, 0, 60, 90}) {
                Location eye = new Location(null, 12, 70.62, -34, yaw, pitch);
                Location anchor = SkyGeometry.anchor(eye);
                assertEquals(eye.getX(), anchor.getX());
                assertEquals(eye.getY(), anchor.getY());
                assertEquals(eye.getZ(), anchor.getZ());
                assertEquals(0f, anchor.getYaw());
                assertEquals(0f, anchor.getPitch());
                assertEquals(yaw, eye.getYaw(), "Нельзя менять саму Location игрока");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"30,60", "180,0", "270,45", "0,90"})
    void rotatingTheSpriteDoesNotMoveItsCentre(double azimuth, double elevation) {
        Vector3f direction = new Constellation.StarDef("star", azimuth, elevation).direction();
        Matrix4f transform = SkyGeometry.starTransform(direction, 80f, 2f);
        assertVector(new Vector3f(direction).mul(80), transform.transformPosition(new Vector3f()));
        assertVector(new Vector3f(direction).negate(),
                transform.transformDirection(new Vector3f(0, 0, 1)).normalize());
    }

    @Test
    void walkingMovesTheWholeSkyButPreservesTheAimDirection() {
        Vector3f direction = new Constellation.StarDef("star", 123, 45).direction();
        Vector3f offset = SkyGeometry.starTransform(direction, 80, 2)
                .transformPosition(new Vector3f());
        for (Vector3f eye : new Vector3f[]{
                new Vector3f(0, 64, 0), new Vector3f(100, 100, -300), new Vector3f(-40, 20, 70)
        }) {
            Vector3f starInWorld = new Vector3f(eye).add(offset);
            assertVector(direction, starInWorld.sub(eye).normalize());
            assertVector(direction, SkyGeometry.directionToStar(new Vector3f(), offset));
        }
    }

    @Test
    void hitTestingUsesTheRenderedPositionNotACameraRelativeOffset() {
        Vector3f direction = new Constellation.StarDef("star", 60, 55).direction();
        Vector3f offset = SkyGeometry.starTransform(direction, 80, 2)
                .transformPosition(new Vector3f());
        Vector3f aim = SkyGeometry.directionToStar(new Vector3f(), offset);
        assertTrue(aim.dot(direction) >= Math.cos(Math.toRadians(2)));
        Vector3f turnedAway = new Vector3f(direction).rotateY((float) Math.toRadians(20));
        assertTrue(aim.dot(turnedAway) < Math.cos(Math.toRadians(2)));
    }

    @Test
    void lineEndsExactlyAtBothStarsInsteadOfStartingAtTheMidpoint() {
        Vector3f a = new Vector3f(10, 50, -30);
        Vector3f b = new Vector3f(-20, 60, 40);
        Matrix4f line = SkyGeometry.lineTransform(a, b, 0.4f);
        assertVector(a, line.transformPosition(new Vector3f(0.5f, 0f, 0.5f)));
        assertVector(b, line.transformPosition(new Vector3f(0.5f, 1f, 0.5f)));
    }

    @Test
    void coincidentStarsDoNotCreateANanTransform() {
        assertThrows(IllegalArgumentException.class,
                () -> SkyGeometry.lineTransform(new Vector3f(), new Vector3f(), 0.4f));
    }

    @ParameterizedTest
    @CsvSource({"0,false", "12999,false", "13000,true", "22999,true", "23000,false", "23999,false",
            "24000,false", "37000,true", "47000,false"})
    void starsAppearAtNightAndDisappearAtDawn(long time, boolean visible) {
        assertEquals(visible, SkyGeometry.isNight(time));
    }
}
