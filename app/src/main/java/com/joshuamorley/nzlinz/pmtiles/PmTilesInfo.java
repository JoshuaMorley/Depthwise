package com.joshuamorley.nzlinz.pmtiles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads the header and JSON metadata of a PMTiles v3 archive (local file or
 * HTTP URL) so the app can build a style without knowing layer names up front.
 */
public final class PmTilesInfo {

    public static final int TILE_TYPE_MVT = 1;
    public static final int TILE_TYPE_PNG = 2;
    public static final int TILE_TYPE_JPEG = 3;
    public static final int TILE_TYPE_WEBP = 4;
    public static final int TILE_TYPE_AVIF = 5;

    private static final int HEADER_SIZE = 127;
    private static final int COMPRESSION_NONE = 1;
    private static final int COMPRESSION_GZIP = 2;

    public final int tileType;
    public final int minZoom;
    public final int maxZoom;
    public final double minLon, minLat, maxLon, maxLat;
    public final double centerLon, centerLat;
    public final int centerZoom;
    public final String name;
    public final List<VectorLayer> vectorLayers;

    public static final class VectorLayer {
        public final String id;
        /** Field name to declared type ("Number", "String", "Boolean"). */
        public final Map<String, String> fields;

        public VectorLayer(String id, Map<String, String> fields) {
            this.id = id;
            this.fields = fields;
        }
    }

    /** Random access to the archive bytes. */
    interface RangeReader {
        byte[] read(long offset, int length) throws IOException;
    }

    public PmTilesInfo(int tileType, int minZoom, int maxZoom,
                       double minLon, double minLat, double maxLon, double maxLat,
                       double centerLon, double centerLat, int centerZoom,
                       String name, List<VectorLayer> vectorLayers) {
        this.tileType = tileType;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.minLon = minLon;
        this.minLat = minLat;
        this.maxLon = maxLon;
        this.maxLat = maxLat;
        this.centerLon = centerLon;
        this.centerLat = centerLat;
        this.centerZoom = centerZoom;
        this.name = name;
        this.vectorLayers = vectorLayers;
    }

    public boolean isVector() {
        return tileType == TILE_TYPE_MVT;
    }

    public boolean hasBounds() {
        return maxLon > minLon && maxLat > minLat;
    }

    /** Quick check used before copying a picked file. */
    public static boolean hasMagic(byte[] firstBytes) {
        if (firstBytes == null || firstBytes.length < 8) return false;
        String magic = new String(firstBytes, 0, 7, StandardCharsets.US_ASCII);
        return "PMTiles".equals(magic) && firstBytes[7] == 3;
    }

    public static PmTilesInfo read(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return read((offset, length) -> {
                byte[] buf = new byte[length];
                raf.seek(offset);
                raf.readFully(buf);
                return buf;
            }, file.getName());
        }
    }

    /** Reads a remote archive using HTTP range requests. Blocking; call off the UI thread. */
    public static PmTilesInfo readUrl(String url) throws IOException {
        return read((offset, length) -> httpRange(url, offset, length), lastPathSegment(url));
    }

    static PmTilesInfo read(RangeReader reader, String fallbackName) throws IOException {
        byte[] header = reader.read(0, HEADER_SIZE);
        if (!hasMagic(header)) throw new IOException("Not a PMTiles v3 archive");

        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long metadataOffset = b.getLong(24);
        long metadataLength = b.getLong(32);
        int internalCompression = header[97] & 0xFF;
        int tileType = header[99] & 0xFF;
        int minZoom = header[100] & 0xFF;
        int maxZoom = header[101] & 0xFF;
        double minLon = b.getInt(102) / 1e7;
        double minLat = b.getInt(106) / 1e7;
        double maxLon = b.getInt(110) / 1e7;
        double maxLat = b.getInt(114) / 1e7;
        int centerZoom = header[118] & 0xFF;
        double centerLon = b.getInt(119) / 1e7;
        double centerLat = b.getInt(123) / 1e7;

        String name = fallbackName;
        List<VectorLayer> layers = Collections.emptyList();
        if (metadataLength > 0 && metadataLength < 64L * 1024 * 1024) {
            byte[] json = decompress(reader.read(metadataOffset, (int) metadataLength), internalCompression);
            try {
                JSONObject meta = new JSONObject(new String(json, StandardCharsets.UTF_8));
                name = meta.optString("name", name);
                layers = parseVectorLayers(meta.optJSONArray("vector_layers"));
            } catch (JSONException e) {
                throw new IOException("Bad PMTiles metadata: " + e.getMessage(), e);
            }
        }
        return new PmTilesInfo(tileType, minZoom, maxZoom, minLon, minLat, maxLon, maxLat,
                centerLon, centerLat, centerZoom, name, layers);
    }

    /** Parses a TileJSON / PMTiles "vector_layers" array. */
    public static List<VectorLayer> parseVectorLayers(JSONArray vl) throws JSONException {
        if (vl == null) return Collections.emptyList();
        List<VectorLayer> layers = new ArrayList<>();
        for (int i = 0; i < vl.length(); i++) {
            JSONObject l = vl.getJSONObject(i);
            Map<String, String> fields = new LinkedHashMap<>();
            JSONObject f = l.optJSONObject("fields");
            if (f != null) {
                for (Iterator<String> it = f.keys(); it.hasNext(); ) {
                    String k = it.next();
                    fields.put(k, f.optString(k));
                }
            }
            layers.add(new VectorLayer(l.getString("id"), fields));
        }
        return Collections.unmodifiableList(layers);
    }

    static byte[] httpRange(String url, long offset, int length) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
            int code = c.getResponseCode();
            if (code != 206 && code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                if (code == 200 && offset > 0) {
                    // Server ignored the range; skip forward (only practical for small offsets).
                    long skipped = 0;
                    while (skipped < offset) {
                        long s = in.skip(offset - skipped);
                        if (s <= 0) throw new IOException("Server doesn't support range requests");
                        skipped += s;
                    }
                }
                byte[] buf = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(buf, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read < length) throw new IOException("Short read from server");
                return buf;
            }
        } finally {
            c.disconnect();
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] data, int compression) throws IOException {
        if (compression == COMPRESSION_NONE) return data;
        if (compression != COMPRESSION_GZIP) {
            throw new IOException("Unsupported metadata compression " + compression);
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAll(in);
        }
    }

    private static String lastPathSegment(String url) {
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        int slash = u.lastIndexOf('/');
        return slash >= 0 ? u.substring(slash + 1) : u;
    }
}
