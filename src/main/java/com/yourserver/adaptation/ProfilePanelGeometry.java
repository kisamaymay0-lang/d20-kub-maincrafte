package com.yourserver.adaptation;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Общая геометрия картинки и наведения. Направление курсора никогда не двигает саму панель. */
final class ProfilePanelGeometry {
    enum Action { NONE, LIKE, DISLIKE, OPEN }
    record Rect(double x, double y, double width, double height) {
        boolean contains(double px, double py) {
            return Math.abs(px - x) <= width / 2 + 0.025 && Math.abs(py - y) <= height / 2 + 0.025;
        }
    }
    record Hit(double x, double y, double distance, Vector3d point) { }
    record Frame(Vector3d center, Vector3d right, Vector3d normal, double width, double height) {
        Vector3d point(double x, double y, double depth) {
            return new Vector3d(center).add(new Vector3d(right).mul(x)).add(0, y, 0).add(new Vector3d(normal).mul(depth));
        }
        Quaternionf rotation() {
            return new Quaternionf().setFromNormalized(new Matrix3f(new Vector3f(right), new Vector3f(0, 1, 0), new Vector3f(normal)));
        }
        Rect bounds() { return new Rect(0, 0, width, height); }
        Rect focusBounds() { return new Rect(-0.1, 0, width + 0.2, height); }
        Rect tooltipBridge(Rect tip) {
            Rect icon = medal();
            double left = icon.x() + icon.width() / 2 - 0.04;
            double rightEdge = tip.x() - tip.width() / 2 + 0.04;
            double bottom = Math.min(icon.y() - icon.height() / 2, tip.y() - tip.height() / 2);
            double top = Math.max(icon.y() + icon.height() / 2, tip.y() + tip.height() / 2);
            return new Rect((left + rightEdge) / 2, (bottom + top) / 2, Math.max(0, rightEdge - left), top - bottom);
        }
        double iconSize() { return height * 0.20; }
        double footerScale() { return height * 0.36; }
        double footerBottom() { return -height / 2 + height * 0.045; }
        private double pixel() { return footerScale() * 0.025; }
        Rect medal() { return new Rect(width / 2 - iconSize() / 2 - height * 0.055,
                footerBottom() + 15.5 * pixel(), iconSize(), iconSize()); }
        Rect openProfile() { return new Rect(0, footerBottom() + 5.5 * pixel(), 100 * pixel(), 10 * pixel()); }
        Rect vote(boolean like, int likes, int dislikes) {
            // Цифра: 6 px, пробел: 4 px, значок: 8 px + 1 px advance; разделитель: 18 px.
            double left = Integer.toString(likes).length() * 6 + 13;
            double rightWidth = Integer.toString(dislikes).length() * 6 + 13;
            double total = left + 18 + rightWidth;
            return new Rect((like ? -total / 2 + left / 2 : total / 2 - rightWidth / 2) * pixel(),
                    footerBottom() + 25.5 * pixel(), Math.max((like ? left : rightWidth) * pixel(), width * 0.19), 8 * pixel());
        }
        Action action(Hit hit, int likes, int dislikes) {
            if (hit == null) return Action.NONE;
            if (openProfile().contains(hit.x(), hit.y())) return Action.OPEN;
            if (vote(true, likes, dislikes).contains(hit.x(), hit.y())) return Action.LIKE;
            if (vote(false, likes, dislikes).contains(hit.x(), hit.y())) return Action.DISLIKE;
            return Action.NONE;
        }
        Frame translated(Vector3d offset) {
            return new Frame(new Vector3d(center).add(offset), new Vector3d(right), new Vector3d(normal), width, height);
        }
        Rect tooltip(double width, double height) { return new Rect(this.width / 2 + 0.06 + width / 2, 0, width, height); }
        Hit intersect(Vector3d eye, Vector3d direction, double maxDistance) {
            if (!eye.isFinite() || !direction.isFinite() || direction.lengthSquared() < 1e-12) return null;
            Vector3d ray = new Vector3d(direction).normalize();
            double denominator = ray.dot(normal);
            if (Math.abs(denominator) < 1e-8) return null;
            double distance = new Vector3d(center).sub(eye).dot(normal) / denominator;
            if (distance < 0 || distance > maxDistance) return null;
            Vector3d point = new Vector3d(eye).add(new Vector3d(ray).mul(distance));
            Vector3d relative = new Vector3d(point).sub(center);
            return new Hit(relative.dot(right), relative.y, distance, point);
        }
        boolean approximately(Frame other) {
            return other != null && center.distanceSquared(other.center) < 0.00001
                    && normal.distanceSquared(other.normal) < 0.00001
                    && Math.abs(width - other.width) < 0.001 && Math.abs(height - other.height) < 0.001;
        }
    }

    private ProfilePanelGeometry() { }

    static Frame beside(Vector3d viewerEye, Vector3d feet, double playerWidth, double playerHeight) {
        double height = Math.clamp(playerHeight * 0.9, 0.45, 3.6);
        double width = Math.clamp(height * 1.48, 0.9, 3.0);
        Vector3d normal = new Vector3d(viewerEye.x - feet.x, 0, viewerEye.z - feet.z);
        if (normal.lengthSquared() < 1e-8) normal.set(0, 0, -1); else normal.normalize();
        Vector3d right = new Vector3d(0, 1, 0).cross(normal).normalize();
        Vector3d center = new Vector3d(feet).add(0, playerHeight / 2, 0)
                .add(new Vector3d(right).mul(playerWidth / 2 + 0.14 + width / 2))
                .add(new Vector3d(normal).mul(0.12));
        return new Frame(center, right, normal, width, height);
    }
}
