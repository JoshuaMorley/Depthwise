package com.joshuamorley.depthwise.charts;

import com.joshuamorley.depthwise.data.Io;
import com.joshuamorley.depthwise.pmtiles.MvtPoints;
import com.joshuamorley.depthwise.pmtiles.PmTilesReader;
import com.joshuamorley.depthwise.util.Geo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the nearest point feature (e.g. a sounding) to a position by reading
 * the archive's most detailed zoom directly, so results don't depend on what
 * the map currently shows. Runs on its own background thread.
 */
public final class NearestFinder {

    public static final class Hit {
        public final ChartPack.NearestRule rule;
        public final double lat, lon;
        public final double distanceM, bearingDeg;
        /** Depth in metres when the rule names a depth field, else NaN. */
        public final double depthM;
        public final String text;

        Hit(ChartPack.NearestRule rule, double lat, double lon, double distanceM, double bearingDeg,
            double depthM, String text) {
            this.rule = rule;
            this.lat = lat;
            this.lon = lon;
            this.distanceM = distanceM;
            this.bearingDeg = bearingDeg;
            this.depthM = depthM;
            this.text = text;
        }
    }

    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "depthwise-nearest");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Map<String, PmTilesReader> READERS = new HashMap<>();
    private static final Pattern FIELD = Pattern.compile("\\{([^}]+)\\}");

    private NearestFinder() {}

    /**
     * Looks up the nearest feature for each rule; the callback gets one hit per rule
     * that found something (in rule order), on the UI thread.
     */
    public static void find(List<ChartStyler.Nearest> specs, double lat, double lon, Consumer<List<Hit>> done) {
        LOOKUP.execute(() -> {
            List<Hit> hits = new ArrayList<>();
            for (ChartStyler.Nearest spec : specs) {
                try {
                    Hit h = findOne(spec, lat, lon);
                    if (h != null) hits.add(h);
                } catch (Exception ignored) {
                    // unreadable archive: just no result for this rule
                }
            }
            Io.main(() -> done.accept(hits));
        });
    }

    private static Hit findOne(ChartStyler.Nearest spec, double lat, double lon) throws Exception {
        PmTilesReader reader = reader(spec);
        int z = reader.maxZoom;
        int n = 1 << z;
        double wx = (lon + 180) / 360 * n;
        double latR = Math.toRadians(lat);
        double wy = (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n;
        int tx = (int) Math.floor(wx), ty = (int) Math.floor(wy);
        // Width of one tile in metres here; used to know when further rings can't be closer.
        double tileM = 40075016.686 * Math.cos(latR) / n;

        MvtPoints.PointFeature best = null;
        double bestD = Double.MAX_VALUE;
        int maxRing = Math.max(1, (int) Math.ceil(spec.rule.maxDistanceM / tileM));
        maxRing = Math.min(maxRing, 8);
        for (int ring = 0; ring <= maxRing; ring++) {
            // Anything in this ring or beyond is at least (ring - 1) tiles away.
            if (best != null && bestD < (ring - 1) * tileM) break;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) continue;
                    int x = tx + dx, y = ty + dy;
                    if (y < 0 || y >= n) continue;
                    x = ((x % n) + n) % n;
                    byte[] tile = reader.getTile(z, x, y);
                    if (tile == null) continue;
                    for (MvtPoints.PointFeature f : MvtPoints.decode(tile, spec.rule.sourceLayer, z, x, y)) {
                        double d = Geo.distanceM(lat, lon, f.lat, f.lon);
                        if (d < bestD) {
                            bestD = d;
                            best = f;
                        }
                    }
                }
            }
        }
        if (best == null || bestD > spec.rule.maxDistanceM) return null;

        double depth = Double.NaN;
        if (spec.rule.depthField != null) {
            Object v = best.props.get(spec.rule.depthField);
            if (v instanceof Number) depth = ((Number) v).doubleValue();
            else if (v != null) {
                try {
                    depth = Double.parseDouble(v.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String text = spec.rule.text != null ? fill(spec.rule.text, best.props)
                : !Double.isNaN(depth) ? String.format(java.util.Locale.US, "%.1f m", depth) : null;
        return new Hit(spec.rule, best.lat, best.lon, bestD, Geo.bearingDeg(lat, lon, best.lat, best.lon),
                depth, text);
    }

    private static PmTilesReader reader(ChartStyler.Nearest spec) throws Exception {
        synchronized (READERS) {
            PmTilesReader r = READERS.get(spec.key());
            if (r == null) {
                r = spec.file != null ? PmTilesReader.open(spec.file) : PmTilesReader.open(spec.url);
                READERS.put(spec.key(), r);
            }
            return r;
        }
    }

    private static String fill(String template, Map<String, Object> props) {
        Matcher m = FIELD.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = props.get(m.group(1));
            String s = v == null ? "" : v instanceof Double && ((Double) v) == Math.rint((Double) v)
                    ? String.valueOf(((Double) v).longValue()) : String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }
}
