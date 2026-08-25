package boids;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One route against another, edges laid end to end in clock coordinates.
 * <p>
 * The per-edge panels each show one piece of the constraint; joining edges along a route puts
 * the pieces in the one coordinate where they belong together. Lengths add, so a state's place
 * along a route is its own tick plus the lengths of everything the route crossed to reach it,
 * and a boid running the route advances that coordinate by one a tick from end to end.
 * <p>
 * The two axes take separate routes, because they answer separate questions. A diagonal band
 * broken into pieces against one psyboid route is not a broken constraint — it is a constraint
 * belonging to a different route, showing only where the two happen to share an edge. Plotted
 * against the route it actually belongs to, it should run unbroken.
 * <p>
 * An edge appearing twice in a route is plotted twice, which is not double counting but the
 * point: a route returning to where it started shows the same constraint again one lap along,
 * and whether the two copies agree is the check.
 * <p>
 * Rounding waits until a coordinate is complete, so offsets stay real and the error does not
 * accumulate edge by edge along the route.
 */
public final class TwoBoidRouteSheet {
    private TwoBoidRouteSheet() {}

    /** Distinct enough to tell nine apart, and legible on a dark ground. */
    private static final int[] EDGE_COLOUR = {
            0xE6194B, 0x3CB44B, 0x4363D8, 0xF58231, 0x911EB4,
            0x42D4F4, 0xF032E6, 0xBFEF45, 0xFFE119};

    private static final int GROUND = 0x14171C;
    private static final int PANEL = 0x1E2129;
    private static final int RULE = 0x39404D;
    private static final int TEXT = 0xB9C1CE;

    public static void write(int[] live, int liveCount, int[] edge, EdgeMetric.Metric m,
                             TwoBoid.Reachable r, int[] boidRoute, int[] psyRoute, int scale,
                             Path out) throws IOException {
        double[] boidOff = offsets(boidRoute, m), psyOff = offsets(psyRoute, m);
        int[] boidSpan = extent(live, liveCount, edge, m, boidRoute, boidOff);
        int[] psySpan = extent(live, liveCount, edge, m, psyRoute, psyOff);
        int width = boidSpan[1] - boidSpan[0] + 1, height = psySpan[1] - psySpan[0] + 1;

        // Every place along each route a state can be plotted. A state whose edge a route
        // visits twice has two, and a state on both routes has a place on each.
        int[][] boidAt = places(live, liveCount, edge, m, boidRoute, boidOff, boidSpan[0]);
        int[][] psyAt = places(live, liveCount, edge, m, psyRoute, psyOff, psySpan[0]);
        int[] onBoid = new int[liveCount], onPsy = new int[liveCount];
        int nb = 0, np = 0;
        for (int i = 0; i < liveCount; i++) {
            if (boidAt[i] != null) onBoid[nb++] = i;
            if (psyAt[i] != null) onPsy[np++] = i;
        }

        byte[] grid = new byte[width * height];
        long plotted = 0;
        for (int a = 0; a < np; a++) {
            int i = onPsy[a];
            long base = (long) i * liveCount;
            byte colour = (byte) (edge[live[i]] + 1);
            for (int b = 0; b < nb; b++) {
                long at = base + onBoid[b];
                if ((r.bits()[(int) (at >>> 6)] & (1L << (at & 63))) == 0) continue;
                for (int y : psyAt[i]) {
                    for (int x : boidAt[onBoid[b]]) {
                        if (grid[y * width + x] == 0) plotted++;
                        grid[y * width + x] = colour;
                    }
                }
            }
        }

        String boidName = name(boidRoute), psyName = name(psyRoute);
        System.out.printf("%n  boid %s (%d states) against psyboid %s (%d states)%n",
                boidName, nb, psyName, np);
        System.out.printf("  axes %d..%d by %d..%d; %,d of %,d cells filled (%.2f%%)%n",
                boidSpan[0], boidSpan[1], psySpan[0], psySpan[1], plotted,
                (long) width * height, 100.0 * plotted / ((long) width * height));

        // How much of the psyboid's route is open, given where the boid is. Columns are
        // assigned by nominal length, so a state in the overrun where two segments share ticks
        // counts once, under the segment it nominally belongs to.
        System.out.printf("  %-6s %-9s %9s %9s%n", "step", "boid on", "columns", "psyboid can be");
        for (int k = 0; k < boidRoute.length; k++) {
            int from = Math.max(0, (int) Math.round(boidOff[k]) - boidSpan[0]);
            int to = Math.min(width,
                    (int) Math.round(boidOff[k] + m.length()[boidRoute[k]]) - boidSpan[0]);
            long cells = 0;
            int columns = 0;
            for (int x = from; x < to; x++, columns++) {
                for (int y = 0; y < height; y++) if (grid[y * width + x] != 0) cells++;
            }
            if (columns == 0) continue;
            System.out.printf("  %-6d %-9s %9d %8.2f%%%n", k, "edge " + boidRoute[k], columns,
                    100.0 * cells / ((long) columns * height));
        }

        int left = 58, top = 44;
        BufferedImage img = new BufferedImage(width * scale + left + 20,
                height * scale + top + 34, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(PANEL));
        g.fillRect(left, top, width * scale, height * scale);

        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int k = 0; k < boidRoute.length; k++) {
            int at = (int) Math.round(boidOff[k]) - boidSpan[0];
            if (at < 0 || at >= width) continue;
            g.setColor(new Color(RULE));
            g.fillRect(left + at * scale, top, 1, height * scale);
            g.setColor(new Color(EDGE_COLOUR[boidRoute[k] % EDGE_COLOUR.length]));
            g.drawString("e" + boidRoute[k], left + at * scale + 3, top - 4);
        }
        for (int k = 0; k < psyRoute.length; k++) {
            int at = (int) Math.round(psyOff[k]) - psySpan[0];
            if (at < 0 || at >= height) continue;
            g.setColor(new Color(RULE));
            g.fillRect(left, top + (height - 1 - at) * scale, width * scale, 1);
            g.setColor(new Color(EDGE_COLOUR[psyRoute[k] % EDGE_COLOUR.length]));
            g.drawString("e" + psyRoute[k], left - 26, top + (height - 1 - at) * scale - 3);
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = grid[y * width + x];
                if (c == 0) continue;
                g.setColor(new Color(EDGE_COLOUR[(c - 1) % EDGE_COLOUR.length]));
                g.fillRect(left + x * scale, top + (height - 1 - y) * scale, scale, scale);
            }
        }

        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString("boid " + boidName + " (horizontal) against psyboid " + psyName
                + " (vertical); colour is the psyboid's edge", 12, 20);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString(String.valueOf(boidSpan[0]), left, top + height * scale + 16);
        g.drawString(String.valueOf(boidSpan[1]), left + width * scale - 24,
                top + height * scale + 16);
        g.drawString(String.valueOf(psySpan[1]), left - 30, top + 10);
        g.drawString(String.valueOf(psySpan[0]), left - 30, top + height * scale);
        g.dispose();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
        System.out.printf("  wrote %s%n", out);
    }

    private static double[] offsets(int[] route, EdgeMetric.Metric m) {
        double[] offset = new double[route.length];
        for (int k = 1; k < route.length; k++) {
            offset[k] = offset[k - 1] + m.length()[route[k - 1]];
        }
        return offset;
    }

    /** Lowest and highest whole tick any state on the route reaches. */
    private static int[] extent(int[] live, int liveCount, int[] edge, EdgeMetric.Metric m,
                                int[] route, double[] offset) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int k = 0; k < route.length; k++) {
            for (int i = 0; i < liveCount; i++) {
                if (edge[live[i]] != route[k]) continue;
                int t = (int) Math.round(m.tick()[live[i]] + offset[k]);
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
        }
        return new int[]{lo, hi};
    }

    private static int[][] places(int[] live, int liveCount, int[] edge, EdgeMetric.Metric m,
                                  int[] route, double[] offset, int lo) {
        int[][] at = new int[liveCount][];
        for (int i = 0; i < liveCount; i++) {
            int e = edge[live[i]];
            int times = 0;
            for (int step : route) if (step == e) times++;
            if (times == 0) continue;
            int[] spots = new int[times];
            int put = 0;
            for (int k = 0; k < route.length; k++) {
                if (route[k] == e) {
                    spots[put++] = (int) Math.round(m.tick()[live[i]] + offset[k]) - lo;
                }
            }
            at[i] = spots;
        }
        return at;
    }

    private static String name(int[] route) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < route.length; k++) s.append(k > 0 ? "-" : "").append(route[k]);
        return s.toString();
    }
}
