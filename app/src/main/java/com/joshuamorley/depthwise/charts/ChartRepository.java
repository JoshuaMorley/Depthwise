package com.joshuamorley.depthwise.charts;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.joshuamorley.depthwise.data.Io;
import com.joshuamorley.depthwise.pmtiles.PmTilesInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the list of chart sources: chart packs (folders with chartpack.json),
 * loose .pmtiles files, and remote sources the user added. All disk and
 * network work runs on {@link Io#DISK}; listeners are called on the UI thread.
 */
public final class ChartRepository {

    public interface Listener {
        void onChartsChanged(List<ChartSource> sources);

        /** Scanning started or finished. */
        default void onChartsBusy(boolean busy) {}
    }

    public interface ImportCallback {
        void onProgress(long copied, long total);
        void onDone(@Nullable String title, @Nullable String error);
    }

    public static final String NOT_A_CHART = "NOT_A_CHART";

    private static final String PREF_DISABLED = "disabled_sources";
    private static final String PREF_HIDDEN_GROUPS = "hidden_groups";
    private static final String PREF_SHOWN_GROUPS = "shown_groups";
    private static ChartRepository instance;

    private final Context app;
    private final File chartsDir;
    private final File remoteFile;
    private final SharedPreferences prefs;
    private final List<Listener> listeners = new ArrayList<>();
    /** Parsed PMTiles headers keyed by path|size|mtime, so rescans are cheap. */
    private final Map<String, ChartSource.Info> fileInfoCache = new HashMap<>();
    private List<ChartSource> sources = Collections.emptyList();
    /** Refreshes queued or running; touched on the UI thread only. */
    private int pendingRefreshes;

    public static synchronized ChartRepository get(Context context) {
        if (instance == null) instance = new ChartRepository(context.getApplicationContext());
        return instance;
    }

    private ChartRepository(Context app) {
        this.app = app;
        File ext = app.getExternalFilesDir("charts");
        this.chartsDir = ext != null ? ext : new File(app.getFilesDir(), "charts");
        //noinspection ResultOfMethodCallIgnored
        chartsDir.mkdirs();
        this.remoteFile = new File(app.getFilesDir(), "remote_sources.json");
        this.prefs = PreferenceManager.getDefaultSharedPreferences(app);
    }

    public File chartsDir() {
        return chartsDir;
    }

    public List<ChartSource> sources() {
        return sources;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------ scanning

    public boolean isBusy() {
        return pendingRefreshes > 0;
    }

    /** Rescans packs, files and remote sources in the background, then notifies listeners. */
    public void refresh() {
        Io.main(() -> {
            if (pendingRefreshes++ == 0) {
                for (Listener l : new ArrayList<>(listeners)) l.onChartsBusy(true);
            }
        });
        Io.DISK.execute(() -> {
            List<ChartSource> list = new ArrayList<>();
            Set<String> disabled = prefs.getStringSet(PREF_DISABLED, Collections.emptySet());
            Set<String> referenced = new HashSet<>();

            // Packs: the charts folder itself and each direct subfolder.
            List<File> packDirs = new ArrayList<>();
            packDirs.add(chartsDir);
            File[] subdirs = chartsDir.listFiles(File::isDirectory);
            if (subdirs != null) Collections.addAll(packDirs, subdirs);
            for (File dir : packDirs) {
                File cfg = new File(dir, ChartPack.CONFIG_NAME);
                if (!cfg.isFile()) continue;
                String id = "pack:" + (dir.equals(chartsDir) ? "." : dir.getName());
                ChartPack pack = null;
                String error = null;
                try {
                    pack = ChartPack.parse(Io.readText(cfg), dir, null, dir.getName());
                    collectReferencedFiles(pack, referenced);
                } catch (Exception e) {
                    error = "chartpack.json: " + e.getMessage();
                }
                ChartSource s = new ChartSource(id, ChartSource.Kind.PACK,
                        pack != null ? pack.title : dir.getName(), dir, null);
                s.pack = pack;
                s.error = error;
                s.enabled = !disabled.contains(s.id);
                list.add(s);
            }

            // Loose .pmtiles with no config (skipping files a pack in the root uses).
            File[] files = chartsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".pmtiles"));
            if (files != null) {
                for (File f : files) {
                    if (referenced.contains(f.getAbsolutePath())) continue;
                    Region region = Region.forFileName(app, f.getName());
                    String title = region != null ? region.title : f.getName().replaceAll("(?i)\\.pmtiles$", "");
                    ChartSource s = new ChartSource("file:" + f.getName(), ChartSource.Kind.FILE_PMTILES,
                            title, f, null);
                    String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
                    ChartSource.Info info = fileInfoCache.get(key);
                    if (info == null) {
                        try {
                            info = ChartSource.Info.from(PmTilesInfo.read(f));
                            fileInfoCache.put(key, info);
                        } catch (IOException e) {
                            s.error = e.getMessage();
                        }
                    }
                    s.info = info;
                    s.enabled = !disabled.contains(s.id);
                    list.add(s);
                }
            }

            for (ChartSource s : readRemote()) {
                s.enabled = !disabled.contains(s.id);
                list.add(s);
            }

            Collections.sort(list, (a, b) -> a.title.compareToIgnoreCase(b.title));
            List<ChartSource> result = Collections.unmodifiableList(list);
            Io.main(() -> {
                sources = result;
                boolean done = --pendingRefreshes == 0;
                if (done) {
                    for (Listener l : new ArrayList<>(listeners)) l.onChartsBusy(false);
                }
                notifyChanged();
            });
        });
    }

    private static void collectReferencedFiles(ChartPack pack, Set<String> out) {
        if (pack.dir == null) return;
        for (Iterator<String> it = pack.sources.keys(); it.hasNext(); ) {
            JSONObject src = pack.sources.optJSONObject(it.next());
            if (src == null) continue;
            String url = src.optString("url", "");
            if (!url.isEmpty() && !url.contains("://")) out.add(new File(pack.dir, url).getAbsolutePath());
        }
    }

    private void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onChartsChanged(sources);
    }

    // ------------------------------------------------------------------ toggles

    public void setEnabled(ChartSource s, boolean enabled) {
        s.enabled = enabled;
        Set<String> disabled = new HashSet<>(prefs.getStringSet(PREF_DISABLED, Collections.emptySet()));
        if (enabled) disabled.remove(s.id);
        else disabled.add(s.id);
        prefs.edit().putStringSet(PREF_DISABLED, disabled).apply();
        notifyChanged();
    }

    /** Whether a layer group is shown, falling back to the pack's default. */
    public boolean isGroupVisible(String groupId, boolean defaultVisible) {
        if (prefs.getStringSet(PREF_HIDDEN_GROUPS, Collections.emptySet()).contains(groupId)) return false;
        if (prefs.getStringSet(PREF_SHOWN_GROUPS, Collections.emptySet()).contains(groupId)) return true;
        return defaultVisible;
    }

    public void setGroupVisible(String groupId, boolean visible) {
        Set<String> hidden = new HashSet<>(prefs.getStringSet(PREF_HIDDEN_GROUPS, Collections.emptySet()));
        Set<String> shown = new HashSet<>(prefs.getStringSet(PREF_SHOWN_GROUPS, Collections.emptySet()));
        if (visible) {
            hidden.remove(groupId);
            shown.add(groupId);
        } else {
            shown.remove(groupId);
            hidden.add(groupId);
        }
        prefs.edit().putStringSet(PREF_HIDDEN_GROUPS, hidden).putStringSet(PREF_SHOWN_GROUPS, shown).apply();
    }

    // ------------------------------------------------------------------ importing

    /** Display name of a picked document, or null. */
    @Nullable
    public String displayName(Uri uri) {
        try (Cursor c = app.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    /** Where an import of this file name would land, so the UI can confirm replacing it. */
    public File importTarget(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return new File(chartsDir, ChartPack.safeId(fileName.substring(0, fileName.length() - 4)));
        String name = lower.endsWith(".pmtiles") ? fileName : fileName + ".pmtiles";
        return new File(chartsDir, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
    }

    /**
     * Imports a picked file: a .zip chart pack is extracted into its own folder,
     * a .pmtiles file is copied into the charts folder.
     */
    public void importFile(Uri uri, String fileName, ImportCallback cb) {
        Io.DISK.execute(() -> {
            File target = importTarget(fileName);
            long total = querySize(uri);
            try {
                if (fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    importZip(uri, target, total, cb);
                } else {
                    importPmtiles(uri, target, total, cb);
                }
                Io.main(() -> cb.onDone(target.getName(), null));
                refresh();
            } catch (IOException e) {
                String msg = e.getMessage();
                Io.main(() -> cb.onDone(null, msg));
            }
        });
    }

    private void importPmtiles(Uri uri, File target, long total, ImportCallback cb) throws IOException {
        File part = new File(chartsDir, target.getName() + ".part");
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Couldn't open file");
            byte[] head = new byte[8];
            int n = readFully(in, head);
            if (n < 8 || !PmTilesInfo.hasMagic(head)) throw new IOException(NOT_A_CHART);
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(head, 0, n);
                copy(in, out, n, total, cb);
            }
            replace(part, target);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
    }

    private void importZip(Uri uri, File targetDir, long total, ImportCallback cb) throws IOException {
        File staging = new File(chartsDir, "." + targetDir.getName() + ".importing");
        deleteRecursive(staging);
        try (InputStream raw = app.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Couldn't open file");
            CountingInputStream counting = new CountingInputStream(raw);
            try (ZipInputStream zin = new ZipInputStream(counting)) {
                ZipEntry e;
                String stagingPath = staging.getCanonicalPath() + File.separator;
                while ((e = zin.getNextEntry()) != null) {
                    File out = new File(staging, e.getName());
                    // Refuse entries that would escape the target folder.
                    if (!out.getCanonicalPath().startsWith(stagingPath)) throw new IOException("Bad zip entry " + e.getName());
                    if (e.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.mkdirs();
                        continue;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        long last = 0;
                        while ((n = zin.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            if (counting.count - last > 8L * 1024 * 1024) {
                                last = counting.count;
                                long c = counting.count;
                                Io.main(() -> cb.onProgress(c, total));
                            }
                        }
                    }
                }
            }
            File root = findPackRoot(staging);
            if (root == null) throw new IOException(NOT_A_CHART);
            deleteRecursive(targetDir);
            if (!root.renameTo(targetDir)) throw new IOException("Couldn't finish import");
        } finally {
            deleteRecursive(staging);
        }
    }

    /**
     * Copies every file under a picked folder (e.g. a region's output folder
     * with chartpack.json) into its own pack folder.
     */
    public void importFolder(Uri treeUri, ImportCallback cb) {
        Io.DISK.execute(() -> {
            ContentResolver cr = app.getContentResolver();
            String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
            String name = ChartPack.safeId(nameOf(cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)));
            File targetDir = new File(chartsDir, name);
            File staging = new File(chartsDir, "." + name + ".importing");
            try {
                deleteRecursive(staging);
                long[] copied = {0};
                copyTree(cr, treeUri, rootDocId, staging, copied, cb, 0);
                File root = findPackRoot(staging);
                if (root == null) throw new IOException(NOT_A_CHART);
                deleteRecursive(targetDir);
                if (!root.renameTo(targetDir)) throw new IOException("Couldn't finish import");
                Io.main(() -> cb.onDone(name, null));
                refresh();
            } catch (IOException e) {
                String msg = e.getMessage();
                Io.main(() -> cb.onDone(null, msg));
            } finally {
                deleteRecursive(staging);
            }
        });
    }

    private void copyTree(ContentResolver cr, Uri treeUri, String docId, File dest, long[] copied,
                          ImportCallback cb, int depth) throws IOException {
        if (depth > 4) return;
        //noinspection ResultOfMethodCallIgnored
        dest.mkdirs();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    copyTree(cr, treeUri, id, new File(dest, name), copied, cb, depth + 1);
                } else {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    try (InputStream in = cr.openInputStream(doc);
                         OutputStream out = new FileOutputStream(new File(dest, name))) {
                        if (in == null) continue;
                        copied[0] = copy(in, out, copied[0], -1, cb);
                    }
                }
            }
        }
    }

    /** The folder holding chartpack.json: the root, or its single top-level folder. */
    @Nullable
    private static File findPackRoot(File dir) {
        if (new File(dir, ChartPack.CONFIG_NAME).isFile()) return dir;
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs != null) {
            for (File s : subs) if (new File(s, ChartPack.CONFIG_NAME).isFile()) return s;
        }
        return null;
    }

    /** The pack folder a picked tree would import into, so the UI can confirm replacing it. */
    public File folderImportTarget(Uri treeUri) {
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        return new File(chartsDir, ChartPack.safeId(nameOf(app.getContentResolver(),
                DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId))));
    }

    private static String nameOf(ContentResolver cr, Uri doc) {
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return "pack";
    }

    private long querySize(Uri uri) {
        try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static long copy(InputStream in, OutputStream out, long copied, long total, ImportCallback cb)
            throws IOException {
        byte[] buf = new byte[1024 * 1024];
        int n;
        long last = copied;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            copied += n;
            if (copied - last > 8L * 1024 * 1024) {
                last = copied;
                long c = copied;
                Io.main(() -> cb.onProgress(c, total));
            }
        }
        return copied;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) break;
            read += n;
        }
        return read;
    }

    private static void replace(File part, File target) throws IOException {
        if (target.exists() && !target.delete()) throw new IOException("Couldn't replace existing file");
        if (!part.renameTo(target)) throw new IOException("Couldn't finish copy");
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        volatile long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    // ------------------------------------------------------------------ removing

    /** Removes a chart: deletes local files/folders, or forgets a remote URL. */
    public void remove(ChartSource s, Runnable done) {
        Io.DISK.execute(() -> {
            if (s.kind == ChartSource.Kind.FILE_PMTILES) {
                //noinspection ResultOfMethodCallIgnored
                s.file.delete();
            } else if (s.kind == ChartSource.Kind.PACK) {
                if (s.file.equals(chartsDir)) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(chartsDir, ChartPack.CONFIG_NAME).delete();
                } else {
                    deleteRecursive(s.file);
                }
            } else {
                List<ChartSource> keep = new ArrayList<>();
                for (ChartSource r : readRemote()) if (!r.id.equals(s.id)) keep.add(r);
                writeRemote(keep);
            }
            Io.main(done);
            refresh();
        });
    }

    // ------------------------------------------------------------------ remote

    /** Guesses the kind of a remote URL. */
    public static ChartSource.Kind detectKind(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("pmtiles://") || u.contains(".pmtiles")) return ChartSource.Kind.REMOTE_PMTILES;
        if (u.contains("{z}") || u.contains("{x}")) return ChartSource.Kind.XYZ_RASTER;
        return ChartSource.Kind.TILEJSON; // or a chart pack; decided after fetching
    }

    /** Validates and saves a remote source. Network runs in the background. */
    public void addRemote(String title, String url, ImportCallback cb) {
        Io.DISK.execute(() -> {
            try {
                String u = url.trim();
                ChartSource.Kind kind = detectKind(u);
                ChartSource s;
                String packJson = null;
                if (kind == ChartSource.Kind.TILEJSON) {
                    String body = httpGet(u);
                    JSONObject o = new JSONObject(body);
                    if (o.has("layers") && o.has("sources")) {
                        packJson = body;
                        ChartPack pack = ChartPack.parse(body, null, baseOf(u), "remote");
                        String t = title.isEmpty() ? pack.title : title;
                        s = new ChartSource("remote:" + UUID.randomUUID(), ChartSource.Kind.REMOTE_PACK, t, null, u);
                        s.pack = pack;
                    } else {
                        s = new ChartSource("remote:" + UUID.randomUUID(), kind, title.isEmpty() ? u : title, null, u);
                        s.info = tileJsonInfo(o);
                    }
                } else {
                    s = new ChartSource("remote:" + UUID.randomUUID(), kind, title.isEmpty() ? u : title, null, u);
                    s.info = resolveRemote(s);
                }
                List<ChartSource> remote = readRemote();
                remote.add(s);
                writeRemote(remote, s.id, packJson);
                String t = s.title;
                Io.main(() -> cb.onDone(t, null));
                refresh();
            } catch (Exception e) {
                String msg = e.getMessage();
                Io.main(() -> cb.onDone(null, msg));
            }
        });
    }

    private static String baseOf(String url) {
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        return u.substring(0, u.lastIndexOf('/') + 1);
    }

    private ChartSource.Info resolveRemote(ChartSource s) throws Exception {
        if (s.kind == ChartSource.Kind.REMOTE_PMTILES) {
            String http = s.url.startsWith("pmtiles://") ? s.url.substring("pmtiles://".length()) : s.url;
            return ChartSource.Info.from(PmTilesInfo.readUrl(http));
        }
        // XYZ template
        if (s.url.contains(".pbf") || s.url.contains(".mvt")) {
            throw new IOException("Vector XYZ needs a TileJSON URL or a chart pack so layers are known");
        }
        return new ChartSource.Info(false, null, null, 0, 22,
                s.url.contains("@2x") ? 512 : 256, Collections.singletonList(s.url));
    }

    private static ChartSource.Info tileJsonInfo(JSONObject tj) throws Exception {
        if (!tj.has("tiles")) throw new IOException("Not a TileJSON document or chart pack");
        List<PmTilesInfo.VectorLayer> layers = PmTilesInfo.parseVectorLayers(tj.optJSONArray("vector_layers"));
        JSONArray t = tj.getJSONArray("tiles");
        List<String> tiles = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) tiles.add(t.getString(i));
        boolean vector = !layers.isEmpty() || "pbf".equals(tj.optString("format"));
        double[] bounds = null;
        JSONArray b = tj.optJSONArray("bounds");
        if (b != null && b.length() == 4) {
            bounds = new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
        }
        return new ChartSource.Info(vector, layers, bounds, tj.optInt("minzoom", 0),
                tj.optInt("maxzoom", 22), tj.optInt("tileSize", vector ? 512 : 256), tiles);
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return new String(PmTilesInfo.readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Remote list, with remote pack configs cached so they work offline. */
    private List<ChartSource> readRemote() {
        List<ChartSource> out = new ArrayList<>();
        if (!remoteFile.exists()) return out;
        try {
            JSONArray arr = new JSONArray(Io.readText(remoteFile));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ChartSource s = new ChartSource(o.getString("id"), ChartSource.Kind.valueOf(o.getString("kind")),
                        o.getString("title"), null, o.getString("url"));
                JSONObject info = o.optJSONObject("info");
                if (info != null) s.info = ChartSource.Info.fromJson(info);
                String pack = o.optString("pack", null);
                if (pack != null) {
                    try {
                        s.pack = ChartPack.parse(pack, null, baseOf(s.url), "remote");
                    } catch (Exception e) {
                        s.error = e.getMessage();
                    }
                }
                out.add(s);
            }
        } catch (Exception ignored) {
            // Corrupt list: behave as empty rather than crash.
        }
        return out;
    }

    private void writeRemote(List<ChartSource> list) {
        writeRemote(list, null, null);
    }

    private void writeRemote(List<ChartSource> list, @Nullable String newId, @Nullable String newPackJson) {
        try {
            // Keep previously cached pack JSON for existing entries.
            Map<String, String> cachedPacks = new HashMap<>();
            if (remoteFile.exists()) {
                JSONArray old = new JSONArray(Io.readText(remoteFile));
                for (int i = 0; i < old.length(); i++) {
                    JSONObject o = old.getJSONObject(i);
                    if (o.has("pack")) cachedPacks.put(o.getString("id"), o.getString("pack"));
                }
            }
            if (newId != null && newPackJson != null) cachedPacks.put(newId, newPackJson);
            JSONArray arr = new JSONArray();
            for (ChartSource s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("kind", s.kind.name());
                o.put("title", s.title);
                o.put("url", s.url);
                if (s.info != null) o.put("info", s.info.toJson());
                String pack = cachedPacks.get(s.id);
                if (pack != null) o.put("pack", pack);
                arr.put(o);
            }
            Io.writeTextAtomic(remoteFile, arr.toString());
        } catch (Exception ignored) {
        }
    }
}
