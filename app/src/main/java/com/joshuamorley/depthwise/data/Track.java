package com.joshuamorley.depthwise.data;

import com.joshuamorley.depthwise.util.Geo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.UUID;

/** A recorded GPS track. Points are kept in primitive arrays to stay light on memory. */
public final class Track {

    public final String id;
    public String name;
    public int color;
    public boolean visible = true;
    public final long createdAt;

    private double[] lats = new double[256];
    private double[] lons = new double[256];
    private long[] times = new long[256];
    private int size;
    private double lengthM;

    public Track(String id, String name, int color, long createdAt) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.createdAt = createdAt;
    }

    public static Track create(String name, int color) {
        return new Track(UUID.randomUUID().toString(), name, color, System.currentTimeMillis());
    }

    public synchronized void add(double lat, double lon, long time) {
        if (size == lats.length) {
            int cap = size * 2;
            lats = Arrays.copyOf(lats, cap);
            lons = Arrays.copyOf(lons, cap);
            times = Arrays.copyOf(times, cap);
        }
        if (size > 0) lengthM += Geo.distanceM(lats[size - 1], lons[size - 1], lat, lon);
        lats[size] = lat;
        lons[size] = lon;
        times[size] = time;
        size++;
    }

    public synchronized int size() { return size; }
    public synchronized double lat(int i) { return lats[i]; }
    public synchronized double lon(int i) { return lons[i]; }
    public synchronized long time(int i) { return times[i]; }
    public synchronized double lengthM() { return lengthM; }

    public synchronized long durationMs() {
        return size < 2 ? 0 : times[size - 1] - times[0];
    }

    /** Snapshot of coordinates as [lon, lat] pairs for map rendering. */
    public synchronized double[][] lonLats() {
        double[][] out = new double[size][];
        for (int i = 0; i < size; i++) out[i] = new double[]{lons[i], lats[i]};
        return out;
    }

    /** Bounding box {minLat, minLon, maxLat, maxLon}, or null when empty. */
    public synchronized double[] bounds() {
        if (size == 0) return null;
        double minLat = 90, minLon = 180, maxLat = -90, maxLon = -180;
        for (int i = 0; i < size; i++) {
            minLat = Math.min(minLat, lats[i]);
            maxLat = Math.max(maxLat, lats[i]);
            minLon = Math.min(minLon, lons[i]);
            maxLon = Math.max(maxLon, lons[i]);
        }
        return new double[]{minLat, minLon, maxLat, maxLon};
    }

    public synchronized JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("color", color);
        o.put("visible", visible);
        o.put("created", createdAt);
        // Flat [lon, lat, time, lon, lat, time, ...] keeps the file compact.
        JSONArray pts = new JSONArray();
        for (int i = 0; i < size; i++) {
            pts.put(Math.round(lons[i] * 1e7) / 1e7);
            pts.put(Math.round(lats[i] * 1e7) / 1e7);
            pts.put(times[i]);
        }
        o.put("pts", pts);
        return o;
    }

    public static Track fromJson(JSONObject o) throws JSONException {
        Track t = new Track(o.getString("id"), o.optString("name", "Track"),
                o.optInt("color", 0xFFE53935), o.optLong("created", System.currentTimeMillis()));
        t.visible = o.optBoolean("visible", true);
        JSONArray pts = o.optJSONArray("pts");
        if (pts != null) {
            for (int i = 0; i + 2 < pts.length(); i += 3) {
                t.add(pts.getDouble(i + 1), pts.getDouble(i), pts.getLong(i + 2));
            }
        }
        return t;
    }
}
