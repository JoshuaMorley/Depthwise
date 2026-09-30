package com.joshuamorley.nzlinz.charts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A chart pack: a folder (or remote base URL) containing {@value #CONFIG_NAME}
 * which tells the app how to draw the pack's tiles and how to use them
 * (tap-to-inspect labels, shallow-water alarm rules, layer groups, sprites).
 * See CHARTPACK.md for the format.
 */
public final class ChartPack {

    public static final String CONFIG_NAME = "chartpack.json";
    public static final int FORMAT = 1;

    public final String id;
    public final String title;
    public final String attribution;
    /** {minLon, minLat, maxLon, maxLat} or null. */
    public final double[] bounds;
    /** Local folder, or null for a remote pack. */
    public final File dir;
    /** Base URL ending in '/', or null for a local pack. */
    public final String baseUrl;
    public final String glyphs;
    /** Sprite id to sprite base path/URL (no extension), as written in the config. */
    public final Map<String, String> sprites;
    public final JSONObject sources;
    public final JSONArray layers;
    public final List<Group> groups;
    public final List<AlarmRule> alarms;
    public final List<InspectRule> inspect;
    public final List<NearestRule> nearest;

    /**
     * "Nearest X to this spot" lookups, read straight from the archive at its
     * most detailed zoom (so nothing is missing because of label thinning).
     */
    public static final class NearestRule {
        /** Pack-local source key; must be a .pmtiles source. */
        public final String source;
        public final String sourceLayer;
        public final String title;
        /** Template for the value, e.g. "{depth} m". */
        public final String text;
        /** Numeric depth field (metres) for the depth readout / warnings, or null. */
        public final String depthField;
        public final double maxDistanceM;

        NearestRule(String source, String sourceLayer, String title, String text, String depthField,
                    double maxDistanceM) {
            this.source = source;
            this.sourceLayer = sourceLayer;
            this.title = title;
            this.text = text;
            this.depthField = depthField;
            this.maxDistanceM = maxDistanceM;
        }

        static NearestRule fromAlarm(AlarmRule a) {
            String label = a.label != null && !a.label.contains("{") ? a.label : "sounding";
            return new NearestRule(a.source, a.sourceLayer, "Nearest " + label, null, a.depthField, 2000);
        }
    }

    public static final class Group {
        public final String id;
        public final String title;
        public final boolean defaultVisible;

        Group(String id, String title, boolean defaultVisible) {
            this.id = id;
            this.title = title;
            this.defaultVisible = defaultVisible;
        }
    }

    /** Features that count as a danger when shallower than the boat's safe depth. */
    public static final class AlarmRule {
        /** Pack-local source key. */
        public final String source;
        public final String sourceLayer;
        /** "point" (checked by distance) or "area" (checked at the boat's position). */
        public final String geometry;
        public final String depthField;
        /** Optional string field holding tenths, e.g. d_int="7", d_dec="2" -> 7.2. */
        public final String depthDecimalField;
        /** MapLibre filter expression (JSON) or null. */
        public final JSONArray filter;
        /** Treat features with no depth as dangerous (e.g. drying rocks). */
        public final boolean dangerWhenNoDepth;
        /** Name shown in the warning, e.g. "sounding" or "rock". Template fields allowed. */
        public final String label;

        AlarmRule(String source, String sourceLayer, String geometry, String depthField,
                  String depthDecimalField, JSONArray filter, boolean dangerWhenNoDepth, String label) {
            this.source = source;
            this.sourceLayer = sourceLayer;
            this.geometry = geometry;
            this.depthField = depthField;
            this.depthDecimalField = depthDecimalField;
            this.filter = filter;
            this.dangerWhenNoDepth = dangerWhenNoDepth;
            this.label = label;
        }
    }

    /** How a tapped feature from some layers is presented. */
    public static final class InspectRule {
        /** Pack-local layer ids this rule covers. */
        public final List<String> layers;
        /** Templates like "{name}" or "Light {label}"; the first non-blank one is used. */
        public final List<String> title;
        public final List<String> subtitle;
        /** Ordered field -> display label; empty means show every property. */
        public final Map<String, String> fields;
        public final List<String> hide;

        InspectRule(List<String> layers, List<String> title, List<String> subtitle,
                    Map<String, String> fields, List<String> hide) {
            this.layers = layers;
            this.title = title;
            this.subtitle = subtitle;
            this.fields = fields;
            this.hide = hide;
        }
    }

    private ChartPack(String id, String title, String attribution, double[] bounds, File dir, String baseUrl,
                      String glyphs, Map<String, String> sprites, JSONObject sources, JSONArray layers,
                      List<Group> groups, List<AlarmRule> alarms, List<InspectRule> inspect,
                      List<NearestRule> nearest) {
        this.id = id;
        this.title = title;
        this.attribution = attribution;
        this.bounds = bounds;
        this.dir = dir;
        this.baseUrl = baseUrl;
        this.glyphs = glyphs;
        this.sprites = sprites;
        this.sources = sources;
        this.layers = layers;
        this.groups = groups;
        this.alarms = alarms;
        this.inspect = inspect;
        this.nearest = nearest;
    }

    public static ChartPack parse(String json, File dir, String baseUrl, String fallbackId) throws JSONException {
        JSONObject o = new JSONObject(json);
        int format = o.optInt("format", FORMAT);
        if (format > FORMAT) throw new JSONException("Pack format " + format + " is newer than this app supports");
        if (!o.has("sources") || !o.has("layers")) throw new JSONException("Pack needs \"sources\" and \"layers\"");

        String id = safeId(o.optString("id", fallbackId));
        double[] bounds = null;
        JSONArray b = o.optJSONArray("bounds");
        if (b != null && b.length() == 4) {
            bounds = new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
        }

        Map<String, String> sprites = new LinkedHashMap<>();
        Object sp = o.opt("sprites");
        if (sp instanceof JSONObject) {
            JSONObject s = (JSONObject) sp;
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                String k = it.next();
                sprites.put(k, s.getString(k));
            }
        } else if (sp instanceof String) {
            sprites.put("default", (String) sp);
        }

        List<Group> groups = new ArrayList<>();
        JSONArray g = o.optJSONArray("groups");
        if (g != null) {
            for (int i = 0; i < g.length(); i++) {
                JSONObject gi = g.getJSONObject(i);
                groups.add(new Group(gi.getString("id"), gi.optString("title", gi.getString("id")),
                        gi.optBoolean("visible", true)));
            }
        }

        List<AlarmRule> alarms = new ArrayList<>();
        JSONArray a = o.optJSONArray("alarms");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                alarms.add(new AlarmRule(r.getString("source"), r.optString("sourceLayer", null),
                        r.optString("geometry", "point").toLowerCase(Locale.ROOT),
                        r.optString("depthField", null), r.optString("depthDecimalField", null),
                        r.optJSONArray("filter"), r.optBoolean("dangerWhenNoDepth", false),
                        r.optString("label", "shallow water")));
            }
        }

        List<InspectRule> inspect = new ArrayList<>();
        JSONArray in = o.optJSONArray("inspect");
        if (in != null) {
            for (int i = 0; i < in.length(); i++) {
                JSONObject r = in.getJSONObject(i);
                List<String> ls = new ArrayList<>();
                JSONArray la = r.optJSONArray("layers");
                if (la != null) for (int j = 0; j < la.length(); j++) ls.add(la.getString(j));
                Map<String, String> fields = new LinkedHashMap<>();
                JSONObject f = r.optJSONObject("fields");
                if (f != null) {
                    for (Iterator<String> it = f.keys(); it.hasNext(); ) {
                        String k = it.next();
                        fields.put(k, f.getString(k));
                    }
                }
                List<String> hide = new ArrayList<>();
                JSONArray h = r.optJSONArray("hide");
                if (h != null) for (int j = 0; j < h.length(); j++) hide.add(h.getString(j));
                inspect.add(new InspectRule(ls, templates(r.opt("title")), templates(r.opt("subtitle")),
                        fields, hide));
            }
        }

        List<NearestRule> nearest = new ArrayList<>();
        JSONArray ne = o.optJSONArray("nearest");
        if (ne != null) {
            for (int i = 0; i < ne.length(); i++) {
                JSONObject r = ne.getJSONObject(i);
                nearest.add(new NearestRule(r.getString("source"), r.optString("sourceLayer", null),
                        r.optString("title", "Nearest"), r.optString("text", null),
                        r.optString("depthField", null), r.optDouble("maxDistanceM", 2000)));
            }
        }

        return new ChartPack(id, o.optString("title", id), o.optString("attribution", ""), bounds, dir, baseUrl,
                o.optString("glyphs", null), Collections.unmodifiableMap(sprites),
                o.getJSONObject("sources"), o.getJSONArray("layers"),
                Collections.unmodifiableList(groups), Collections.unmodifiableList(alarms),
                Collections.unmodifiableList(inspect), Collections.unmodifiableList(nearest));
    }

    /**
     * Resolves a path from the config to something MapLibre can load.
     * Relative paths are resolved against the pack folder / base URL;
     * .pmtiles files get the pmtiles:// prefix.
     */
    public String resolve(String path, boolean pmtilesAware) {
        if (path == null) return null;
        String p = path.trim();
        boolean isPmtiles = pmtilesAware && p.toLowerCase(Locale.ROOT).contains(".pmtiles");
        if (p.startsWith("pmtiles://")) return p;
        String abs;
        if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("file://") || p.startsWith("asset://")) {
            abs = p;
        } else if (dir != null) {
            abs = "file://" + new File(dir, p).getAbsolutePath();
        } else {
            abs = baseUrl + p;
        }
        return isPmtiles ? "pmtiles://" + abs : abs;
    }

    /** A template string or an array of fallbacks. */
    private static List<String> templates(Object o) throws JSONException {
        List<String> out = new ArrayList<>();
        if (o instanceof String) out.add((String) o);
        else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        }
        return Collections.unmodifiableList(out);
    }

    static String safeId(String s) {
        String id = s.replaceAll("[^A-Za-z0-9_-]", "_");
        return id.isEmpty() ? "pack" : id;
    }
}
