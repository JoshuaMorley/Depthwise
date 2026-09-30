package com.joshuamorley.depthwise.charts;

import com.joshuamorley.depthwise.pmtiles.PmTilesInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds one MapLibre style from every enabled chart source.
 *
 * <p>Chart packs supply their own layers (see CHARTPACK.md); ids are namespaced
 * per pack so several regions can share layer, source and sprite names. Layers
 * from all packs are ordered by kind (backgrounds, areas, lines, points, labels)
 * so one region's background never covers another's marks.
 *
 * <p>Plain .pmtiles files without a config get a generic style built from
 * their metadata.
 */
public final class ChartStyler {

    public static final String BACKGROUND = "#EAF3F8";
    public static final String DEFAULT_GLYPHS =
            "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf";
    public static final String FONT = "Noto Sans Regular";

    /** Depth attribute names used by the generic style, most specific first. */
    private static final String[] DEPTH_FIELDS = {
            "drval1", "depth_min", "min_depth", "mindepth", "valsou", "valdco",
            "depth", "depth_m", "sounding", "dep", "elevation", "elev", "ele",
    };

    /** An alarm rule bound to a style source id. */
    public static final class Alarm {
        public final String sourceId;
        public final ChartPack.AlarmRule rule;
        /** True when the depth attribute is an elevation (negative underwater). */
        public final boolean negate;

        Alarm(String sourceId, ChartPack.AlarmRule rule, boolean negate) {
            this.sourceId = sourceId;
            this.rule = rule;
            this.negate = negate;
        }
    }

    /** A sprite to load and register under an icon-name prefix. */
    public static final class Sprite {
        public final String prefix;
        /** Resolved base URL without extension (file://, http(s)://). */
        public final String base;

        Sprite(String prefix, String base) {
            this.prefix = prefix;
            this.base = base;
        }
    }

    /** A user-toggleable layer group, merged by id across packs. */
    public static final class Group {
        public final String id;
        public final String title;
        public final boolean defaultVisible;
        public final List<String> layerIds = new ArrayList<>();

        Group(String id, String title, boolean defaultVisible) {
            this.id = id;
            this.title = title;
            this.defaultVisible = defaultVisible;
        }
    }

    /** A nearest-feature lookup bound to a concrete archive. */
    public static final class Nearest {
        public final ChartPack.NearestRule rule;
        /** Local archive, or null when {@link #url} is set. */
        public final java.io.File file;
        public final String url;

        Nearest(ChartPack.NearestRule rule, java.io.File file, String url) {
            this.rule = rule;
            this.file = file;
            this.url = url;
        }

        public String key() {
            // Size and time are included so a replaced file isn't served from a stale reader.
            return file != null ? file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified() : url;
        }
    }

    public interface GroupVisibility {
        boolean isVisible(String groupId, boolean defaultVisible);
    }

    public static final class Result {
        public final String styleJson;
        /** Chart layers that can be tapped for feature info. */
        public final List<String> queryableLayerIds;
        /** Inspect rule per style layer id (absent = show raw properties). */
        public final Map<String, ChartPack.InspectRule> inspect;
        /** Heading per style layer id for feature info. */
        public final Map<String, String> layerTitles;
        public final List<Alarm> alarms;
        public final List<Sprite> sprites;
        public final List<Group> groups;
        public final List<String> attributions;
        public final List<Nearest> nearest;

        Result(String styleJson, List<String> queryable, Map<String, ChartPack.InspectRule> inspect,
               Map<String, String> layerTitles, List<Alarm> alarms, List<Sprite> sprites,
               List<Group> groups, List<String> attributions, List<Nearest> nearest) {
            this.nearest = Collections.unmodifiableList(nearest);
            this.styleJson = styleJson;
            this.queryableLayerIds = Collections.unmodifiableList(queryable);
            this.inspect = Collections.unmodifiableMap(inspect);
            this.layerTitles = Collections.unmodifiableMap(layerTitles);
            this.alarms = Collections.unmodifiableList(alarms);
            this.sprites = Collections.unmodifiableList(sprites);
            this.groups = Collections.unmodifiableList(groups);
            this.attributions = Collections.unmodifiableList(attributions);
        }
    }

    /** A layer waiting to be ordered into the style. */
    private static final class Pending {
        final JSONObject layer;
        final double rank;
        final int seq;

        Pending(JSONObject layer, double rank, int seq) {
            this.layer = layer;
            this.rank = rank;
            this.seq = seq;
        }
    }

    private ChartStyler() {}

    /**
     * @param minSafeDepthM highlight/label threshold for generic styles; 0 disables
     */
    public static Result build(List<ChartSource> sources, double minSafeDepthM, boolean showShallow,
                               boolean depthLabels, GroupVisibility visibility) {
        Builder b = new Builder(minSafeDepthM, showShallow, depthLabels, visibility);
        try {
            for (ChartSource s : sources) {
                if (!s.enabled || !s.isDrawable()) continue;
                if (s.isPack()) b.addPack(s.pack);
                else b.addGeneric(s);
            }
            return b.finish();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Builder {
        final double safe;
        final boolean showShallow, depthLabels;
        final GroupVisibility visibility;
        final JSONObject sources = new JSONObject();
        final List<Pending> pending = new ArrayList<>();
        final List<String> queryable = new ArrayList<>();
        final Map<String, ChartPack.InspectRule> inspect = new HashMap<>();
        final Map<String, String> titles = new HashMap<>();
        final List<Alarm> alarms = new ArrayList<>();
        final List<Sprite> sprites = new ArrayList<>();
        final Map<String, Group> groups = new LinkedHashMap<>();
        final List<String> attributions = new ArrayList<>();
        final List<Nearest> nearest = new ArrayList<>();
        String glyphs;
        int seq;
        int genericIdx;

        Builder(double safe, boolean showShallow, boolean depthLabels, GroupVisibility visibility) {
            this.safe = safe;
            this.showShallow = showShallow;
            this.depthLabels = depthLabels;
            this.visibility = visibility;
        }

        // ---------------------------------------------------------------- packs

        void addPack(ChartPack pack) throws JSONException {
            String ns = uniqueNamespace(pack.id);
            if (glyphs == null && pack.glyphs != null) glyphs = pack.resolve(pack.glyphs, false);
            if (!pack.attribution.isEmpty() && !attributions.contains(pack.attribution)) {
                attributions.add(pack.attribution);
            }
            for (ChartPack.Group g : pack.groups) {
                if (!groups.containsKey(g.id)) groups.put(g.id, new Group(g.id, g.title, g.defaultVisible));
            }
            for (Map.Entry<String, String> sp : pack.sprites.entrySet()) {
                sprites.add(new Sprite(ns + ":" + sp.getKey() + ":", pack.resolve(sp.getValue(), false)));
            }

            for (Iterator<String> it = pack.sources.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject src = new JSONObject(pack.sources.getJSONObject(key).toString());
                if (src.has("url")) src.put("url", pack.resolve(src.getString("url"), true));
                JSONArray tiles = src.optJSONArray("tiles");
                if (tiles != null) {
                    JSONArray resolved = new JSONArray();
                    for (int i = 0; i < tiles.length(); i++) resolved.put(pack.resolve(tiles.getString(i), false));
                    src.put("tiles", resolved);
                }
                sources.put(ns + "/" + key, src);
            }

            // Group of each source, so highlights follow their layer toggle (e.g. Soundings).
            Map<String, String> sourceGroup = new HashMap<>();
            for (int i = 0; i < pack.layers.length(); i++) {
                JSONObject l = pack.layers.getJSONObject(i);
                if (l.has("source") && l.has("group") && !sourceGroup.containsKey(l.getString("source"))) {
                    sourceGroup.put(l.getString("source"), l.getString("group"));
                }
            }
            for (int i = 0; i < pack.alarms.size(); i++) {
                ChartPack.AlarmRule r = pack.alarms.get(i);
                Alarm alarm = new Alarm(ns + "/" + r.source, r, false);
                alarms.add(alarm);
                if (showShallow && safe > 0) {
                    addShallowHighlight(ns + "/__shallow" + i, alarm, sourceGroup.get(r.source));
                }
            }

            List<ChartPack.NearestRule> nearestRules = pack.nearest;
            if (nearestRules.isEmpty()) {
                // Older packs: an unfiltered point alarm with a numeric depth (i.e. soundings)
                // doubles as a nearest-sounding lookup.
                nearestRules = new ArrayList<>();
                for (ChartPack.AlarmRule a : pack.alarms) {
                    if (a.filter == null && a.depthField != null && a.depthDecimalField == null) {
                        nearestRules.add(ChartPack.NearestRule.fromAlarm(a));
                    }
                }
            }
            for (ChartPack.NearestRule r : nearestRules) {
                JSONObject src = pack.sources.optJSONObject(r.source);
                String url = src != null ? src.optString("url", "") : "";
                if (!url.toLowerCase(Locale.ROOT).contains(".pmtiles")) continue;
                if (url.startsWith("pmtiles://")) url = url.substring("pmtiles://".length());
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    nearest.add(new Nearest(r, null, url));
                } else if (url.startsWith("file://")) {
                    nearest.add(new Nearest(r, new java.io.File(url.substring("file://".length())), null));
                } else if (pack.dir != null) {
                    nearest.add(new Nearest(r, new java.io.File(pack.dir, url), null));
                } else if (pack.baseUrl != null) {
                    nearest.add(new Nearest(r, null, pack.baseUrl + url));
                }
            }

            String defaultSprite = pack.sprites.size() == 1 ? pack.sprites.keySet().iterator().next() : null;
            for (int i = 0; i < pack.layers.length(); i++) {
                JSONObject def = pack.layers.getJSONObject(i);
                String type = def.optString("type");
                if ("background".equals(type)) continue; // would cover every other pack
                String localId = def.optString("id", "layer" + i);
                String id = ns + "/" + localId;

                JSONObject layer = new JSONObject(def.toString());
                layer.put("id", id);
                if (layer.has("source")) layer.put("source", ns + "/" + layer.getString("source"));
                if (layer.has("sourceLayer")) {
                    layer.put("source-layer", layer.get("sourceLayer"));
                    layer.remove("sourceLayer");
                }
                String spriteId = (String) layer.remove("sprite");
                if (spriteId == null) spriteId = defaultSprite;
                String group = (String) layer.remove("group");
                Object z = layer.remove("z");

                JSONObject layout = layer.optJSONObject("layout");
                if (layout != null && layout.has("icon-image") && spriteId != null) {
                    String prefix = ns + ":" + spriteId + ":";
                    Object icon = layout.get("icon-image");
                    layout.put("icon-image", icon instanceof String
                            ? prefix + icon
                            : arr("concat", prefix, arr("to-string", icon)));
                }
                if (group != null) {
                    Group g = groups.get(group);
                    if (g == null) {
                        g = new Group(group, group, true);
                        groups.put(group, g);
                    }
                    g.layerIds.add(id);
                    if (!visibility.isVisible(g.id, g.defaultVisible)) {
                        if (layout == null) {
                            layout = new JSONObject();
                            layer.put("layout", layout);
                        }
                        layout.put("visibility", "none");
                    }
                }

                double rank = z instanceof Number ? ((Number) z).doubleValue() : rankOf(type);
                pending.add(new Pending(layer, rank, seq++));
                if (!"raster".equals(type) && !"hillshade".equals(type)) {
                    queryable.add(id);
                    titles.put(id, pack.title);
                    for (ChartPack.InspectRule r : pack.inspect) {
                        if (r.layers.isEmpty() || r.layers.contains(localId)) {
                            inspect.put(id, r);
                            break;
                        }
                    }
                }
            }
        }

        /** Red shading over area features an alarm rule says are too shallow. */
        private void addShallowHighlight(String id, Alarm a, String group) throws JSONException {
            JSONArray danger = dangerFilter(a, safe);
            JSONArray isPolygon = arr("match", arr("geometry-type"), arr("Polygon", "MultiPolygon"), true, false);
            JSONObject area = new JSONObject()
                    .put("id", id + "/area")
                    .put("type", "fill")
                    .put("source", a.sourceId)
                    .put("filter", danger != null ? arr("all", isPolygon, danger) : isPolygon)
                    .put("paint", new JSONObject().put("fill-color", "#E53935").put("fill-opacity", 0.3)
                            .put("fill-antialias", false));
            if (a.rule.sourceLayer != null) area.put("source-layer", a.rule.sourceLayer);
            if (group != null) {
                Group g = groups.get(group);
                if (g == null) {
                    g = new Group(group, group, true);
                    groups.put(group, g);
                }
                g.layerIds.add(id + "/area");
                if (!visibility.isVisible(g.id, g.defaultVisible)) {
                    area.put("layout", new JSONObject().put("visibility", "none"));
                }
            }
            // Areas only: ringing every shallow sounding cluttered the chart.
            pending.add(new Pending(area, 1.5, seq++));  // above areas, below lines
        }

        private String uniqueNamespace(String base) throws JSONException {
            String ns = base;
            int n = 2;
            while (hasSourcePrefix(ns + "/")) ns = base + "_" + n++;
            return ns;
        }

        private boolean hasSourcePrefix(String prefix) {
            for (Iterator<String> it = sources.keys(); it.hasNext(); ) {
                if (it.next().startsWith(prefix)) return true;
            }
            return false;
        }

        // ---------------------------------------------------------------- generic files

        void addGeneric(ChartSource s) throws JSONException {
            String srcId = "auto" + genericIdx++;
            sources.put(srcId, genericSourceJson(s));
            if (!s.info.vector) {
                pending.add(new Pending(new JSONObject()
                        .put("id", srcId + "/raster")
                        .put("type", "raster")
                        .put("source", srcId)
                        .put("paint", new JSONObject().put("raster-fade-duration", 0)), 0, seq++));
                return;
            }
            for (PmTilesInfo.VectorLayer vl : s.info.layers) addGenericVectorLayer(s.title, srcId, vl);
        }

        private void addGenericVectorLayer(String title, String srcId, PmTilesInfo.VectorLayer vl)
                throws JSONException {
            String base = srcId + "/" + vl.id;
            String lower = vl.id.toLowerCase(Locale.ROOT);
            boolean land = lower.contains("land") || lower.contains("lndare") || lower.contains("coast")
                    || lower.contains("island");
            String depthField = null;
            boolean negate = false;
            if (!land) {
                outer:
                for (String candidate : DEPTH_FIELDS) {
                    for (Map.Entry<String, String> f : vl.fields.entrySet()) {
                        if (f.getKey().toLowerCase(Locale.ROOT).equals(candidate)
                                && !"String".equalsIgnoreCase(f.getValue())
                                && !"Boolean".equalsIgnoreCase(f.getValue())) {
                            depthField = f.getKey();
                            negate = candidate.startsWith("ele");
                            break outer;
                        }
                    }
                }
            }
            JSONArray depth = null;
            if (depthField != null) {
                JSONArray v = arr("to-number", arr("get", depthField));
                depth = negate ? arr("*", -1, v) : v;
                alarms.add(new Alarm(srcId, new ChartPack.AlarmRule(srcId, vl.id, "any", depthField, null,
                        null, false, vl.id), negate));
            }
            JSONArray isPolygon = arr("match", arr("geometry-type"), arr("Polygon", "MultiPolygon"), true, false);
            JSONArray isLine = arr("match", arr("geometry-type"), arr("LineString", "MultiLineString"), true, false);
            JSONArray isPoint = arr("match", arr("geometry-type"), arr("Point", "MultiPoint"), true, false);

            String fillId = base + "/fill";
            Object fillColor = land ? "#F2EAD3"
                    : depth != null ? arr("case", arr("has", depthField), depthRamp(depth), "#DCECF7")
                    : "#7FA7C4";
            double fillOpacity = land || depth != null ? 1 : 0.15;
            add(layer(fillId, "fill", srcId, vl.id, isPolygon)
                    .put("paint", new JSONObject().put("fill-color", fillColor)
                            .put("fill-opacity", fillOpacity).put("fill-antialias", false)), title, true);

            if (depth != null && showShallow && safe > 0) {
                add(layer(base + "/shallow", "fill", srcId, vl.id,
                        arr("all", isPolygon, arr("has", depthField), arr("<", depth, safe)))
                        .put("paint", new JSONObject().put("fill-color", "#E53935").put("fill-opacity", 0.35)
                                .put("fill-antialias", false)), title, false);
            }

            add(layer(base + "/line", "line", srcId, vl.id, null)
                    .put("paint", new JSONObject()
                            .put("line-color", land ? "#A89060" : depth != null ? "#5C8DB5" : "#4F6F86")
                            .put("line-width", arr("interpolate", arr("linear"), arr("zoom"), 8, 0.4, 14, 1.0, 18, 1.8))
                            .put("line-opacity", land ? 1 : 0.8)), title, true);

            if (depth != null && depthLabels) {
                add(layer(base + "/lineLabel", "symbol", srcId, vl.id, arr("all", isLine, arr("has", depthField)))
                        .put("minzoom", 12)
                        .put("layout", new JSONObject()
                                .put("symbol-placement", "line")
                                .put("text-field", depthText(depth))
                                .put("text-font", arr(FONT))
                                .put("text-size", 10))
                        .put("paint", labelPaint("#2F5F85")), title, false);
                add(layer(base + "/point", "symbol", srcId, vl.id, arr("all", isPoint, arr("has", depthField)))
                        .put("minzoom", 11)
                        .put("layout", new JSONObject()
                                .put("text-field", depthText(depth))
                                .put("text-font", arr(FONT))
                                .put("text-size", 11))
                        .put("paint", labelPaint(safe > 0
                                ? arr("case", arr("<", depth, safe), "#C62828", "#34566F")
                                : "#34566F")), title, true);
            } else {
                add(layer(base + "/point", "circle", srcId, vl.id, isPoint)
                        .put("paint", new JSONObject()
                                .put("circle-radius", arr("interpolate", arr("linear"), arr("zoom"), 8, 1.5, 16, 4))
                                .put("circle-color", "#6A1B9A")
                                .put("circle-stroke-color", "#FFFFFF")
                                .put("circle-stroke-width", 1)), title, true);
            }
        }

        private void add(JSONObject layer, String title, boolean query) throws JSONException {
            String id = layer.getString("id");
            pending.add(new Pending(layer, rankOf(layer.getString("type")), seq++));
            if (query) {
                queryable.add(id);
                titles.put(id, title + " · " + layer.optString("source-layer"));
            }
        }

        // ---------------------------------------------------------------- output

        Result finish() throws JSONException {
            Collections.sort(pending, (a, b) -> a.rank != b.rank ? Double.compare(a.rank, b.rank)
                    : Integer.compare(a.seq, b.seq));
            JSONArray layers = new JSONArray();
            layers.put(new JSONObject()
                    .put("id", "background")
                    .put("type", "background")
                    .put("paint", new JSONObject().put("background-color", BACKGROUND)));
            for (Pending p : pending) layers.put(p.layer);

            JSONObject style = new JSONObject();
            style.put("version", 8);
            style.put("name", "Charts");
            style.put("glyphs", glyphs != null ? glyphs : DEFAULT_GLYPHS);
            style.put("sources", sources);
            style.put("layers", layers);
            return new Result(style.toString(), queryable, inspect, titles, alarms, sprites,
                    new ArrayList<>(groups.values()), attributions, nearest);
        }
    }

    /**
     * MapLibre filter matching features an alarm rule treats as dangerous for a boat
     * needing {@code safe} metres. Shared by the shallow highlight and the alarm so they agree.
     * Returns null when every feature is dangerous (no depth field and no filter).
     */
    public static JSONArray dangerFilter(Alarm a, double safe) {
        ChartPack.AlarmRule rule = a.rule;
        JSONArray cond = null;
        if (rule.depthField != null) {
            double missing = a.negate ? -1e6 : 1e6;
            JSONArray depth = arr("to-number", arr("get", rule.depthField), missing);
            if (rule.depthDecimalField != null) {
                depth = arr("+", depth, arr("/", arr("to-number", arr("get", rule.depthDecimalField), 0), 10));
            }
            if (a.negate) depth = arr("*", -1, depth);
            cond = arr("<", depth, safe);
            if (rule.dangerWhenNoDepth) {
                cond = arr("any", cond, arr("!", arr("has", rule.depthField)),
                        arr("==", arr("to-string", arr("get", rule.depthField)), ""));
            }
        }
        if (rule.filter != null && cond != null) return arr("all", rule.filter, cond);
        return rule.filter != null ? rule.filter : cond;
    }

    /** Draw order: rasters, areas, lines, points, labels. */
    private static double rankOf(String type) {
        switch (type) {
            case "raster":
            case "hillshade":
                return 0;
            case "fill":
            case "fill-extrusion":
                return 1;
            case "line":
            case "heatmap":
                return 2;
            case "circle":
                return 3;
            case "symbol":
                return 4;
            default:
                return 2;
        }
    }

    private static JSONObject genericSourceJson(ChartSource s) throws JSONException {
        ChartSource.Info info = s.info;
        JSONObject o = new JSONObject();
        o.put("type", info.vector ? "vector" : "raster");
        if (s.kind == ChartSource.Kind.FILE_PMTILES || s.kind == ChartSource.Kind.REMOTE_PMTILES) {
            o.put("url", s.mapLibreUrl());
        } else {
            JSONArray tiles = new JSONArray();
            for (String t : info.tiles) tiles.put(t);
            o.put("tiles", tiles);
            o.put("minzoom", info.minZoom);
            o.put("maxzoom", info.maxZoom);
            if (info.bounds != null) {
                o.put("bounds", new JSONArray().put(info.bounds[0]).put(info.bounds[1])
                        .put(info.bounds[2]).put(info.bounds[3]));
            }
        }
        if (!info.vector) o.put("tileSize", info.tileSize);
        return o;
    }

    /** Chart-like depth ramp: drying green, then blues fading to near white offshore. */
    private static JSONArray depthRamp(JSONArray depth) {
        return arr("step", depth,
                "#C5D6A8",
                0, "#9CCBE8",
                2, "#B3D7EE",
                5, "#C9E3F3",
                10, "#DDEEF8",
                20, "#EDF6FB",
                50, "#F8FBFD");
    }

    private static JSONArray depthText(JSONArray depth) throws JSONException {
        return arr("number-format", depth, new JSONObject().put("max-fraction-digits", 1));
    }

    private static JSONObject labelPaint(Object color) throws JSONException {
        return new JSONObject()
                .put("text-color", color)
                .put("text-halo-color", "#FFFFFF")
                .put("text-halo-width", 1.2);
    }

    private static JSONObject layer(String id, String type, String source, String sourceLayer, JSONArray filter)
            throws JSONException {
        JSONObject o = new JSONObject()
                .put("id", id)
                .put("type", type)
                .put("source", source)
                .put("source-layer", sourceLayer);
        if (filter != null) o.put("filter", filter);
        return o;
    }

    static JSONArray arr(Object... items) {
        JSONArray a = new JSONArray();
        for (Object o : items) a.put(o);
        return a;
    }
}
