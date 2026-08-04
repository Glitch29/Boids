package boids;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the play areas, runs the navigability transform over each, and writes the
 * visualisations.
 * <p>
 * The annulus is kept as a regression check: it is one of the few shapes whose
 * prohibited ranges can be solved exactly, so its computed profile is printed beside
 * the closed form.
 */
public final class PlayAreaMain {

    private static final Path DIR = Path.of("render", "playarea");

    public static void main(String[] args) throws IOException {
        PlayAreas.writePeanut();
        int radius = (int) Math.round(Params.R_TURN);
        System.out.printf("peanut %dx%d, r=%d -> %s%n",
                PlayAreas.PEANUT_W, PlayAreas.PEANUT_H, radius, PlayAreas.PEANUT);
        NavMapRender.write(NavMapBuilder.buildFromPng(PlayAreas.PEANUT, radius), 1,
                DIR.resolve("peanut_navmap.png"));

        PlayAreas.writeAnnulus();
        for (int r : new int[]{10, 5}) {
            NavMap map = NavMapBuilder.buildFromPng(PlayAreas.ANNULUS, r);
            NavMapRender.write(map, 4, DIR.resolve("annulus_navmap_r" + r + ".png"));
            System.out.printf("%nannulus, turning radius %d (scale 4x)%n", r);
            profile(map, r);
            cardinals(map);
        }
    }

    /**
     * Walks outward along one row and compares each pixel's computed ranges against
     * the exact answer for an annulus.
     */
    private static void profile(NavMap map, int radius) {
        int row = PlayAreas.ANNULUS_SIZE / 2;
        System.out.println("    d    n   computed (mid, length)          exact length");
        for (int x = 66; x <= 94; x += 2) {
            double dx = x + 0.5 - PlayAreas.ANNULUS_CENTRE;
            double dy = row + 0.5 - PlayAreas.ANNULUS_CENTRE;
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
        int c = PlayAreas.ANNULUS_SIZE / 2;
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
        double rIn = PlayAreas.ANNULUS_INNER;
        double rOut = PlayAreas.ANNULUS_OUTER;
        double kIn = ((rIn + r) * (rIn + r) - d * d - r * r) / (2 * d * r);
        double kOut = ((rOut - r) * (rOut - r) - d * d - r * r) / (2 * d * r);

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
