package com.joshuamorley.nzlinz.map;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import com.joshuamorley.nzlinz.charts.ChartStyler;
import com.joshuamorley.nzlinz.data.Io;
import com.joshuamorley.nzlinz.pmtiles.PmTilesInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Loads pack sprites (sprite.json + sprite.png, @2x on dense screens) and slices
 * them into individual icons named "&lt;prefix&gt;&lt;icon&gt;". Done in the app rather than
 * through the style's "sprite" key so any number of packs can each bring
 * their own sprite, from local files or URLs.
 */
public final class SpriteLoader {

    public static final class Icons {
        public final HashMap<String, Bitmap> normal = new HashMap<>();
        public final HashMap<String, Bitmap> sdf = new HashMap<>();
    }

    /** Sliced icons per prefix+base, so style rebuilds don't re-decode. */
    private static final Map<String, Icons> CACHE = new ConcurrentHashMap<>();

    private SpriteLoader() {}

    /** Loads all sprites in the background; the callback runs on the UI thread. */
    public static void load(List<ChartStyler.Sprite> sprites, float density, Consumer<Icons> done) {
        Io.DISK.execute(() -> {
            Icons all = new Icons();
            for (ChartStyler.Sprite s : sprites) {
                String key = s.prefix + "|" + s.base;
                Icons icons = CACHE.get(key);
                if (icons == null) {
                    try {
                        icons = loadOne(s, density);
                        CACHE.put(key, icons);
                    } catch (Exception e) {
                        continue; // missing sprite just means missing icons
                    }
                }
                all.normal.putAll(icons.normal);
                all.sdf.putAll(icons.sdf);
            }
            Io.main(() -> done.accept(all));
        });
    }

    private static Icons loadOne(ChartStyler.Sprite s, float density) throws Exception {
        byte[] json = null, png = null;
        int ratio = 1;
        if (density >= 1.5f) {
            try {
                json = fetch(s.base + "@2x.json");
                png = fetch(s.base + "@2x.png");
                ratio = 2;
            } catch (IOException ignored) {
                json = null;
            }
        }
        if (json == null) {
            json = fetch(s.base + ".json");
            png = fetch(s.base + ".png");
            ratio = 1;
        }
        Bitmap sheet = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (sheet == null) throw new IOException("Bad sprite image");
        JSONObject index = new JSONObject(new String(json, StandardCharsets.UTF_8));
        Icons icons = new Icons();
        for (Iterator<String> it = index.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject e = index.getJSONObject(name);
            int x = e.getInt("x"), y = e.getInt("y"), w = e.getInt("width"), h = e.getInt("height");
            if (w <= 0 || h <= 0 || x + w > sheet.getWidth() || y + h > sheet.getHeight()) continue;
            Bitmap icon = Bitmap.createBitmap(sheet, x, y, w, h);
            double pr = e.optDouble("pixelRatio", ratio);
            // MapLibre derives the icon's pixel ratio from the bitmap density.
            icon.setDensity((int) Math.round(DisplayMetrics.DENSITY_DEFAULT * pr));
            (e.optBoolean("sdf", false) ? icons.sdf : icons.normal).put(s.prefix + name, icon);
        }
        return icons;
    }

    private static byte[] fetch(String url) throws IOException {
        if (url.startsWith("file://")) {
            File f = new File(URI.create(url.replace(" ", "%20")).getPath());
            if (!f.isFile()) f = new File(url.substring("file://".length()));
            try (InputStream in = new FileInputStream(f)) {
                return PmTilesInfo.readAll(in);
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return PmTilesInfo.readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }
}
