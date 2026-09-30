package com.joshuamorley.nzlinz.pmtiles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Mapbox Vector Tile decoder that extracts point features (with
 * properties) from one layer. Enough for looking up soundings and marks
 * without going through the renderer.
 */
public final class MvtPoints {

    public static final class PointFeature {
        public final double lat, lon;
        public final Map<String, Object> props;

        PointFeature(double lat, double lon, Map<String, Object> props) {
            this.lat = lat;
            this.lon = lon;
            this.props = props;
        }
    }

    private MvtPoints() {}

    /** Point features of {@code layerName} in tile z/x/y. */
    public static List<PointFeature> decode(byte[] tile, String layerName, int z, int x, int y) {
        List<PointFeature> out = new ArrayList<>();
        Pbf p = new Pbf(tile, 0, tile.length);
        while (p.hasMore()) {
            int tag = p.tag();
            if (tag >> 3 == 3 && (tag & 7) == 2) {
                Pbf layer = p.sub();
                decodeLayer(layer, layerName, z, x, y, out);
            } else {
                p.skip(tag);
            }
        }
        return out;
    }

    private static void decodeLayer(Pbf l, String layerName, int z, int x, int y, List<PointFeature> out) {
        String name = null;
        int extent = 4096;
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<Pbf> features = new ArrayList<>();
        while (l.hasMore()) {
            int tag = l.tag();
            switch (tag >> 3) {
                case 1: name = l.string(); break;
                case 2: features.add(l.sub()); break;
                case 3: keys.add(l.string()); break;
                case 4: values.add(value(l.sub())); break;
                case 5: extent = (int) l.varint(); break;
                default: l.skip(tag);
            }
        }
        if (layerName != null && !layerName.equals(name)) return;

        double n = 1L << z;
        for (Pbf f : features) {
            int[] tags = null;
            int[] geom = null;
            int type = 0;
            while (f.hasMore()) {
                int tag = f.tag();
                switch (tag >> 3) {
                    case 2: tags = f.packed(); break;
                    case 3: type = (int) f.varint(); break;
                    case 4: geom = f.packed(); break;
                    default: f.skip(tag);
                }
            }
            if (type != 1 || geom == null) continue; // points only
            Map<String, Object> props = new HashMap<>();
            if (tags != null) {
                for (int i = 0; i + 1 < tags.length; i += 2) {
                    if (tags[i] < keys.size() && tags[i + 1] < values.size()) {
                        props.put(keys.get(tags[i]), values.get(tags[i + 1]));
                    }
                }
            }
            int cx = 0, cy = 0, i = 0;
            while (i < geom.length) {
                int cmd = geom[i] & 7, count = geom[i] >>> 3;
                i++;
                if (cmd != 1) break;
                for (int c = 0; c < count && i + 1 < geom.length; c++) {
                    cx += zigzag(geom[i++]);
                    cy += zigzag(geom[i++]);
                    double wx = (x + cx / (double) extent) / n;
                    double wy = (y + cy / (double) extent) / n;
                    double lon = wx * 360 - 180;
                    double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * wy))));
                    out.add(new PointFeature(lat, lon, props));
                }
            }
        }
    }

    private static Object value(Pbf v) {
        Object result = null;
        while (v.hasMore()) {
            int tag = v.tag();
            switch (tag >> 3) {
                case 1: result = v.string(); break;
                case 2: result = (double) Float.intBitsToFloat(v.fixed32()); break;
                case 3: result = Double.longBitsToDouble(v.fixed64()); break;
                case 4: result = v.varint(); break;
                case 5: result = v.varint(); break;
                case 6: { long u = v.varint(); result = (u >>> 1) ^ -(u & 1); break; }
                case 7: result = v.varint() != 0; break;
                default: v.skip(tag);
            }
        }
        return result;
    }

    private static int zigzag(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    /** Tiny protobuf reader over a byte range. */
    private static final class Pbf {
        final byte[] b;
        int pos;
        final int end;

        Pbf(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        boolean hasMore() { return pos < end; }

        int tag() { return (int) varint(); }

        long varint() {
            long r = 0;
            int shift = 0;
            while (true) {
                int v = b[pos++] & 0xFF;
                r |= (long) (v & 0x7F) << shift;
                if ((v & 0x80) == 0) return r;
                shift += 7;
            }
        }

        int fixed32() {
            int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
            pos += 4;
            return v;
        }

        long fixed64() {
            long lo = fixed32() & 0xFFFFFFFFL, hi = fixed32() & 0xFFFFFFFFL;
            return lo | hi << 32;
        }

        Pbf sub() {
            int len = (int) varint();
            Pbf s = new Pbf(b, pos, pos + len);
            pos += len;
            return s;
        }

        String string() {
            int len = (int) varint();
            String s = new String(b, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        int[] packed() {
            Pbf s = sub();
            List<Integer> vals = new ArrayList<>();
            while (s.hasMore()) vals.add((int) s.varint());
            int[] out = new int[vals.size()];
            for (int i = 0; i < out.length; i++) out[i] = vals.get(i);
            return out;
        }

        void skip(int tag) {
            switch (tag & 7) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: pos += (int) varint(); break;
                case 5: pos += 4; break;
                default: throw new IllegalStateException("Bad wire type " + (tag & 7));
            }
        }
    }
}
