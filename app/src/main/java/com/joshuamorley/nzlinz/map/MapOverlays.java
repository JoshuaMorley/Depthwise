package com.joshuamorley.nzlinz.map;

import static org.maplibre.android.style.expressions.Expression.eq;
import static org.maplibre.android.style.expressions.Expression.geometryType;
import static org.maplibre.android.style.expressions.Expression.get;
import static org.maplibre.android.style.expressions.Expression.has;
import static org.maplibre.android.style.layers.PropertyFactory.symbolPlacement;
import static org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.textKeepUpright;
import static org.maplibre.android.style.expressions.Expression.literal;
import static org.maplibre.android.style.expressions.Expression.toColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.lineCap;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineDasharray;
import static org.maplibre.android.style.layers.PropertyFactory.lineJoin;
import static org.maplibre.android.style.layers.PropertyFactory.lineOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textAnchor;
import static org.maplibre.android.style.layers.PropertyFactory.textColor;
import static org.maplibre.android.style.layers.PropertyFactory.textField;
import static org.maplibre.android.style.layers.PropertyFactory.textFont;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloColor;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textOffset;
import static org.maplibre.android.style.layers.PropertyFactory.textOptional;
import static org.maplibre.android.style.layers.PropertyFactory.textSize;

import com.joshuamorley.nzlinz.charts.ChartStyler;
import com.joshuamorley.nzlinz.data.Mark;
import com.joshuamorley.nzlinz.data.Palette;
import com.joshuamorley.nzlinz.data.Track;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User data drawn over the charts: saved tracks, the live recording, marks,
 * the measure line and the course-ahead vector. Each has its own source so an
 * update only re-tiles what changed.
 */
public final class MapOverlays {

    public static final String MARKS_LAYER = "ov-marks";
    /** Deep orange: stands out on the chart's blues, greens and land buff. */
    private static final String MEASURE_COLOR = "#E65100";

    private static final String SRC_TRACKS = "ov-tracks";
    private static final String SRC_ACTIVE = "ov-active";
    private static final String SRC_MARKS = "ov-marks";
    private static final String SRC_MEASURE = "ov-measure";
    private static final String SRC_AHEAD = "ov-ahead";
    private static final String SRC_PROBE = "ov-probe";

    private GeoJsonSource tracks, active, marks, measure, ahead, probe;
    private LatLng probePoint, probeHit;
    private boolean labels;

    /** Adds overlay sources/layers to a freshly loaded style (they sit above all chart layers). */
    public void install(Style style, boolean withLabels) {
        labels = withLabels;
        tracks = new GeoJsonSource(SRC_TRACKS);
        active = new GeoJsonSource(SRC_ACTIVE);
        marks = new GeoJsonSource(SRC_MARKS);
        measure = new GeoJsonSource(SRC_MEASURE);
        ahead = new GeoJsonSource(SRC_AHEAD);
        style.addSource(tracks);
        style.addSource(active);
        style.addSource(marks);
        style.addSource(measure);
        style.addSource(ahead);

        style.addLayer(new LineLayer("ov-tracks-line", SRC_TRACKS).withProperties(
                lineColor(toColor(get("color"))),
                lineWidth(3f),
                lineOpacity(0.9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));

        style.addLayer(new LineLayer("ov-active-casing", SRC_ACTIVE).withProperties(
                lineColor("#FFFFFF"),
                lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));
        style.addLayer(new LineLayer("ov-active-line", SRC_ACTIVE).withProperties(
                lineColor(toColor(get("color"))),
                lineWidth(3.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));

        // Lines drawn over the chart get a white casing so they stand out on any water colour.
        style.addLayer(new LineLayer("ov-ahead-casing", SRC_AHEAD).withProperties(
                lineColor("#FFFFFF"),
                lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND)));
        style.addLayer(new LineLayer("ov-ahead-line", SRC_AHEAD).withProperties(
                lineColor(toColor(get("color"))),
                lineWidth(3f),
                lineDasharray(new Float[]{2f, 1.5f})));

        style.addLayer(new LineLayer("ov-measure-casing", SRC_MEASURE).withProperties(
                lineColor("#FFFFFF"),
                lineOpacity(0.9f),
                lineWidth(4.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));
        style.addLayer(new LineLayer("ov-measure-line", SRC_MEASURE).withProperties(
                lineColor(MEASURE_COLOR),
                lineWidth(2f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));
        style.addLayer(new CircleLayer("ov-measure-pts", SRC_MEASURE)
                .withFilter(eq(geometryType(), literal("Point")))
                .withProperties(
                        circleRadius(4f),
                        circleColor(MEASURE_COLOR),
                        circleStrokeColor("#FFFFFF"),
                        circleStrokeWidth(1.5f)));
        // Leg distances, written along the middle of each leg.
        style.addLayer(new SymbolLayer("ov-measure-labels", SRC_MEASURE)
                .withFilter(has("label"))
                .withProperties(
                        symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                        textField(get("label")),
                        textFont(new String[]{ChartStyler.FONT}),
                        textSize(13f),
                        textOffset(new Float[]{0f, -0.9f}),
                        textKeepUpright(true),
                        textAllowOverlap(true),
                        textIgnorePlacement(true),
                        textColor("#BF360C"),
                        textHaloColor("#FFFFFF"),
                        textHaloWidth(2f)));

        style.addLayer(new CircleLayer(MARKS_LAYER, SRC_MARKS).withProperties(
                circleRadius(8f),
                circleColor(toColor(get("color"))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2.5f)));
        if (withLabels) {
            style.addLayer(new SymbolLayer("ov-marks-label", SRC_MARKS).withProperties(
                    textField(get("name")),
                    textFont(new String[]{ChartStyler.FONT}),
                    textSize(12f),
                    textAnchor(Property.TEXT_ANCHOR_TOP),
                    textOffset(new Float[]{0f, 1.1f}),
                    textOptional(true),
                    textColor("#1B2B36"),
                    textHaloColor("#FFFFFF"),
                    textHaloWidth(1.5f)));
        }

        // Tapped-point crosshair (role=tap), plus the nearest looked-up feature (role=hit)
        // joined by a dashed line (role=link). Drawn on top of everything.
        probe = new GeoJsonSource(SRC_PROBE);
        style.addSource(probe);
        style.addLayer(new LineLayer("ov-probe-link-casing", SRC_PROBE)
                .withFilter(eq(get("role"), literal("link")))
                .withProperties(
                        lineColor("#FFFFFF"),
                        lineWidth(5f),
                        lineCap(Property.LINE_CAP_ROUND)));
        style.addLayer(new LineLayer("ov-probe-link", SRC_PROBE)
                .withFilter(eq(get("role"), literal("link")))
                .withProperties(
                        lineColor("#0B5A8C"),
                        lineWidth(2.5f),
                        lineDasharray(new Float[]{1.5f, 1.5f})));
        style.addLayer(new CircleLayer("ov-probe-hit", SRC_PROBE)
                .withFilter(eq(get("role"), literal("hit")))
                .withProperties(
                        circleRadius(7f),
                        circleColor("#FFFFFF"),
                        circleStrokeColor("#0B5A8C"),
                        circleStrokeWidth(3f)));
        style.addLayer(new CircleLayer("ov-probe-halo", SRC_PROBE).withFilter(eq(get("role"), literal("tap"))).withProperties(
                circleRadius(16f),
                circleColor("#0B5A8C"),
                circleOpacity(0.15f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(4f)));
        style.addLayer(new CircleLayer("ov-probe-ring", SRC_PROBE).withFilter(eq(get("role"), literal("tap"))).withProperties(
                circleRadius(16f),
                circleOpacity(0f),
                circleStrokeColor("#0B5A8C"),
                circleStrokeWidth(2.5f)));
        style.addLayer(new CircleLayer("ov-probe-dot", SRC_PROBE).withFilter(eq(get("role"), literal("tap"))).withProperties(
                circleRadius(3.5f),
                circleColor("#0B5A8C"),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(1.5f)));
        setProbe(probePoint, probeHit);
    }

    /**
     * Marks a tapped point, and optionally the nearest looked-up feature with a line to it.
     * Pass null to clear. Survives style reloads.
     */
    public void setProbe(LatLng point, LatLng hit) {
        probePoint = point;
        probeHit = hit;
        if (probe == null) return;
        List<Feature> features = new ArrayList<>();
        if (point != null) {
            Point tap = Point.fromLngLat(point.getLongitude(), point.getLatitude());
            if (hit != null) {
                Point h = Point.fromLngLat(hit.getLongitude(), hit.getLatitude());
                Feature link = Feature.fromGeometry(LineString.fromLngLats(Arrays.asList(tap, h)));
                link.addStringProperty("role", "link");
                features.add(link);
                Feature hf = Feature.fromGeometry(h);
                hf.addStringProperty("role", "hit");
                features.add(hf);
            }
            Feature tf = Feature.fromGeometry(tap);
            tf.addStringProperty("role", "tap");
            features.add(tf);
        }
        probe.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    public boolean isInstalled() {
        return tracks != null;
    }

    public void clear() {
        tracks = active = marks = measure = ahead = probe = null;
    }

    public void setTracks(List<Track> list, String excludeId) {
        if (tracks == null) return;
        List<Feature> features = new ArrayList<>();
        for (Track t : list) {
            if (!t.visible || t.size() < 2 || t.id.equals(excludeId)) continue;
            features.add(line(t));
        }
        tracks.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    public void setActive(Track t) {
        if (active == null) return;
        if (t == null || t.size() < 2) {
            active.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        } else {
            active.setGeoJson(line(t));
        }
    }

    public void setMarks(List<Mark> list) {
        if (marks == null) return;
        List<Feature> features = new ArrayList<>(list.size());
        for (Mark m : list) {
            Feature f = Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat));
            f.addStringProperty("id", m.id);
            f.addStringProperty("name", m.name);
            f.addStringProperty("color", Palette.hex(m.color));
            features.add(f);
        }
        marks.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    /**
     * @param legLabels text for each leg (points.size() - 1 entries), or null for none
     */
    public void setMeasure(List<LatLng> points, List<String> legLabels) {
        if (measure == null) return;
        List<Feature> features = new ArrayList<>();
        List<Point> pts = new ArrayList<>(points.size());
        for (LatLng p : points) {
            Point pt = Point.fromLngLat(p.getLongitude(), p.getLatitude());
            pts.add(pt);
            features.add(Feature.fromGeometry(pt));
        }
        // One feature per leg so each can carry its own distance label.
        for (int i = 1; i < pts.size(); i++) {
            Feature leg = Feature.fromGeometry(LineString.fromLngLats(Arrays.asList(pts.get(i - 1), pts.get(i))));
            if (legLabels != null && i - 1 < legLabels.size()) leg.addStringProperty("label", legLabels.get(i - 1));
            features.add(leg);
        }
        measure.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    /** Course-ahead vector; pass null to hide. */
    public void setAhead(LatLng from, LatLng to, boolean danger) {
        if (ahead == null) return;
        if (from == null || to == null) {
            ahead.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
            return;
        }
        Feature f = Feature.fromGeometry(LineString.fromLngLats(Arrays.asList(
                Point.fromLngLat(from.getLongitude(), from.getLatitude()),
                Point.fromLngLat(to.getLongitude(), to.getLatitude()))));
        f.addStringProperty("color", danger ? "#D32F2F" : "#0B5A8C");
        ahead.setGeoJson(f);
    }

    private static Feature line(Track t) {
        double[][] ll = t.lonLats();
        List<Point> pts = new ArrayList<>(ll.length);
        for (double[] p : ll) pts.add(Point.fromLngLat(p[0], p[1]));
        Feature f = Feature.fromGeometry(LineString.fromLngLats(pts));
        f.addStringProperty("id", t.id);
        f.addStringProperty("color", Palette.hex(t.color));
        return f;
    }
}
