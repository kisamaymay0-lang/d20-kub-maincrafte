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

    private static Matrix4f starMatrix(Vector3f direction, float distance, float scale) {
        return starMatrix(direction, distance, scale, 0f);
    }

    private static Matrix4f starMatrix(Vector3f direction, float distance, float scale, float spin) {
        var transform = SkyGeometry.starTransform(direction, distance, scale, spin);
        return new Matrix4f().translation(transform.getTranslation())
                .rotate(transform.getLeftRotation()).scale(transform.getScale())
                .rotate(transform.getRightRotation());
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
        Matrix4f transform = starMatrix(direction, 80f, 2f);
        assertVector(new Vector3f(direction).mul(80), transform.transformPosition(new Vector3f()));
        assertVector(new Vector3f(direction).negate(),
                transform.transformDirection(new Vector3f(0, 0, 1)).normalize());
    }

    @Test
    void walkingMovesTheWholeSkyButPreservesTheAimDirection() {
        Vector3f direction = new Constellation.StarDef("star", 123, 45).direction();
        Vector3f offset = starMatrix(direction, 80, 2)
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
        Vector3f offset = starMatrix(direction, 80, 2)
                .transformPosition(new Vector3f());
        Vector3f aim = SkyGeometry.directionToStar(new Vector3f(), offset);
        assertTrue(aim.dot(direction) >= Math.cos(Math.toRadians(2)));
        Vector3f turnedAway = new Vector3f(direction).rotateY((float) Math.toRadians(20));
        assertTrue(aim.dot(turnedAway) < Math.cos(Math.toRadians(2)));
    }

    @Test
    void starSpinLeavesItsCentreNormalAndHitDirectionUnchanged() {
        Vector3f direction = new Constellation.StarDef("star", 125, 55).direction();
        Vector3f centre = new Vector3f(direction).mul(80);
        Vector3f initialRight = starMatrix(direction, 80, 2, 0)
                .transformDirection(new Vector3f(1, 0, 0));
        for (int degrees : new int[]{0, 30, 90, 180, 270, 359, 360, -90}) {
            Matrix4f matrix = starMatrix(direction, 80, 2, (float) Math.toRadians(degrees));
            assertVector(centre, matrix.transformPosition(new Vector3f()));
            assertVector(new Vector3f(direction).negate(),
                    matrix.transformDirection(new Vector3f(0, 0, 1)).normalize());
            assertVector(direction, matrix.transformPosition(new Vector3f()).normalize());
        }
        Vector3f quarterTurnRight = starMatrix(direction, 80, 2, (float) (Math.PI / 2))
                .transformDirection(new Vector3f(1, 0, 0));
        assertEquals(0f, initialRight.dot(quarterTurnRight), EPSILON, "Картинка действительно вращается");
    }

    @Test
    void beamEndpointsKeepTheStarsApparentDirectionsWhileMovingBehindThem() {
        Vector3f a = new Constellation.StarDef("a", 10, 40).direction().mul(80);
        Vector3f b = new Constellation.StarDef("b", 80, 60).direction().mul(80);
        Matrix4f beam = SkyGeometry.beamTransform(a, b, 0.05f, 4f);
        assertNotNull(beam);
        Vector3f start = beam.transformPosition(new Vector3f(0f, -0.5f, 0f));
        Vector3f end = beam.transformPosition(new Vector3f(0f, 0.5f, 0f));
        assertTrue(start.length() > a.length());
        assertTrue(end.length() > b.length());
        assertVector(new Vector3f(a).normalize(), start.normalize());
        assertVector(new Vector3f(b).normalize(), end.normalize());
    }

    @Test
    void beamHasTheSameWidthEverywhereAndItsEntireSurfaceIsBehindTheStars() {
        // Далёкие друг от друга точки: обычная хорда здесь пересекла бы сферу.
        Vector3f a = new Constellation.StarDef("a", 0, 10).direction().mul(80);
        Vector3f b = new Constellation.StarDef("b", 150, 10).direction().mul(80);
        Matrix4f beam = SkyGeometry.beamTransform(a, b, 0.05f, 4f);
        assertNotNull(beam);
        float width = -1;
        for (float y : new float[]{-0.5f, -0.25f, 0f, 0.25f, 0.5f}) {
            Vector3f left = beam.transformPosition(new Vector3f(-0.5f, y, 0));
            Vector3f right = beam.transformPosition(new Vector3f(0.5f, y, 0));
            float currentWidth = left.distance(right);
            if (width < 0) {
                width = currentWidth;
            }
            assertEquals(width, currentWidth, EPSILON);
            for (float x : new float[]{-0.5f, 0f, 0.5f}) {
                assertTrue(beam.transformPosition(new Vector3f(x, y, 0)).length() >= 83.99f);
            }
        }
    }

    @Test
    void invalidOrAntipodalBeamsDoNotProduceNanOrAnEntityAtThePlayersEyes() {
        assertNull(SkyGeometry.beamTransform(new Vector3f(), new Vector3f(), 0.05f, 4));
        assertNull(SkyGeometry.beamTransform(new Vector3f(0, 80, 0), new Vector3f(0, 80, 0), 0.05f, 4));
        assertNull(SkyGeometry.beamTransform(new Vector3f(80, 0, 0), new Vector3f(-80, 0, 0), 0.05f, 4));
        assertNull(SkyGeometry.beamTransform(new Vector3f(Float.NaN, 1, 2), new Vector3f(0, 80, 0), 0.05f, 4));
    }

    @ParameterizedTest
    @CsvSource({"0,false", "12999,false", "13000,true", "22999,true", "23000,false", "23999,false",
            "24000,false", "37000,true", "47000,false"})
    void starsAppearAtNightAndDisappearAtDawn(long time, boolean visible) {
        assertEquals(visible, SkyGeometry.isNight(time));
    }
    @Test
    void beamEndpointsStillMeetStarsWhenTheObserverIsNotAtTheInertialSphereCenter() {
        Vector3f a = new Vector3f(0, 1, 1).normalize().mul(80);
        Vector3f b = new Vector3f(1, 1, 0).normalize().mul(80);
        Vector3f observer = new Vector3f(3, 1.25f, -2);
        Matrix4f transform = SkyGeometry.beamTransform(a, b, 0.05f, 4f, observer);
        assertNotNull(transform);
        Vector3f start = transform.transformPosition(new Vector3f(0, -0.5f, 0));
        Vector3f end = transform.transformPosition(new Vector3f(0, 0.5f, 0));
        assertTrue(new Vector3f(start).sub(observer).normalize().distance(new Vector3f(a).sub(observer).normalize()) < 0.0001f);
        assertTrue(new Vector3f(end).sub(observer).normalize().distance(new Vector3f(b).sub(observer).normalize()) < 0.0001f);
        assertTrue(start.length() > 80 && end.length() > 80);
    }

}
