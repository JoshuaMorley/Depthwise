package com.joshuamorley.nzlinz.charts;

import com.joshuamorley.nzlinz.pmtiles.PmTilesInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One chart layer the map can draw: a PMTiles file on the device or a remote tile service. */
public final class ChartSource {

    public enum Kind {
        /** Folder in the charts directory with a chartpack.json. */
        PACK,
        /** chartpack.json at a URL; relative paths resolve against it. */
        REMOTE_PACK,
        /** .pmtiles in the app's charts folder with no config (auto-styled). */
        FILE_PMTILES,
        /** pmtiles archive over HTTP(S). */
        REMOTE_PMTILES,
        /** Raster XYZ template: https://host/{z}/{x}/{y}.png */
        XYZ_RASTER,
        /** TileJSON endpoint describing vector or raster tiles. */
        TILEJSON
    }

    public final String id;
    public final Kind kind;
    public final String title;
    /** Local file for FILE_PMTILES, otherwise null. */
    public final File file;
    /** Remote URL (template / archive / TileJSON), or null for files. */
    public final String url;
    public boolean enabled = true;

    /** Resolved description for config-less sources; null for packs or if resolving failed. */
    public Info info;
    /** Parsed config for PACK / REMOTE_PACK. */
    public ChartPack pack;
    public String error;

    public boolean isPack() {
        return kind == Kind.PACK || kind == Kind.REMOTE_PACK;
    }

    /** True when this source has what it needs to be drawn. */
    public boolean isDrawable() {
        return isPack() ? pack != null : info != null;
    }

    /** {minLon, minLat, maxLon, maxLat} or null. */
    public double[] bounds() {
        if (pack != null) return pack.bounds;
        return info != null ? info.bounds : null;
    }

    public ChartSource(String id, Kind kind, String title, File file, String url) {
        this.id = id;
        this.kind = kind;
        this.title = title;
        this.file = file;
        this.url = url;
    }

    public boolean isRemote() {
        return kind != Kind.FILE_PMTILES && kind != Kind.PACK;
    }

    /** The URL MapLibre should load. For TileJSON the tiles array is used directly. */
    public String mapLibreUrl() {
        switch (kind) {
            case FILE_PMTILES:
                return "pmtiles://file://" + file.getAbsolutePath();
            case REMOTE_PMTILES:
                return url.startsWith("pmtiles://") ? url : "pmtiles://" + url;
            default:
                return url;
        }
    }

    /** What we know about a source's tiles, enough to build style layers. */
    public static final class Info {
        public final boolean vector;
        public final List<PmTilesInfo.VectorLayer> layers;
        /** {minLon, minLat, maxLon, maxLat} or null. */
        public final double[] bounds;
        public final int minZoom;
        public final int maxZoom;
        public final int tileSize;
        /** Tile URL templates, only for TileJSON sources. */
        public final List<String> tiles;

        public Info(boolean vector, List<PmTilesInfo.VectorLayer> layers, double[] bounds,
                    int minZoom, int maxZoom, int tileSize, List<String> tiles) {
            this.vector = vector;
            this.layers = layers == null ? Collections.emptyList() : layers;
            this.bounds = bounds;
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.tileSize = tileSize;
            this.tiles = tiles == null ? Collections.emptyList() : tiles;
        }

        public static Info from(PmTilesInfo p) {
            return new Info(p.isVector(), p.vectorLayers,
                    p.hasBounds() ? new double[]{p.minLon, p.minLat, p.maxLon, p.maxLat} : null,
                    p.minZoom, p.maxZoom, p.isVector() ? 512 : 256, null);
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("vector", vector);
            o.put("minzoom", minZoom);
            o.put("maxzoom", maxZoom);
            o.put("tileSize", tileSize);
            if (bounds != null) {
                o.put("bounds", new JSONArray().put(bounds[0]).put(bounds[1]).put(bounds[2]).put(bounds[3]));
            }
            JSONArray t = new JSONArray();
            for (String s : tiles) t.put(s);
            o.put("tiles", t);
            JSONArray vl = new JSONArray();
            for (PmTilesInfo.VectorLayer l : layers) {
                JSONObject fields = new JSONObject();
                for (Map.Entry<String, String> e : l.fields.entrySet()) fields.put(e.getKey(), e.getValue());
                vl.put(new JSONObject().put("id", l.id).put("fields", fields));
            }
            o.put("vector_layers", vl);
            return o;
        }

        public static Info fromJson(JSONObject o) throws JSONException {
            double[] bounds = null;
            JSONArray b = o.optJSONArray("bounds");
            if (b != null && b.length() == 4) {
                bounds = new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
            }
            JSONArray t = o.optJSONArray("tiles");
            List<String> tiles = new java.util.ArrayList<>();
            if (t != null) for (int i = 0; i < t.length(); i++) tiles.add(t.getString(i));
            List<PmTilesInfo.VectorLayer> layers = PmTilesInfo.parseVectorLayers(o.optJSONArray("vector_layers"));
            boolean vector = o.has("vector") ? o.getBoolean("vector") : !layers.isEmpty();
            return new Info(vector, layers, bounds,
                    o.optInt("minzoom", 0), o.optInt("maxzoom", 22), o.optInt("tileSize", vector ? 512 : 256), tiles);
        }
    }
}
