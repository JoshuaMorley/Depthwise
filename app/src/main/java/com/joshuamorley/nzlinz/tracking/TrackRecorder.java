package com.joshuamorley.nzlinz.tracking;

import android.content.Context;
import android.content.Intent;
import android.location.Location;

import androidx.core.content.ContextCompat;

import com.joshuamorley.nzlinz.Prefs;
import com.joshuamorley.nzlinz.data.Palette;
import com.joshuamorley.nzlinz.data.Track;
import com.joshuamorley.nzlinz.data.TrackStore;
import com.joshuamorley.nzlinz.util.Geo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Holds the track currently being recorded. All calls happen on the UI thread.
 *
 * <p>Manual mode: {@link #start()} / {@link #stop()} from the record button.
 * Auto mode: {@link #startAuto()} keeps recording, resuming the last track if it
 * ended recently, and rolls over to a new track after a break in fixes or a
 * jump in position (see settings).
 */
public final class TrackRecorder {

    public interface Listener {
        void onRecordingChanged(boolean recording, Track track);
        void onTrackPoint(Track track);
    }

    private static final int SAVE_EVERY_POINTS = 30;

    private static TrackRecorder instance;

    private final Context app;
    private final List<Listener> listeners = new ArrayList<>();
    private Track active;
    private int unsaved;
    private boolean startingAuto;

    public static synchronized TrackRecorder get(Context context) {
        if (instance == null) instance = new TrackRecorder(context.getApplicationContext());
        return instance;
    }

    private TrackRecorder(Context app) {
        this.app = app;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isRecording() {
        return active != null;
    }

    public Track active() {
        return active;
    }

    /** Manual start: always a new track. */
    public void start() {
        if (active != null) return;
        begin(newTrack());
    }

    /** Auto mode: continue the latest track if it ended within the break limit, else start a new one. */
    public void startAuto() {
        if (active != null || startingAuto) return;
        startingAuto = true;
        TrackStore store = TrackStore.get(app);
        store.whenLoaded(() -> {
            startingAuto = false;
            if (active != null || !Prefs.TRACK_AUTO.equals(new Prefs(app).trackMode())) return;
            Track latest = null;
            for (Track t : store.snapshot()) {
                if (t.size() > 0 && (latest == null || t.time(t.size() - 1) > latest.time(latest.size() - 1))) {
                    latest = t;
                }
            }
            long gap = new Prefs(app).autoGapMs();
            boolean resume = latest != null && System.currentTimeMillis() - latest.time(latest.size() - 1) < gap;
            begin(resume ? latest : newTrack());
        });
    }

    private Track newTrack() {
        TrackStore store = TrackStore.get(app);
        String name = new SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(new Date());
        Track t = Track.create(name, Palette.pick(store.count()));
        store.save(t);
        return t;
    }

    private void begin(Track t) {
        active = t;
        unsaved = 0;
        ContextCompat.startForegroundService(app, new Intent(app, TrackingService.class));
        for (Listener l : new ArrayList<>(listeners)) l.onRecordingChanged(true, active);
    }

    public void stop() {
        if (active == null) return;
        Track t = finish(active);
        active = null;
        app.stopService(new Intent(app, TrackingService.class));
        for (Listener l : new ArrayList<>(listeners)) l.onRecordingChanged(false, t);
    }

    /** Saves a finished track, or drops it if it never got going. */
    private Track finish(Track t) {
        TrackStore store = TrackStore.get(app);
        if (t.size() < 2) {
            store.delete(t);
        } else {
            store.persist(t);
            store.notifyChanged();
        }
        return t;
    }

    void onLocation(Location loc) {
        if (active == null) return;
        Prefs prefs = new Prefs(app);
        if (Prefs.TRACK_AUTO.equals(prefs.trackMode()) && active.size() > 0) {
            int last = active.size() - 1;
            long gap = loc.getTime() - active.time(last);
            double jump = Geo.distanceM(active.lat(last), active.lon(last), loc.getLatitude(), loc.getLongitude());
            if (gap > prefs.autoGapMs() || jump > prefs.autoJumpM()) {
                // Break or jump: close this track and carry on in a new one.
                Track old = finish(active);
                active = newTrack();
                unsaved = 0;
                for (Listener l : new ArrayList<>(listeners)) l.onRecordingChanged(false, old);
                for (Listener l : new ArrayList<>(listeners)) l.onRecordingChanged(true, active);
            }
        }
        active.add(loc.getLatitude(), loc.getLongitude(), loc.getTime());
        if (++unsaved >= SAVE_EVERY_POINTS) {
            unsaved = 0;
            TrackStore.get(app).persist(active);
        }
        for (Listener l : new ArrayList<>(listeners)) l.onTrackPoint(active);
    }

    /** Called if the system kills the service without the user stopping. */
    void onServiceStopped() {
        if (active != null) TrackStore.get(app).persist(active);
    }
}
