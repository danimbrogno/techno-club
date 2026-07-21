package io.github.danimbrogno.regionlock;

public final class LockedZone {

    private final String name;
    private final String world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private boolean enabled;

    private LockedZone(
            String name,
            String world,
            boolean enabled,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        this.name = name;
        this.world = world;
        this.enabled = enabled;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static LockedZone of(
            String name,
            String world,
            int x1,
            int y1,
            int z1,
            int x2,
            int y2,
            int z2
    ) {
        return of(name, world, true, x1, y1, z1, x2, y2, z2);
    }

    public static LockedZone of(
            String name,
            String world,
            boolean enabled,
            int x1,
            int y1,
            int z1,
            int x2,
            int y2,
            int z2
    ) {
        return new LockedZone(
                name,
                world,
                enabled,
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.min(z1, z2),
                Math.max(x1, x2),
                Math.max(y1, y2),
                Math.max(z1, z2)
        );
    }

    public String name() {
        return name;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX
                && x <= maxX
                && y >= minY
                && y <= maxY
                && z >= minZ
                && z <= maxZ;
    }
}
