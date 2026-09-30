package com.joshuamorley.nzlinz.map;

import com.joshuamorley.nzlinz.charts.ChartPack;
import com.joshuamorley.nzlinz.charts.ChartStyler;
import com.joshuamorley.nzlinz.util.FeatureText;
import com.joshuamorley.nzlinz.util.Geo;

import org.json.JSONArray;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.sources.Source;
import org.maplibre.android.style.sources.VectorSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.MultiPoint;
import org.maplibre.geojson.MultiPolygon;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.List;

/**
 * Checks charted depths near the boat and along its course against the boat's
 * safe depth (draft + margin), using the alarm rules from the chart packs.
 *
 * <p>Uses querySourceFeatures, so it sees every feature in loaded tiles
 * (not just what's drawn after label collision), but only for tiles the map
 * currently has loaded.
 */
public final class ShallowWatch {

    public static final class Hazard {
        public final double depthM;       // NaN when unknown (e.g. drying rock without a sounding)
        public final double distanceM;    // from the boat
        public final String label;

        Hazard(double depthM, double distanceM, String label) {
            this.depthM = depthM;
            this.distanceM = distanceM;
            this.label = label;
        }
    }

    public static final class Result {
        /** Danger within the alarm radius of the boat now. */
        public Hazard now;
        /** Nearest danger along the course-ahead corridor. */
        public Hazard ahead;
    }

    private List<ChartStyler.Alarm> alarms = java.util.Collections.emptyList();
    private Expression[] filters = new Expression[0];
    private double filtersFor = Double.NaN;

    public void setAlarms(List<ChartStyler.Alarm> alarms) {
        this.alarms = alarms;
        this.filtersFor = Double.NaN;
    }

    public boolean hasRules() {
        return !alarms.isEmpty();
    }

    /**
     * @param radiusM   corridor half-width and "near the boat" distance
     * @param aheadM    look-ahead distance along the course (0 = off)
     */
    public Result check(Style style, double lat, double lon, double courseDeg, double aheadM,
                        double safeDepthM, double radiusM) {
        Result r = new Result();
        if (style == null || safeDepthM <= 0 || alarms.isEmpty()) return r;
        if (filtersFor != safeDepthM) buildFilters(safeDepthM);

        // Local planar frame in metres centred on the boat.
        double kx = Math.cos(Math.toRadians(lat)) * 111320.0, ky = 110540.0;
        double ex = 0, ey = 0;
        if (aheadM > 0) {
            ex = Math.sin(Math.toRadians(courseDeg)) * aheadM;
            ey = Math.cos(Math.toRadians(courseDeg)) * aheadM;
        }

        for (int i = 0; i < alarms.size(); i++) {
            ChartStyler.Alarm a = alarms.get(i);
            Source src = style.getSource(a.sourceId);
            if (!(src instanceof VectorSource)) continue;
            String[] layers = a.rule.sourceLayer != null ? new String[]{a.rule.sourceLayer} : new String[0];
            List<Feature> features;
            try {
                features = ((VectorSource) src).querySourceFeatures(layers, filters[i]);
            } catch (RuntimeException e) {
                continue;
            }
            for (Feature f : features) {
                Geometry g = f.geometry();
                if (g == null) continue;
                double depth = depthOf(a, f);
                if (!Double.isNaN(depth) && depth >= safeDepthM) continue; // filter is a pre-screen only
                String label = labelOf(a.rule, f);

                if (g instanceof Point || g instanceof MultiPoint) {
                    List<Point> pts = g instanceof Point
                            ? java.util.Collections.singletonList((Point) g) : ((MultiPoint) g).coordinates();
                    for (Point p : pts) {
                        double px = (p.longitude() - lon) * kx, py = (p.latitude() - lat) * ky;
                        double dNow = Math.hypot(px, py);
                        if (dNow <= radiusM) r.now = nearer(r.now, new Hazard(depth, dNow, label));
                        if (aheadM > 0) {
                            double t = projectT(px, py, ex, ey);
                            double cx = ex * t, cy = ey * t;
                            if (Math.hypot(px - cx, py - cy) <= radiusM) {
                                r.ahead = nearer(r.ahead, new Hazard(depth, t * aheadM, label));
                            }
                        }
                    }
                } else if (g instanceof Polygon || g instanceof MultiPolygon) {
                    List<List<List<Point>>> polys = g instanceof Polygon
                            ? java.util.Collections.singletonList(((Polygon) g).coordinates())
                            : ((MultiPolygon) g).coordinates();
                    for (List<List<Point>> poly : polys) {
                        if (contains(poly, lon, lat)) r.now = nearer(r.now, new Hazard(depth, 0, label));
                        if (aheadM > 0) {
                            int steps = 12;
                            for (int s = 1; s <= steps; s++) {
                                double t = s / (double) steps;
                                double plon = lon + ex * t / kx, plat = lat + ey * t / ky;
                                if (contains(poly, plon, plat)) {
                                    r.ahead = nearer(r.ahead, new Hazard(depth, t * aheadM, label));
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return r;
    }

    private void buildFilters(double safe) {
        filters = new Expression[alarms.size()];
        for (int i = 0; i < alarms.size(); i++) {
            // Same test as the map's shallow highlight, so what's red is what alarms.
            JSONArray full = ChartStyler.dangerFilter(alarms.get(i), safe);
            try {
                filters[i] = full == null ? null : Expression.Converter.convert(full.toString());
            } catch (RuntimeException e) {
                filters[i] = null;
            }
        }
        filtersFor = safe;
    }

    private static double depthOf(ChartStyler.Alarm a, Feature f) {
        ChartPack.AlarmRule rule = a.rule;
        if (rule.depthField == null) return Double.NaN;
        Double d = FeatureText.number(f, rule.depthField);
        if (d == null) return Double.NaN;
        if (rule.depthDecimalField != null) {
            Double dec = FeatureText.number(f, rule.depthDecimalField);
            if (dec != null) d = d + dec / 10.0;
        }
        return a.negate ? -d : d;
    }

    private static String labelOf(ChartPack.AlarmRule rule, Feature f) {
        String s = FeatureText.fill(rule.label, f);
        return s != null ? s : "shallow water";
    }

    private static Hazard nearer(Hazard a, Hazard b) {
        return a == null || b.distanceM < a.distanceM ? b : a;
    }

    /** Parameter t in [0,1] of the closest point on segment (0,0)-(ex,ey) to (px,py). */
    private static double projectT(double px, double py, double ex, double ey) {
        double len2 = ex * ex + ey * ey;
        if (len2 == 0) return 0;
        return Math.max(0, Math.min(1, (px * ex + py * ey) / len2));
    }

    /** Even-odd point-in-polygon over outer ring and holes. */
    private static boolean contains(List<List<Point>> rings, double x, double y) {
        boolean inside = false;
        for (List<Point> ring : rings) {
            for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
                double xi = ring.get(i).longitude(), yi = ring.get(i).latitude();
                double xj = ring.get(j).longitude(), yj = ring.get(j).latitude();
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
            }
        }
        return inside;
    }

    private static JSONArray arr(Object... items) {
        JSONArray a = new JSONArray();
        for (Object o : items) a.put(o);
        return a;
    }

    /** Distance text helper for warnings. */
    public static String describe(Hazard h, String units) {
        String depth = Double.isNaN(h.depthM) ? "" : String.format(java.util.Locale.US, "%.1f m ", h.depthM);
        return depth + h.label + " · " + Geo.formatDistance(h.distanceM, units);
    }
}
