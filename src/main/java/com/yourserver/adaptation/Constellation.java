package com.yourserver.adaptation;

import org.bukkit.inventory.ItemStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные созвездия: звёзды (направления на небе), линии (рёбра) и награда.
 *
 * Звезда хранится как направление на небесной сфере:
 *  - azimuth (азимут) в градусах: 0 = север, 90 = восток, 180 = юг, 270 = запад;
 *  - elevation (высота) в градусах над горизонтом: 0 = горизонт, 90 = зенит.
 *
 * Направления одинаковы для всех игроков — поэтому карта звёзд у всех совпадает.
 */
public class Constellation {

    public final String id;
    public String name;
    public boolean pinned;
    public String starModel = "f8resurs:star";

    public final Map<String, StarDef> stars = new LinkedHashMap<>();
    public final List<EdgeDef> edges = new ArrayList<>();
    public RewardDef reward = new RewardDef();

    public Constellation(String id) {
        this.id = id;
        this.name = id;
    }

    public static class StarDef {

        public final String id;
        public double azimuth;
        public double elevation;

        public StarDef(String id, double azimuth, double elevation) {
            this.id = id;
            this.azimuth = azimuth;
            this.elevation = elevation;
        }

        /**
         * Единичный вектор направления в мировых координатах
         * (+X = восток, +Y = вверх, +Z = юг).
         */
        public Vector3f direction() {
            double az = Math.toRadians(azimuth);
            double el = Math.toRadians(
                    Math.max(0.0, Math.min(90.0, elevation))
            );

            float x = (float) (Math.sin(az) * Math.cos(el));
            float y = (float) Math.sin(el);
            float z = (float) (-Math.cos(az) * Math.cos(el));

            return new Vector3f(x, y, z);
        }
    }

    /** Линия (ребро) между двумя звёздами. */
    public static class EdgeDef {

        public final String a;
        public final String b;

        public EdgeDef(String a, String b) {
            this.a = a;
            this.b = b;
        }

        /** Нормализованный ключ ребра (порядок звёзд не важен). */
        public String key() {
            return a.compareTo(b) <= 0
                    ? (a + "|" + b)
                    : (b + "|" + a);
        }
    }

    public static class RewardDef {

        public String title = "";
        public String subtitle = "";
        public final List<String> commands = new ArrayList<>();
        public ItemStack item = null;
    }
}
