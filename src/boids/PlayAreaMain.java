package boids;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the annulus test area, runs the navigability transform at two turning radii,
 * and writes the visualisations.
 * <p>
 * Also prints a radial profile beside the closed-form answer for this particular
 * geometry, since an annulus is one of the few shapes where the prohibited ranges can
 * be solved exactly and checked against the sampled result.
 */
public final class PlayAreaMain {

    private static final int SIZE = 100;
    private static final double CENTRE = SIZE / 2.0;
    private static final double R_OUTER = 45.0;
    private static final double R_INNER = 15.0;

    private static final int RENDER_SCALE = 4;

    public static void main(String[] args) throws IOException {
        // Not out/ — that belongs to the IDE's compiler output and is marked excluded.
        Path dir = Path.of("render", "playarea");
        Files.createDirectories(dir);

        Path source = dir.resolve("annulus.png");
        writeTestArea(source);
        System.out.println("test area -> " + source);

        for (int radius : new int[]{10, 5}) {
            NavMap map = NavMapBuilder.buildFromPng(source, radius);
            Path out = dir.resolve("navmap_r" + radius + ".png");
            NavMapRender.write(map, RENDER_SCALE, out);
            System.out.printf("%nturning radius %d  ->  %s  (scale %dx)%n", radius, out, RENDER_SCALE);
            profile(map, radius);
            cardinals(map);
        }
    }

    /** Black 100x100, white disk of radius 45, black disk of radius 15, concentric. */
    private static void writeTestArea(Path out) throws IOException {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double d = Math.hypot(x + 0.5 - CENTRE, y + 0.5 - CENTRE);
                boolean free = d > R_INNER && d < R_OUTER;
                img.setRGB(x, y, free ? 0xFFFFFF : 0x000000);
            }
        }
        ImageIO.write(img, "png", out.toFile());
    }

    /**
     * Walks outward along one row and compares each pixel's computed ranges against
     * the exact answer for an annulus.
     */
    private static void profile(NavMap map, int radius) {
        int row = SIZE / 2;
        System.out.println("    d    n   computed (mid, length)          exact length");
        for (int x = 66; x <= 94; x += 2) {
            double dx = x + 0.5 - CENTRE;
            double dy = row + 0.5 - CENTRE;
            double d = Math.hypot(dx, dy);

            List<NavMap.Range> ranges = map.ranges(x, row);
            StringBuilder got = new StringBuilder();
            for (NavMap.Range r : ranges) {
                got.append(String.format("(%6.1f,%6.1f) ", r.midDeg(), r.lengthDeg()));
            }

            System.out.printf("%6.2f  %d   %-30s %s%n",
                    d, ranges.size(), got.toString().trim(), exact(d, radius));
        }
    }

    /**
     * The prohibited heading should always point at the obstacle responsible for it:
     * toward the centre in the inner band, away from it in the outer band. Angles are
     * screen convention, so 90 degrees is downward.
     */
    private static void cardinals(NavMap map) {
        int c = SIZE / 2;
        // {x, y, expected midpoint heading}
        int[][] probes = {
                {c, c - 18, 90}, {c, c - 42, 270},    // north of centre: in toward it is down
                {c + 18, c, 180}, {c + 42, c, 0},     // east
                {c, c + 18, 270}, {c, c + 42, 90},    // south
                {c - 18, c, 0}, {c - 42, c, 180},     // west
        };
        String[] names = {"N inner", "N outer", "E inner", "E outer",
                          "S inner", "S outer", "W inner", "W outer"};
        for (int i = 0; i < probes.length; i++) {
            List<NavMap.Range> rs = map.ranges(probes[i][0], probes[i][1]);
            String got = rs.isEmpty() ? "none" : String.format("%.1f", rs.get(0).midDeg());
            System.out.printf("  %-8s prohibited heading %-6s  expected %3d%n",
                    names[i], got, probes[i][2]);
        }
    }

    /**
     * Closed form for the annulus. The inner disk contributes a range centred on the
     * heading toward the centre (180 degrees), the outer wall one centred on the
     * heading away from it (0 degrees).
     */
    private static String exact(double d, double r) {
        double kIn = ((R_INNER + r) * (R_INNER + r) - d * d - r * r) / (2 * d * r);
        double kOut = ((R_OUTER - r) * (R_OUTER - r) - d * d - r * r) / (2 * d * r);

        StringBuilder sb = new StringBuilder();
        if (kIn > 0 && kIn < 1) {
            sb.append(String.format("(180.0,%6.1f) ", 180.0 - 2 * Math.toDegrees(Math.acos(kIn))));
        }
        if (kOut > -1 && kOut < 0) {
            sb.append(String.format("(  0.0,%6.1f) ", 2 * Math.toDegrees(Math.acos(kOut)) - 180.0));
        }
        return sb.length() == 0 ? "-" : sb.toString().trim();
    }
}
