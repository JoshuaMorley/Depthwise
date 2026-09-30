package com.joshuamorley.depthwise.data;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Saved marks in a single files/marks.json. Callbacks run on the UI thread. */
public final class MarkStore {

    public interface Listener {
        void onMarksChanged(List<Mark> marks);
    }

    private static MarkStore instance;

    private final File file;
    private final List<Mark> marks = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private boolean loaded;

    public static synchronized MarkStore get(Context context) {
        if (instance == null) instance = new MarkStore(context.getApplicationContext());
        return instance;
    }

    private MarkStore(Context app) {
        file = new File(app.getFilesDir(), "marks.json");
    }

    public void addListener(Listener l) {
        listeners.add(l);
        if (loaded) l.onMarksChanged(snapshot());
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Mark> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(marks));
    }

    public Mark find(String id) {
        for (Mark m : marks) if (m.id.equals(id)) return m;
        return null;
    }

    public int count() {
        return marks.size();
    }

    public void load() {
        if (loaded) return;
        Io.DISK.execute(() -> {
            List<Mark> list = new ArrayList<>();
            if (file.exists()) {
                try {
                    JSONArray arr = new JSONArray(Io.readText(file));
                    for (int i = 0; i < arr.length(); i++) list.add(Mark.fromJson(arr.getJSONObject(i)));
                } catch (Exception ignored) {
                }
            }
            Io.main(() -> {
                marks.clear();
                marks.addAll(list);
                loaded = true;
                notifyChanged();
            });
        });
    }

    public void save(Mark m) {
        if (find(m.id) == null) marks.add(m);
        changed();
    }

    public void delete(Mark m) {
        marks.remove(m);
        changed();
    }

    private void changed() {
        notifyChanged();
        List<Mark> snap = snapshot();
        Io.DISK.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Mark m : snap) arr.put(m.toJson());
                Io.writeTextAtomic(file, arr.toString());
            } catch (Exception ignored) {
            }
        });
    }

    private void notifyChanged() {
        List<Mark> snap = snapshot();
        for (Listener l : new ArrayList<>(listeners)) l.onMarksChanged(snap);
    }
}
