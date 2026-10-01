package dev.jjstools.util;

/**
 * Standard "ignore this area around 0, 0" sizes, shared by the modules that need one so they all
 * read the same way in the GUI.
 */
public enum SpawnArea {
    Off("Off", 0),
    Size1000("1000 x 1000", 500),
    Size4000("4000 x 4000", 2000),
    Size10000("10k x 10k", 5000),
    Size100000("100k x 100k", 50000),
    Custom("Custom", -1);

    /** 2b2t's ring roads, in blocks from 0, 0. */
    public static final int[] RING_ROADS = {1000, 5000, 10000, 15000, 25000, 50000, 125000, 250000, 3750000};

    private final String title;
    private final int half;

    SpawnArea(String title, int half) {
        this.title = title;
        this.half = half;
    }

    /** Half the width, so a position counts as inside when both coordinates are within it. */
    public int half(int customWidth) {
        return this == Custom ? customWidth / 2 : half;
    }

    @Override
    public String toString() {
        return title;
    }
}
