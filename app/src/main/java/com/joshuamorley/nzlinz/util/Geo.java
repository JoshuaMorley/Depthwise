package com.joshuamorley.nzlinz.util;

import java.util.Locale;

/** Distance maths and marine-friendly formatting. */
public final class Geo {

    private static final double EARTH_RADIUS_M = 6371008.8;
    public static final double METRES_PER_NM = 1852.0;

    private Geo() {}

    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial true bearing in degrees 0..360. */
    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Point reached travelling distanceM on bearingDeg. Returns {lat, lon}. */
    public static double[] destination(double lat, double lon, double bearingDeg, double distanceM) {
        double d = distanceM / EARTH_RADIUS_M;
        double b = Math.toRadians(bearingDeg);
        double p1 = Math.toRadians(lat), l1 = Math.toRadians(lon);
        double p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
        double l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1),
                Math.cos(d) - Math.sin(p1) * Math.sin(p2));
        return new double[]{Math.toDegrees(p2), ((Math.toDegrees(l2) + 540) % 360) - 180};
    }

    public static String formatDistance(double metres, String units) {
        if ("km".equals(units)) {
            if (metres < 1000) return String.format(Locale.US, "%.0f m", metres);
            return String.format(Locale.US, metres < 10000 ? "%.2f km" : "%.1f km", metres / 1000);
        }
        double nm = metres / METRES_PER_NM;
        if (nm < 0.1) return String.format(Locale.US, "%.0f m", metres);
        return String.format(Locale.US, nm < 10 ? "%.2f nm" : "%.1f nm", nm);
    }

    public static String formatSpeed(double metresPerSecond, String units) {
        if ("kmh".equals(units)) return String.format(Locale.US, "%.1f km/h", metresPerSecond * 3.6);
        return String.format(Locale.US, "%.1f kn", metresPerSecond * 3600 / METRES_PER_NM);
    }

    public static String formatBearing(double deg) {
        return String.format(Locale.US, "%03.0f°", (deg + 360) % 360);
    }

    /** Degrees and decimal minutes, e.g. 36°50.123′S 174°45.678′E. */
    public static String formatLatLon(double lat, double lon) {
        return dm(Math.abs(lat)) + (lat < 0 ? "S" : "N") + "  " + dm(Math.abs(lon)) + (lon < 0 ? "W" : "E");
    }

    private static String dm(double v) {
        int deg = (int) v;
        double min = (v - deg) * 60;
        return String.format(Locale.US, "%d°%06.3f′", deg, min);
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return String.format(Locale.US, "%dh %02dm", h, m);
        return String.format(Locale.US, "%dm %02ds", m, s % 60);
    }
}
