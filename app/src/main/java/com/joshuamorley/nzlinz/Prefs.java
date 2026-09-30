package com.joshuamorley.nzlinz;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** Typed access to the values edited on the settings screen. */
public final class Prefs {

    public static final String KEEP_SCREEN_ON = "keep_screen_on";
    public static final String COURSE_UP = "course_up";
    public static final String SHOW_SOG = "show_sog";
    public static final String SHOW_COG = "show_cog";
    public static final String SHOW_DEPTH = "show_depth";
    public static final String TRACK_MODE = "track_mode";
    public static final String AUTO_GAP_MIN = "auto_gap_min";
    public static final String AUTO_JUMP_M = "auto_jump_m";

    public static final String TRACK_AUTO = "auto";
    public static final String TRACK_MANUAL = "manual";
    public static final String TRACK_OFF = "off";
    public static final String DISTANCE_UNITS = "distance_units";
    public static final String SPEED_UNITS = "speed_units";
    public static final String DRAFT_M = "draft_m";
    public static final String SAFETY_MARGIN_M = "safety_margin_m";
    public static final String SHOW_SHALLOW = "show_shallow";
    public static final String SHALLOW_ALARM = "shallow_alarm";
    public static final String ALARM_VIBRATE = "alarm_vibrate";
    public static final String ALARM_SOUND = "alarm_sound";
    public static final String LOOK_AHEAD_MIN = "look_ahead_min";
    public static final String ALARM_RADIUS_M = "alarm_radius_m";
    public static final String TRACK_INTERVAL_S = "track_interval_s";
    public static final String TRACK_MIN_DISTANCE_M = "track_min_distance_m";
    public static final String DEPTH_LABELS = "depth_labels";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public SharedPreferences raw() {
        return sp;
    }

    public boolean keepScreenOn() { return sp.getBoolean(KEEP_SCREEN_ON, true); }
    public boolean courseUp() { return sp.getBoolean(COURSE_UP, false); }
    public boolean showSog() { return sp.getBoolean(SHOW_SOG, true); }
    public boolean showCog() { return sp.getBoolean(SHOW_COG, true); }
    public boolean showDepth() { return sp.getBoolean(SHOW_DEPTH, true); }

    /** {@link #TRACK_AUTO}, {@link #TRACK_MANUAL} or {@link #TRACK_OFF}. */
    public String trackMode() { return sp.getString(TRACK_MODE, TRACK_MANUAL); }
    /** Auto mode starts a new track after this long without a fix. */
    public long autoGapMs() { return (long) (parse(sp.getString(AUTO_GAP_MIN, "30"), 30) * 60_000); }
    /** Auto mode starts a new track when consecutive fixes are this far apart. */
    public double autoJumpM() { return parse(sp.getString(AUTO_JUMP_M, "1852"), 1852); }
    public boolean showShallow() { return sp.getBoolean(SHOW_SHALLOW, true); }
    public boolean shallowAlarm() { return sp.getBoolean(SHALLOW_ALARM, true); }
    public boolean alarmVibrate() { return sp.getBoolean(ALARM_VIBRATE, true); }
    public boolean alarmSound() { return sp.getBoolean(ALARM_SOUND, true); }
    public boolean depthLabels() { return sp.getBoolean(DEPTH_LABELS, true); }

    /** "nm" or "km". */
    public String distanceUnits() { return sp.getString(DISTANCE_UNITS, "nm"); }
    /** "kn" or "kmh". */
    public String speedUnits() { return sp.getString(SPEED_UNITS, "kn"); }

    /** Boat draft in metres, 0 when not set. */
    public double draftM() { return parse(sp.getString(DRAFT_M, ""), 0); }
    public double safetyMarginM() { return parse(sp.getString(SAFETY_MARGIN_M, "0.5"), 0.5); }
    public double lookAheadMin() { return parse(sp.getString(LOOK_AHEAD_MIN, "1"), 1); }
    public double alarmRadiusM() { return parse(sp.getString(ALARM_RADIUS_M, "50"), 50); }
    public int trackIntervalS() { return (int) parse(sp.getString(TRACK_INTERVAL_S, "2"), 2); }
    public float trackMinDistanceM() { return (float) parse(sp.getString(TRACK_MIN_DISTANCE_M, "5"), 5); }

    /** Water shallower than this (m, chart datum) is treated as dangerous. 0 = off. */
    public double minSafeDepthM() {
        double draft = draftM();
        return draft <= 0 ? 0 : draft + safetyMarginM();
    }

    private static double parse(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
