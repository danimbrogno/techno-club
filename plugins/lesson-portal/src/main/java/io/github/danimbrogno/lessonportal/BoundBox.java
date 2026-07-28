package io.github.danimbrogno.lessonportal;

public final class BoundBox {
    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    private BoundBox(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static BoundBox of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new BoundBox(
                world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)
        );
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public String world() { return world; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public String describe() {
        return world + " (" + minX + "," + minY + "," + minZ + ") -> ("
                + maxX + "," + maxY + "," + maxZ + ")";
    }
}
