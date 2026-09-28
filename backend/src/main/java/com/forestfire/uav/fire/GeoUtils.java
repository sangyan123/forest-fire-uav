package com.forestfire.uav.fire;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * 几何工具：WGS84 经纬度 → JTS Point（SRID 4326，PostGIS geometry 列），
 * 以及 Haversine 球面距离（火情去重 100m 精算、调度距离估算用）。
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
