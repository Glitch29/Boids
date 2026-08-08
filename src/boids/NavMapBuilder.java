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

    private static final int SCORE_ORANGE = 0xFF7F27;
    private static final double INF = 1e18;

    /**
     * A range spanning the full circle cannot be represented, since its two bounds
     * would coincide. Capping just short keeps the midpoint meaningful, which both
     * the visualiser's hue and the forced turn depend on. The visual difference from
     * a true 360 is nil: value has already fallen to zero.
     */
    private static final double MAX_RANGE = 359.9;

    /**
     * Smallest arc the radius-2r sweep is allowed to emit.
     * <p>
     * That sweep only runs on pixels lying beyond the border, where the geometry says
     * the answer is always between 180 and 360 degrees. Anything narrower is an
     * artefact — usually speckle where the circle runs tangent to the dilation edge
     * and consecutive samples round to pixels on opposite sides of the threshold,
     * producing a rash of one-sample slivers. The cut-off is well clear of 180 so that
     * a genuine range roughed up by pixelation still survives.
     * <p>
     * The radius-r sweep keeps its narrow ranges: there they are real, and they are
     * exactly the case of a boid pointed straight at a wall.
     */
    private static final double MIN_FAR_ARC = 90.0;

    /** Program-level entry point. {@code #000000} is out of bounds, anything else is traversable. */
    public static NavMap buildFromPng(Path png, int radius) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("not a readable image: " + png);
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] oob = new boolean[w * h];
        int[] score = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                oob[x + y * w] = (img.getRGB(x, y) & 0xFFFFFF) == 0x000000;
                score[x + y * w] = (img.getRGB(x, y) & 0xFFFFFF) == SCORE_ORANGE ? 1 : 0;
            }
        }
        return build(oob, score, w, h, radius);

    }

    /**
     * @param oob    row-major, {@code true} where the pixel is out of bounds
     * @param radius the boid's turning radius in pixels
     */
    public static NavMap build(boolean[] oob, int[] score, int width, int height, int radius) {
        List<List<NavMap.Range>> found = new ArrayList<>(width * height);
        for (int i = 0; i < width * height; i++) found.add(new ArrayList<>());

        addRanges(oob, width, height, radius, found);

        List<List<NavMap.Range>> out = new ArrayList<>(width * height);
        for (int i = 0; i < width * height; i++) out.add(NavMap.merge(found.get(i)));

        return new NavMap(width, height, radius, Arrays.copyOf(oob, width * height), score, out);
    }

    /**
     * The prohibited headings for every pixel, one out-of-bounds region at a time.
     * <p>
     * A pixel is classified by how the circle of radius r about it meets the dilated
     * border, and there are exactly three cases:
     * <ul>
     * <li><b>It crosses.</b> The pixel sits within reach of the border on the play
     *     side. The arc lying inside the dilation, taken in increasing angle order,
     *     gives the prohibited range {@code [arcStart + 90, arcEnd - 90]} — headings
     *     from which no turn saves the boid. These come out between 0 and 180 degrees.
     * <li><b>It lies wholly inside the dilation.</b> The pixel is on the far side of
     *     the border: out of bounds, or in a concave pocket too tight for the boid's
     *     turning circle to fit. The circle of radius 2r is used instead and its
     *     inside arc is taken <em>without</em> the quarter-turn adjustments, giving a
     *     range between 180 and 360 degrees.
     * <li><b>It lies wholly outside.</b> Nothing is prohibited.
     * </ul>
     * Treating both sides with one sweep is what closes the gap that used to leave
     * unrestricted pixels stranded along borders whose curvature only barely honours
     * the traversability contract, where pixelation can push a pocket below the
     * turning circle even though the continuous shape obeys it.
     */
    private static void addRanges(boolean[] oob, int width, int height, int radius,
                                  List<List<NavMap.Range>> found) {
        // Pad with out-of-bounds so that every sample point lands inside the working
        // grid, and so the play area is genuinely bounded rather than open at the image
        // edge. The radius-2r sweep reaches twice as far, so the padding must too.
        final int pad = 2 * radius + 2;
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

        // An interior obstacle smaller than the circle of radius 2r is invisible to
        // the sweeps: the radius-r circle about a pixel inside it lies wholly within
        // the dilation, while the radius-2r circle clears the dilation entirely, so
        // neither crosses the border. No sweep radius helps — at the centre of a
        // symmetric blob every circle is uniformly in or out. Those regions get an
        // ellipse fit instead, and every one of their pixels is painted regardless of
        // distance. Contract: such regions must be convex.
        int[] size = new int[lab.count];
        for (int g : lab.labels) if (g >= 0) size[g]++;

        double smallLimit = Math.PI * (2.0 * radius) * (2.0 * radius);
        boolean[] byEllipse = new boolean[lab.count];
        for (int g = 0; g < lab.count; g++) {
            byEllipse[g] = g != lab.exterior && size[g] < smallLimit;
        }
        Ellipse[] ellipses = fitEllipses(lab, pw, ph);

        // One sample every half pixel or so along C, with a floor that keeps angular
        // resolution usable at small radii.
        final int samples = Math.max(720, (int) Math.ceil(4.0 * Math.PI * radius));
        final double step = 360.0 / samples;

        double[] offX = new double[samples];
        double[] offY = new double[samples];
        double[] farX = new double[samples];
        double[] farY = new double[samples];
        for (int i = 0; i < samples; i++) {
            double phi = Math.toRadians(i * step);
            offX[i] = radius * Math.cos(phi);
            offY[i] = radius * Math.sin(phi);
            farX[i] = 2.0 * radius * Math.cos(phi);
            farY[i] = 2.0 * radius * Math.sin(phi);
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
            // thresholding at 2r prunes pixels whose circle of radius r cannot reach D
            // at all. The radius-2r sweep only ever runs on pixels inside D, so it is
            // covered by the same prune.
            double[] d2 = squaredEdt(regionMask, pw, ph);
            for (int i = 0; i < pw * ph; i++) inD[i] = d2[i] <= near2;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int px = x + pad;
                    int py = y + pad;

                    if (byEllipse[g] && lab.labels[px + py * pw] == g) {
                        double eject = ellipses[g].ejectionDeg(px, py);
                        // The centre of a symmetric region has no preferred direction.
                        if (Double.isNaN(eject)) eject = 0.0;
                        // Prohibit heading away from the exit, leaving the half circle
                        // that makes progress toward it.
                        double mid = NavMap.norm(eject + 180.0);
                        found.get(x + y * width).add(new NavMap.Range(
                                NavMap.norm(mid - 90.0), NavMap.norm(mid + 90.0)));
                        continue;
                    }

                    if (d2[px + py * pw] > far2) continue;

                    int in = 0;
                    for (int i = 0; i < samples; i++) {
                        int qx = (int) Math.round(px + offX[i]);
                        int qy = (int) Math.round(py + offY[i]);
                        inside[i] = inD[qx + qy * pw];
                        if (inside[i]) in++;
                    }

                    if (in > 0 && in < samples) {
                        collectRanges(inside, samples, step, true, found.get(x + y * width));
                    } else if (in == samples) {
                        // The whole circle is inside the dilation, so it never crosses
                        // the border and the quarter-turn construction has nothing to
                        // work with. Step out to 2r, which does cross.
                        int far = 0;
                        for (int i = 0; i < samples; i++) {
                            int qx = (int) Math.round(px + farX[i]);
                            int qy = (int) Math.round(py + farY[i]);
                            inside[i] = inD[qx + qy * pw];
                            if (inside[i]) far++;
                        }
                        if (far > 0 && far < samples) {
                            collectRanges(inside, samples, step, false, found.get(x + y * width));
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds every maximal arc lying inside the dilation and emits a range for each.
     * <p>
     * Contract C2 means only one arc can ever exceed 180 degrees, since the arcs are
     * disjoint and sum to at most 360. Handling all of them anyway is what makes this
     * behave like a polygonal-chain treatment if a border ever comes within r of
     * itself.
     *
     * @param quarterTurns whether to pull each end in by 90 degrees. True for the
     *                     radius-r sweep, where the arc is the set of blocked turning
     *                     circle centres and the two quarter turns convert it into
     *                     headings. False for the radius-2r sweep, where the arc is
     *                     already the answer.
     */
    private static void collectRanges(boolean[] inside, int samples, double step,
                                      boolean quarterTurns, List<NavMap.Range> out) {
        int firstFree = -1;
        for (int i = 0; i < samples; i++) {
            if (!inside[i]) { firstFree = i; break; }
        }
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
            double arcEnd = arcStart + arcLen;

            if (quarterTurns) {
                if (arcLen > 180.0) {
                    out.add(new NavMap.Range(NavMap.norm(arcStart + 90.0),
                                             NavMap.norm(arcEnd - 90.0)));
                }
            } else if (arcLen > MIN_FAR_ARC) {
                double len = Math.min(arcLen, MAX_RANGE);
                out.add(new NavMap.Range(NavMap.norm(arcStart),
                                         NavMap.norm(arcStart + len)));
            }
        }
    }

    // ---- Connected components ---------------------------------------------

    /**
     * @param exterior the label of the region reachable from outside the image. The
     *                 padding is a solid out-of-bounds frame, so it is one component
     *                 and every region merged into it is part of the outer boundary.
     *                 Everything else is an interior obstacle.
     */
    private record Labeling(int[] labels, int count, int exterior) {}

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
        return new Labeling(labels, next, labels[0]);
    }

    /**
     * The best-fitting ellipse for one region, by second moments — the statistical
     * reading of the shape rather than the geometric one. Scale is discarded; only the
     * centre, the orientation of the principal axes and the ratio of their extents
     * matter.
     *
     * @param lambda1 variance along the major axis, {@code lambda2} along the minor
     */
    private record Ellipse(double cx, double cy,
                           double axisX, double axisY,
                           double lambda1, double lambda2) {

        /**
         * The outward normal of the similar ellipse through this point, which is the
         * direction that leaves the obstacle soonest.
         * <p>
         * In the principal frame the gradient of {@code u²/λ₁ + v²/λ₂} is
         * {@code (u/λ₁, v/λ₂)}; multiplying through by {@code λ₁λ₂} gives the same
         * direction as {@code (u·λ₂, v·λ₁)} with no division, so a region flat enough
         * that {@code λ₂} is zero ejects cleanly across its short axis instead of
         * dividing by it.
         *
         * @return degrees, or {@code NaN} at the centre of a symmetric region, where
         *         no direction is preferred
         */
        double ejectionDeg(double x, double y) {
            double dx = x - cx;
            double dy = y - cy;

            double u = dx * axisX + dy * axisY;
            double v = -dx * axisY + dy * axisX;

            double nu = u * lambda2;
            double nv = v * lambda1;

            double nx = nu * axisX - nv * axisY;
            double ny = nu * axisY + nv * axisX;

            if (nx == 0.0 && ny == 0.0) return Double.NaN;
            return Math.toDegrees(Math.atan2(ny, nx));
        }
    }

    /** Centroid and covariance of every region, accumulated in a single pass. */
    private static Ellipse[] fitEllipses(Labeling lab, int w, int h) {
        int regions = lab.count;
        double[] n = new double[regions];
        double[] sx = new double[regions], sy = new double[regions];
        double[] sxx = new double[regions], syy = new double[regions], sxy = new double[regions];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = lab.labels[x + y * w];
                if (g < 0) continue;
                n[g]++;
                sx[g] += x;
                sy[g] += y;
                sxx[g] += (double) x * x;
                syy[g] += (double) y * y;
                sxy[g] += (double) x * y;
            }
        }

        Ellipse[] out = new Ellipse[regions];
        for (int g = 0; g < regions; g++) {
            if (n[g] == 0) continue;
            double cx = sx[g] / n[g];
            double cy = sy[g] / n[g];
            double vxx = sxx[g] / n[g] - cx * cx;
            double vyy = syy[g] / n[g] - cy * cy;
            double vxy = sxy[g] / n[g] - cx * cy;

            // Eigen-decomposition of the 2x2 covariance matrix.
            double disc = Math.sqrt((vxx - vyy) * (vxx - vyy) + 4.0 * vxy * vxy);
            double lambda1 = (vxx + vyy + disc) / 2.0;
            double lambda2 = (vxx + vyy - disc) / 2.0;

            double ax, ay;
            if (Math.abs(vxy) > 1e-12) {
                ax = lambda1 - vyy;
                ay = vxy;
                double m = Math.hypot(ax, ay);
                ax /= m;
                ay /= m;
            } else {
                // Already axis-aligned; pick whichever axis carries more spread.
                ax = vxx >= vyy ? 1.0 : 0.0;
                ay = vxx >= vyy ? 0.0 : 1.0;
            }
            out[g] = new Ellipse(cx, cy, ax, ay, lambda1, Math.max(lambda2, 0.0));
        }
        return out;
    }

    // ---- Exact Euclidean distance transform --------------------------------

    /**
     * Squared distance from each cell to the nearest set cell of the mask, by
     * Felzenszwalb and Huttenlocher's lower-envelope method. Exact, and linear in the
     * number of pixels.
     * <p>
     * Only the distance is needed now. Direction used to be recovered here as well, to
     * point out-of-bounds pixels back toward the nearest in-bounds one; sweeping a
     * circle instead gets the direction from the border's actual shape rather than
     * from a vector to one integer-coordinate pixel, which is both simpler and free of
     * the angular quantisation that produced radial banding close to the border.
     */
    private static double[] squaredEdt(boolean[] mask, int w, int h) {
        int n = Math.max(w, h);
        double[] f = new double[n];
        double[] d = new double[n];
        int[] v = new int[n];
        double[] z = new double[n + 1];

        double[] dist = new double[w * h];
        for (int i = 0; i < w * h; i++) dist[i] = mask[i] ? 0.0 : INF;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) f[y] = dist[x + y * w];
            transform1d(f, d, h, v, z);
            for (int y = 0; y < h; y++) dist[x + y * w] = d[y];
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) f[x] = dist[x + y * w];
            transform1d(f, d, w, v, z);
            for (int x = 0; x < w; x++) dist[x + y * w] = d[x];
        }
        return dist;
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
