package com.joshuamorley.depthwise.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Saved tracks, one JSON file each under files/tracks. Callbacks run on the UI thread. */
public final class TrackStore {

    public interface Listener {
        void onTracksChanged(List<Track> tracks);
    }

    private static TrackStore instance;

    private final File dir;
    private final List<Track> tracks = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private boolean loaded;

    public static synchronized TrackStore get(Context context) {
        if (instance == null) instance = new TrackStore(context.getApplicationContext());
        return instance;
    }

    private TrackStore(Context app) {
        dir = new File(app.getFilesDir(), "tracks");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public void addListener(Listener l) {
        listeners.add(l);
        if (loaded) l.onTracksChanged(snapshot());
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Track> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(tracks));
    }

    public Track find(String id) {
        for (Track t : tracks) if (t.id.equals(id)) return t;
        return null;
    }

    public int count() {
        return tracks.size();
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** Runs {@code r} on the UI thread once saved tracks have been read. */
    public void whenLoaded(Runnable r) {
        if (loaded) {
            r.run();
            return;
        }
        addListener(new Listener() {
            @Override
            public void onTracksChanged(List<Track> t) {
                removeListener(this);
                Io.main(r);
            }
        });
        load();
    }

    public void load() {
        if (loaded) return;
        Io.DISK.execute(() -> {
            List<Track> list = new ArrayList<>();
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    try {
                        list.add(Track.fromJson(new JSONObject(Io.readText(f))));
                    } catch (Exception ignored) {
                        // Skip unreadable files rather than lose the rest.
                    }
                }
            }
            Collections.sort(list, (a, b) -> Long.compare(b.createdAt, a.createdAt));
            Io.main(() -> {
                tracks.clear();
                tracks.addAll(list);
                loaded = true;
                notifyChanged();
            });
        });
    }

    /** Adds or updates a track and writes it to disk. */
    public void save(Track t) {
        if (find(t.id) == null) tracks.add(0, t);
        notifyChanged();
        persist(t);
    }

    /** Writes a track without notifying (used for periodic saves while recording). */
    public void persist(Track t) {
        Io.DISK.execute(() -> {
            try {
                Io.writeTextAtomic(new File(dir, t.id + ".json"), t.toJson().toString());
            } catch (Exception ignored) {
            }
        });
    }

    public void delete(Track t) {
        tracks.remove(t);
        notifyChanged();
        Io.DISK.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, t.id + ".json").delete();
        });
    }

    public void notifyChanged() {
        List<Track> snap = snapshot();
        for (Listener l : new ArrayList<>(listeners)) l.onTracksChanged(snap);
    }
}
