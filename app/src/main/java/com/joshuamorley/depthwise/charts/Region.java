package com.joshuamorley.depthwise.charts;

import android.content.Context;

import com.joshuamorley.depthwise.pmtiles.PmTilesInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A named chart region from assets/regions.json (mirrors the tile build's region list).
 * A file named "<name>.pmtiles" (or starting with "<name>") takes that region's title.
 */
public final class Region {

    public final String name;
    public final String title;
    /** {minLon, minLat, maxLon, maxLat} */
    public final double[] bbox;

    Region(String name, String title, double[] bbox) {
        this.name = name;
        this.title = title;
        this.bbox = bbox;
    }

    private static List<Region> cache;

    public static synchronized List<Region> all(Context context) {
        if (cache != null) return cache;
        List<Region> out = new ArrayList<>();
        try (InputStream in = context.getAssets().open("regions.json")) {
            JSONArray arr = new JSONObject(new String(PmTilesInfo.readAll(in), StandardCharsets.UTF_8))
                    .getJSONArray("regions");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                JSONArray b = r.getJSONArray("bbox");
                out.add(new Region(r.getString("name"), r.optString("title", r.getString("name")),
                        new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)}));
            }
        } catch (Exception ignored) {
            // A missing or bad catalogue just means files keep their own names.
        }
        cache = Collections.unmodifiableList(out);
        return cache;
    }

    /** The region a chart file belongs to, matched on file name, or null. */
    public static Region forFileName(Context context, String fileName) {
        String base = fileName.toLowerCase(Locale.ROOT);
        if (base.endsWith(".pmtiles")) base = base.substring(0, base.length() - 8);
        Region best = null;
        for (Region r : all(context)) {
            if (base.equals(r.name) || base.startsWith(r.name + "_") || base.startsWith(r.name + "-")
                    || base.startsWith(r.name + ".")) {
                if (best == null || r.name.length() > best.name.length()) best = r;
            }
        }
        return best;
    }
}
