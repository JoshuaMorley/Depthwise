package com.joshuamorley.depthwise.pmtiles;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads individual tiles from a PMTiles v3 archive (local file or HTTP URL),
 * following the spec's Hilbert tile ids and (leaf) directories.
 * Thread-safe; directories and recent tiles are cached.
 */
public final class PmTilesReader implements Closeable {

    private static final int COMPRESSION_NONE = 1;
    private static final int COMPRESSION_GZIP = 2;

    private final PmTilesInfo.RangeReader reader;
    private final RandomAccessFile raf;
    private final long rootOffset, rootLength, leafOffset, tileDataOffset;
    private final int internalCompression, tileCompression;
    public final int minZoom, maxZoom;

    private final Map<Long, List<Entry>> dirCache = lru(64);
    private final Map<Long, byte[]> tileCache = lru(48);

    private static final class Entry {
        long tileId, offset;
        int length, runLength;
    }

    private PmTilesReader(PmTilesInfo.RangeReader reader, RandomAccessFile raf) throws IOException {
        this.reader = reader;
        this.raf = raf;
        byte[] h = reader.read(0, 127);
        if (!PmTilesInfo.hasMagic(h)) throw new IOException("Not a PMTiles v3 archive");
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        rootOffset = b.getLong(8);
        rootLength = b.getLong(16);
        leafOffset = b.getLong(40);
        tileDataOffset = b.getLong(56);
        internalCompression = h[97] & 0xFF;
        tileCompression = h[98] & 0xFF;
        minZoom = h[100] & 0xFF;
        maxZoom = h[101] & 0xFF;
    }

    public static PmTilesReader open(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            return new PmTilesReader((offset, length) -> {
                byte[] buf = new byte[length];
                synchronized (raf) {
                    raf.seek(offset);
                    raf.readFully(buf);
                }
                return buf;
            }, raf);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
    }

    public static PmTilesReader open(String httpUrl) throws IOException {
        return new PmTilesReader((offset, length) -> PmTilesInfo.httpRange(httpUrl, offset, length), null);
    }

    /** Decompressed tile bytes, or null if the tile isn't in the archive. */
    public synchronized byte[] getTile(int z, int x, int y) throws IOException {
        long id = zxyToTileId(z, x, y);
        byte[] cached = tileCache.get(id);
        if (cached != null) return cached;

        long dirOffset = rootOffset, dirLength = rootLength;
        for (int depth = 0; depth < 4; depth++) {
            List<Entry> dir = directory(dirOffset, (int) dirLength);
            Entry e = find(dir, id);
            if (e == null) return null;
            if (e.runLength > 0) {
                byte[] raw = reader.read(tileDataOffset + e.offset, e.length);
                byte[] tile = decompress(raw, tileCompression);
                tileCache.put(id, tile);
                return tile;
            }
            dirOffset = leafOffset + e.offset;
            dirLength = e.length;
        }
        return null;
    }

    private List<Entry> directory(long offset, int length) throws IOException {
        List<Entry> dir = dirCache.get(offset);
        if (dir != null) return dir;
        byte[] data = decompress(reader.read(offset, length), internalCompression);
        dir = parseDirectory(data);
        dirCache.put(offset, dir);
        return dir;
    }

    static List<Entry> parseDirectory(byte[] data) {
        int[] pos = {0};
        int n = (int) varint(data, pos);
        List<Entry> entries = new ArrayList<>(n);
        long last = 0;
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            last += varint(data, pos);
            e.tileId = last;
            entries.add(e);
        }
        for (int i = 0; i < n; i++) entries.get(i).runLength = (int) varint(data, pos);
        for (int i = 0; i < n; i++) entries.get(i).length = (int) varint(data, pos);
        for (int i = 0; i < n; i++) {
            long v = varint(data, pos);
            Entry e = entries.get(i);
            if (v == 0 && i > 0) {
                Entry prev = entries.get(i - 1);
                e.offset = prev.offset + prev.length;
            } else {
                e.offset = v - 1;
            }
        }
        return entries;
    }

    private static Entry find(List<Entry> entries, long tileId) {
        int m = 0, n = entries.size() - 1;
        while (m <= n) {
            int k = (m + n) >>> 1;
            long cmp = tileId - entries.get(k).tileId;
            if (cmp > 0) m = k + 1;
            else if (cmp < 0) n = k - 1;
            else return entries.get(k);
        }
        if (n >= 0) {
            Entry e = entries.get(n);
            if (e.runLength == 0) return e;
            if (tileId - e.tileId < e.runLength) return e;
        }
        return null;
    }

    /** PMTiles v3 tile id: tiles in lower zooms first, then Hilbert order within a zoom. */
    public static long zxyToTileId(int z, int x, int y) {
        long acc = ((1L << (2 * z)) - 1) / 3;
        long n = 1L << z;
        long tx = x, ty = y, d = 0;
        for (long s = n / 2; s > 0; s /= 2) {
            long rx = (tx & s) > 0 ? 1 : 0;
            long ry = (ty & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    tx = s - 1 - tx;
                    ty = s - 1 - ty;
                }
                long t = tx;
                tx = ty;
                ty = t;
            }
        }
        return acc + d;
    }

    private static long varint(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int v = b[pos[0]++] & 0xFF;
            result |= (long) (v & 0x7F) << shift;
            if ((v & 0x80) == 0) return result;
            shift += 7;
        }
    }

    private static byte[] decompress(byte[] data, int compression) throws IOException {
        if (compression == COMPRESSION_GZIP || (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B)) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                return PmTilesInfo.readAll(in);
            }
        }
        if (compression == COMPRESSION_NONE || compression == 0) return data;
        throw new IOException("Unsupported compression " + compression);
    }

    private static <K, V> Map<K, V> lru(int max) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    @Override
    public void close() throws IOException {
        if (raf != null) raf.close();
    }
}
