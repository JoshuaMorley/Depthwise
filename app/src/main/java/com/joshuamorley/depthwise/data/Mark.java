package com.joshuamorley.depthwise.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A user-placed waypoint. */
public final class Mark {

    public final String id;
    public String name;
    public String note;
    public int color;
    public double lat;
    public double lon;
    public final long createdAt;

    public Mark(String id, String name, String note, int color, double lat, double lon, long createdAt) {
        this.id = id;
        this.name = name;
        this.note = note;
        this.color = color;
        this.lat = lat;
        this.lon = lon;
        this.createdAt = createdAt;
    }

    public static Mark create(String name, int color, double lat, double lon) {
        return new Mark(UUID.randomUUID().toString(), name, "", color, lat, lon, System.currentTimeMillis());
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("note", note);
        o.put("color", color);
        o.put("lat", lat);
        o.put("lon", lon);
        o.put("created", createdAt);
        return o;
    }

    public static Mark fromJson(JSONObject o) throws JSONException {
        return new Mark(o.getString("id"), o.optString("name", "Mark"), o.optString("note", ""),
                o.optInt("color", 0xFFFB8C00), o.getDouble("lat"), o.getDouble("lon"),
                o.optLong("created", System.currentTimeMillis()));
    }
}
