package com.joshuamorley.nzlinz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.joshuamorley.nzlinz.pmtiles.MvtPoints;
import com.joshuamorley.nzlinz.pmtiles.PmTilesReader;
import com.joshuamorley.nzlinz.util.Geo;

import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * Checks the PMTiles tile lookup and MVT point decoding against a real
 * soundings archive. Set NZ_SOUNDINGS to its path; skipped otherwise.
 */
public class NearestSoundingTest {

    @Test
    public void tileIdsMatchSpec() {
        // Values from the PMTiles v3 spec / reference implementation.
        assertEquals(0, PmTilesReader.zxyToTileId(0, 0, 0));
        assertEquals(1, PmTilesReader.zxyToTileId(1, 0, 0));
        assertEquals(2, PmTilesReader.zxyToTileId(1, 0, 1));
        assertEquals(3, PmTilesReader.zxyToTileId(1, 1, 1));
        assertEquals(4, PmTilesReader.zxyToTileId(1, 1, 0));
        assertEquals(5, PmTilesReader.zxyToTileId(2, 0, 0));
    }

    @Test
    public void findsNearestSoundingAtMaxZoom() throws Exception {
        String path = System.getenv("NZ_SOUNDINGS");
        assumeTrue("NZ_SOUNDINGS not set", path != null && new File(path).isFile());

        double lat = -(36 + 49.864 / 60), lon = 174 + 45.551 / 60; // Waitemata, off Westhaven
        try (PmTilesReader r = PmTilesReader.open(new File(path))) {
            int z = r.maxZoom;
            int n = 1 << z;
            int tx = (int) Math.floor((lon + 180) / 360 * n);
            double latR = Math.toRadians(lat);
            int ty = (int) Math.floor((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n);

            // Brute force over a 9x9 block of max-zoom tiles.
            MvtPoints.PointFeature best = null;
            double bestD = Double.MAX_VALUE;
            int tiles = 0, points = 0;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    byte[] tile = r.getTile(z, tx + dx, ty + dy);
                    if (tile == null) continue;
                    tiles++;
                    List<MvtPoints.PointFeature> pts = MvtPoints.decode(tile, "soundings", z, tx + dx, ty + dy);
                    points += pts.size();
                    for (MvtPoints.PointFeature f : pts) {
                        double d = Geo.distanceM(lat, lon, f.lat, f.lon);
                        if (d < bestD) {
                            bestD = d;
                            best = f;
                        }
                    }
                }
            }
            System.out.printf("maxzoom %d, %d tiles, %d soundings; nearest %.0f m at %.5f,%.5f props %s%n",
                    z, tiles, points, bestD, best == null ? 0 : best.lat, best == null ? 0 : best.lon,
                    best == null ? null : best.props);
            assertTrue("no tiles found around the test point", tiles > 0);
            assertNotNull(best);
            assertTrue(best.props.containsKey("depth"));
            // Decoded points must land near the tiles we asked for.
            assertTrue(bestD < 4 * 40075016.686 * Math.cos(latR) / n);
        }
    }
}
