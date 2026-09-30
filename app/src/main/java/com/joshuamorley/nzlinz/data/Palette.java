package com.joshuamorley.nzlinz.data;

/** Colours offered for tracks and marks; chosen to stand out on a light chart. */
public final class Palette {

    public static final int[] COLORS = {
            0xFFE53935, // red
            0xFFFB8C00, // orange
            0xFFFDD835, // yellow
            0xFF43A047, // green
            0xFF00897B, // teal
            0xFF1E88E5, // blue
            0xFF3949AB, // indigo
            0xFF8E24AA, // purple
            0xFFD81B60, // pink
            0xFF6D4C41, // brown
            0xFF546E7A, // slate
            0xFF212121, // black
    };

    private Palette() {}

    public static int pick(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    public static String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }
}
