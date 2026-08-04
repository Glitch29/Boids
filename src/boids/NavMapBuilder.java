package boids;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a lattice of out-of-bounds flags plus a turning radius into a lattice of
 * prohibited heading ranges. See CONTRACTS.md for the derivation.
 * <p>
 * The core entry point takes booleans rather than an image so it can be exercised
 * without any PNG involved.
 */
public final class NavMapBuilder {
    private NavMapBuilder() {}

    private static final double INF = 1e18;

    /** Program-level entry point. {@code #000000} is out of bounds, anything else is traversable. */
    public static NavMap buildFromPng(Path png, int radius) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("not a readable image: " + png);
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] oob = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                oob[x + y * w] = (img.getRGB(x, y) & 0xFFFFFF) == 0x000000;
            }
        }
        return build(oob, w, h, radius);
    }

    /**
     * @param oob    row-major, {@code true} where the pixel is out of bounds
     * @param radius the boid's turning radius in pixels
     */
    public static NavMap build(boolean[] oob, int width, int height, int radius) {
        // Pad with out-of-bounds so that every sample point taken from an in-bounds
        // pixel lands inside the working grid, and so the play area is genuinely
        // bounded rather than open at the image edge.
        final int pad = radius + 2;
        final int pw = width + 2 * pad;
        final int ph = height + 2 * pad;

        boolean[] work = new boolean[pw * ph];
        Arrays.fill(work, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                work[(x + pad) + (y + pad) * pw] = oob[x + y * width];
            }
        }

        Labeling lab = label(work, pw, ph);

        List<List<NavMap.Range>> found = new ArrayList<>(width * height);
        for (int i = 0; i < width * height; i++) found.add(new ArrayList<>());

        // One sample every half pixel or so along C, with a floor that keeps angular
        // resolution usable at small radii.
        final int samples = Math.max(720, (int) Math.ceil(4.0 * Math.PI * radius));
        final double step = 360.0 / samples;

        double[] offX = new double[samples];
        double[] offY = new double[samples];
        for (int i = 0; i < samples; i++) {
            double phi = Math.toRadians(i * step);
            offX[i] = radius * Math.cos(phi);
            offY[i] = radius * Math.sin(phi);
        }

        // The distance transform measures to out-of-bounds pixel centres, but the
        // obstacle is really the union of those pixels' unit squares, whose boundary
        // sits about half a pixel further out. Without this the dilation is
        // systematically too small and every range comes out short.
        final double near2 = (radius + 0.5) * (radius + 0.5);
        final double far2 = 4.0 * radius * radius;

        boolean[] regionMask = new boolean[pw * ph];
        boolean[] inD = new boolean[pw * ph];
        boolean[] inside = new boolean[samples];

        for (int g = 0; g < lab.count; g++) {
            for (int i = 0; i < pw * ph; i++) regionMask[i] = lab.labels[i] == g;

            // Distance to this region alone. Thresholding at r gives the dilation D;
            // thresholding at 2r prunes pixels whose circle C cannot reach D at all.
            double[] d2 = squaredEdt(regionMask, pw, ph);
            for (int i = 0; i < pw * ph; i++) inD[i] = d2[i] <= near2;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (oob[x + y * width]) continue;

                    int px = x + pad;
                    int py = y + pad;
                    if (d2[px + py * pw] > far2) continue;

                    for (int i = 0; i < samples; i++) {
                        int qx = (int) Math.round(px + offX[i]);
                        int qy = (int) Math.round(py + offY[i]);
                        inside[i] = inD[qx + qy * pw];
                    }

                    collectRanges(inside, samples, step, found.get(x + y * width));
                }
            }
        }

        List<List<NavMap.Range>> out = new ArrayList<>(width * height);
        for (int i = 0; i < width * height; i++) out.add(NavMap.merge(found.get(i)));

        boolean[] oobCopy = Arrays.copyOf(oob, width * height);
        return new NavMap(width, height, radius, oobCopy, out);
    }

    /**
     * Finds every maximal arc of C lying inside D and emits a range for each one long
     * enough to survive.
     * <p>
     * Contract C2 means only one arc can ever exceed 180 degrees, since the arcs are
     * disjoint and sum to at most 360. Handling all of them anyway is what makes this
     * behave like a polygonal-chain treatment if a border ever comes within r of
     * itself.
     */
    private static void collectRanges(boolean[] inside, int samples, double step,
                                      List<NavMap.Range> out) {
        int firstFree = -1;
        for (int i = 0; i < samples; i++) {
            if (!inside[i]) { firstFree = i; break; }
        }
        // Every sample inside D means zero intersections with the border, not a
        // full-circle prohibition. See CONTRACTS.md: this requires a contract
        // violation, and the behaviour here is arbitrary by design.
        if (firstFree < 0) return;

        int i = 0;
        while (i < samples) {
            if (!inside[(firstFree + i) % samples]) { i++; continue; }

            int runStart = i;
            int count = 0;
            while (i < samples && inside[(firstFree + i) % samples]) { count++; i++; }

            // The true crossing sits between samples, so take the run as spanning
            // half a step beyond each end.
            double arcStart = (firstFree + runStart - 0.5) * step;
            double arcLen = count * step;
            if (arcLen > 180.0) {
                double arcEnd = arcStart + arcLen;
                out.add(new NavMap.Range(NavMap.norm(arcStart + 90.0),
                                         NavMap.norm(arcEnd - 90.0)));
            }
        }
    }

    // ---- Connected components ---------------------------------------------

    private record Labeling(int[] labels, int count) {}

    /** 8-connectivity on the out-of-bounds pixels; free pixels are labelled -1. */
    private static Labeling label(boolean[] mask, int w, int h) {
        int[] labels = new int[w * h];
        Arrays.fill(labels, -1);
        int[] stack = new int[w * h];
        int next = 0;

        for (int seed = 0; seed < w * h; seed++) {
            if (!mask[seed] || labels[seed] != -1) continue;
            int sp = 0;
            stack[sp++] = seed;
            labels[seed] = next;
            while (sp > 0) {
                int p = stack[--sp];
                int px = p % w;
                int py = p / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = px + dx;
                        int ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int q = nx + ny * w;
                        if (mask[q] && labels[q] == -1) {
                            labels[q] = next;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            next++;
        }
        return new Labeling(labels, next);
    }

    // ---- Exact squared Euclidean distance transform ------------------------

    /**
     * Squared distance from each cell to the nearest set cell of {@code mask},
     * by Felzenszwalb and Huttenlocher's lower-envelope method. Exact, and linear
     * in the number of pixels.
     */
    private static double[] squaredEdt(boolean[] mask, int w, int h) {
        int n = Math.max(w, h);
        double[] f = new double[n];
        double[] d = new double[n];
        int[] v = new int[n];
        double[] z = new double[n + 1];

        double[] out = new double[w * h];
        for (int i = 0; i < w * h; i++) out[i] = mask[i] ? 0.0 : INF;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) f[y] = out[x + y * w];
            transform1d(f, d, h, v, z);
            for (int y = 0; y < h; y++) out[x + y * w] = d[y];
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) f[x] = out[x + y * w];
            transform1d(f, d, w, v, z);
            for (int x = 0; x < w; x++) out[x + y * w] = d[x];
        }
        return out;
    }

    private static void transform1d(double[] f, double[] d, int n, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            double s = intersect(f, v[k], q);
            while (s <= z[k]) {
                k--;
                s = intersect(f, v[k], q);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    private static double intersect(double[] f, int p, int q) {
        return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }
}
