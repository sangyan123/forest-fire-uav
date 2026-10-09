package com.forestfire.uav.fire;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 几何工具：WGS84 经纬度 → JTS Point/MultiPolygon（SRID 4326，PostGIS geometry 列），
 * Haversine 球面距离（火情去重 100m 精算、调度距离估算用），
 * 以及火场多边形的周长累加与 GeoJSON 坐标序列化。
 */
public final class GeoUtils {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    /** 地球平均半径（米），Haversine 用 */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private GeoUtils() {
    }

    /** 经纬度 → geometry(Point,4326)；任一为 null 返回 null（可空列允许） */
    public static Point toPoint(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude)); // JTS: (x=lon, y=lat)
    }

    /**
     * [lat,lon] 闭合环 → geometry(MultiPolygon,4326)。
     * fire_polygon.polygon 列类型以 DDL 为准（MultiPolygon 而非 Polygon）：
     * AI 返回的单环先构造 Polygon 再包一层 MultiPolygon。输入未闭合时自动闭合。
     */
    public static MultiPolygon toMultiPolygon(List<double[]> ring) {
        if (ring == null || ring.size() < 3) {
            throw new IllegalArgumentException("ring must contain at least 3 [lat,lon] points");
        }
        Coordinate[] coords = new Coordinate[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            coords[i] = new Coordinate(ring.get(i)[1], ring.get(i)[0]); // [lat,lon] → (x=lon, y=lat)
        }
        if (!coords[0].equals2D(coords[coords.length - 1])) {
            Coordinate[] closed = new Coordinate[coords.length + 1];
            System.arraycopy(coords, 0, closed, 0, coords.length);
            closed[coords.length] = new Coordinate(coords[0]);
            coords = closed;
        }
        Polygon polygon = GEOMETRY_FACTORY.createPolygon(coords);
        return GEOMETRY_FACTORY.createMultiPolygon(new Polygon[]{polygon});
    }

    /** 闭合环周长（米）：逐边 Haversine 累加；环不合法返回 null */
    public static Double perimeterMeters(List<double[]> ring) {
        if (ring == null || ring.size() < 3) {
            return null;
        }
        double total = 0.0;
        for (int i = 0; i < ring.size(); i++) {
            double[] a = ring.get(i);
            double[] b = ring.get((i + 1) % ring.size());
            total += haversineMeters(a[0], a[1], b[0], b[1]);
        }
        return Math.round(total * 10) / 10.0;
    }

    /**
     * MultiPolygon → GeoJSON 坐标数组（首个多边形的外环，[lat,lon] 顺序，Leaflet 直接可用）。
     * 空几何/非多边形返回 null。
     */
    public static List<List<Double>> multiPolygonToGeoJson(MultiPolygon multiPolygon) {
        if (multiPolygon == null || multiPolygon.isEmpty()) {
            return null;
        }
        Geometry geometry = multiPolygon.getGeometryN(0);
        if (!(geometry instanceof Polygon polygon)) {
            return null;
        }
        List<List<Double>> out = new ArrayList<>();
        for (Coordinate c : polygon.getExteriorRing().getCoordinates()) {
            out.add(List.of(c.y, c.x)); // 还原 [lat,lon] 顺序
        }
        return out;
    }

    /** [lat,lon] 闭合环 → geometry(Polygon,4326)（patrol_area.geometry 列；未闭合自动闭合） */
    public static Polygon toPolygon(List<double[]> ring) {
        if (ring == null || ring.size() < 3) {
            throw new IllegalArgumentException("ring must contain at least 3 [lat,lon] points");
        }
        Coordinate[] coords = new Coordinate[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            coords[i] = new Coordinate(ring.get(i)[1], ring.get(i)[0]); // [lat,lon] → (x=lon, y=lat)
        }
        if (!coords[0].equals2D(coords[coords.length - 1])) {
            Coordinate[] closed = new Coordinate[coords.length + 1];
            System.arraycopy(coords, 0, closed, 0, coords.length);
            closed[coords.length] = new Coordinate(coords[0]);
            coords = closed;
        }
        return GEOMETRY_FACTORY.createPolygon(coords);
    }

    /** MultiPolygon 首个多边形（patrol_area.geometry(Polygon) 取网格矩形用；空返回 null） */
    public static Polygon firstPolygonOf(MultiPolygon multiPolygon) {
        if (multiPolygon == null || multiPolygon.isEmpty()
                || !(multiPolygon.getGeometryN(0) instanceof Polygon polygon)) {
            return null;
        }
        return polygon;
    }

    /** Polygon 外环 → GeoJSON [lat,lon] 坐标数组（建议视图用；空返回 null） */
    public static List<List<Double>> polygonToGeoJson(Polygon polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return null;
        }
        List<List<Double>> out = new ArrayList<>();
        for (Coordinate c : polygon.getExteriorRing().getCoordinates()) {
            out.add(List.of(c.y, c.x)); // 还原 [lat,lon] 顺序
        }
        return out;
    }

    /** 几何外接框 [minLat, minLon, maxLat, maxLon]（JTS x=lon, y=lat；空返回 null） */
    public static double[] envelopeLatLon(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        var env = geometry.getEnvelopeInternal();
        return new double[]{env.getMinY(), env.getMinX(), env.getMaxY(), env.getMaxX()};
    }

    /** 由面积反推等效半径（米）：r=√(A/π)（fire_polygon 无半径列，查询侧派生） */
    public static Double radiusFromArea(Double areaSquareMeters) {
        return areaSquareMeters == null ? null : Math.sqrt(areaSquareMeters / Math.PI);
    }

    /** Haversine 球面距离（米） */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}

