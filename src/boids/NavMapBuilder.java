package boids;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Computes the viability kernel of a play area: every (position, heading) from which a
 * boid can keep flying inside it forever.
 * <p>
 * There is no geometry here beyond the step table. Because positions are integer pixels
 * and each heading's step is a fixed integer vector, the reachable states form a finite
 * graph whose edges are exactly the moves a boid can make, and the answer falls out of
 * backward reachability on that graph: a state is dead when every one of its three
 * turns is dead or blocked, and killing a state can only kill its predecessors. That
 * needs no contract about the shape of the play area, which is the point — the previous
 * circle-sweep construction was a proxy that required the play area's concave features
 * and the obstacles' convex ones to both be gentler than the turning circle, and cusps
 * and thin spikes broke it in opposite directions.
 */
public final class NavMapBuilder {
    private NavMapBuilder() {}

    private static final int SCORE_ORANGE = 0xFF7F27;

    /**
     * {@code #000000} is out of bounds, anything else is traversable.
     * <p>
     * The path is resolved through {@link MapStore} first, so a caller naming a mutable
     * PNG still gets the frozen version — a navmap built here can never belong to a map
     * that has since been edited.
     */
    public static NavMap buildFromPng(Path png, int radius) throws IOException {
        png = MapStore.resolve(png);
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("not a readable image: " + png);

        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] oob = new boolean[w * h];
        int[] score = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                oob[x + y * w] = rgb == 0x000000;
                score[x + y * w] = rgb == SCORE_ORANGE ? 1 : 0;
            }
        }
        return build(oob, score, w, h, radius);
    }

    /**
     * Which direction of travel a state has to survive to count as navigable.
     * <p>
     * {@link #FORWARD} is the viability kernel proper: a boid here can keep flying
     * forever. {@link #BIDIRECTIONAL} additionally requires that a boid could have got
     * here, which is what makes every navigable state a state the simulation can actually
     * exhibit rather than only one it can be placed in.
     */
    public enum Navigability {
        /** Infinite future. Half the live states on a typical map have no possible past. */
        FORWARD,
        /** Infinite future and infinite past. The default, and what spawning wants. */
        BIDIRECTIONAL
    }

    public static NavMap build(boolean[] oob, int[] score, int width, int height, int radius) {
        return build(oob, score, width, height, radius, Navigability.BIDIRECTIONAL);
    }

    public static NavMap build(boolean[] oob, int[] score, int width, int height, int radius,
                               Navigability navigability) {
        int turns = Params.TURNS;

        int[] stepX = new int[turns];
        int[] stepY = new int[turns];
        steps(radius, stepX, stepY);

        int[][] path = segmentOffsets(stepX, stepY);
        long[] passable = passable(oob, width, height, path);
        long[] alive = kernel(oob, width, height, stepX, stepY, passable);
        if (navigability == Navigability.BIDIRECTIONAL) {
            restrictToArrivable(alive, passable, width, height, stepX, stepY);
        }

        return new NavMap(width, height, radius, oob, score, stepX, stepY, path, alive, passable);
    }

    /**
     * Drops live states with no infinite past, in a single sweep.
     * <p>
     * No iteration is needed even though "has an infinite past" is a fixed-point property,
     * because it can be read off the forward kernel directly. A boid's motion is
     * {@code (turn + move)*}, so running it backwards is
     * {@code (inverse_move + inverse_turn)*}; {@code inverse_turn} is a turn, and
     * {@code inverse_move} is {@code flip + move + flip}. Flip commutes with turn and is
     * its own inverse, so the chain collapses to {@code flip + move + (turn + move)*} —
     * and {@code (turn + move)*} is exactly what {@code alive} already answers.
     * <p>
     * The surviving set is closed under the dynamics in both directions, so removing a
     * state can never strand another: a successor of a state with an infinite past has one
     * too, through its predecessor.
     */
    private static void restrictToArrivable(long[] alive, long[] passable, int width, int height,
                                            int[] stepX, int[] stepY) {
        int turns = Params.TURNS;
        long[] arrivable = new long[alive.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int h = 0; h < turns; h++) {
                    int s = (x + y * width) * turns + h;
                    if (!get(alive, s)) continue;
                    int back = (h + turns / 2) % turns;
                    if (!get(passable, (x + y * width) * turns + back)) continue;
                    int px = x + stepX[back], py = y + stepY[back];
                    if (px < 0 || py < 0 || px >= width || py >= height) continue;
                    if (get(alive, (px + py * width) * turns + back)) set(arrivable, s);
                }
            }
        }
        System.arraycopy(arrivable, 0, alive, 0, alive.length);
    }

    /**
     * The per-heading step vectors, built so the table has the symmetry of a square.
     * <p>
     * Only the first octant is computed from trigonometry; the second is its reflection in
     * {@code y = x} and the other six are quarter-turn rotations of those. Deriving them
     * rather than evaluating {@code cos} and {@code sin} at each heading independently is
     * what makes the symmetry exact: {@code cos(2pi(16-h)/64)} and {@code sin(2pi h/64)}
     * are the same number in mathematics but not necessarily in floating point, and a
     * one-ulp disagreement is enough to round to a different pixel.
     */
    private static void steps(int radius, int[] stepX, int[] stepY) {
        int turns = Params.TURNS, oct = turns / 8, quad = turns / 4;
        double speed = Params.speed(radius);

        for (int h = 0; h <= oct; h++) {                       // 0 to 45 degrees
            stepX[h] = round(speed * Params.COS[h]);
            stepY[h] = round(speed * Params.SIN[h]);
        }
        for (int h = oct + 1; h <= quad; h++) {                // reflect in y = x
            stepX[h] = stepY[quad - h];
            stepY[h] = stepX[quad - h];
        }
        for (int h = quad + 1; h < turns; h++) {               // rotate a quarter turn
            stepX[h] = -stepY[h - quad];
            stepY[h] = stepX[h - quad];
        }
        for (int h = 0; h < turns; h++) {
            if (stepX[h] == 0 && stepY[h] == 0) {
                throw new IllegalArgumentException(
                        "turning radius " + radius + " gives a sub-pixel step at heading " + h);
            }
        }
    }

    /**
     * Symmetric rounding, so that {@code round(-v) == -round(v)}.
     * <p>
     * This is what makes a maximal turn close exactly. The headings are antipodally
     * symmetric — heading {@code h + TURNS/2} has precisely the opposite cosine — so odd
     * rounding makes the steps cancel in pairs and the sum around a full turn is zero
     * for any speed. Rounding by {@code floor(v + 0.5)} would not be odd and the circle
     * would drift.
     */
    static int round(double v) {
        return (int) (Math.signum(v) * Math.floor(Math.abs(v) + 0.5));
    }

    /**
     * The pixels a step passes through, per heading, excluding the origin.
     * <p>
     * A boid moves several pixels a tick, so checking only where it lands would let it
     * jump a wall thinner than its stride. Diagonal adjacency is enough — the path need
     * not be orthogonally connected, only dense enough that no wall fits between
     * consecutive samples.
     */
    private static int[][] segmentOffsets(int[] stepX, int[] stepY) {
        int turns = stepX.length, oct = turns / 8, quad = turns / 4;
        int[][] path = new int[turns][];
        for (int h = 0; h <= oct; h++) path[h] = octantPath(stepX[h], stepY[h]);
        for (int h = oct + 1; h <= quad; h++) path[h] = transform(path[quad - h], true);
        for (int h = quad + 1; h < turns; h++) path[h] = transform(path[h - quad], false);
        return path;
    }

    /**
     * The pixels a step passes through, for a heading in the first octant.
     * <p>
     * A boid moves several pixels a tick, so checking only where it lands would let it
     * jump a wall thinner than its stride.
     * <p>
     * Sampling is exact integer arithmetic and takes <em>both</em> candidates where the
     * line passes exactly between two pixels. That tie rule is not fussiness: it is the
     * only choice that makes a segment cover the same pixels in both directions of
     * travel. Rounding a tie one way makes the path bulge away from whichever end it
     * started at, so the same physical segment came out passable flown one way and blocked
     * flown the other — which it did, on 2,262 segments of dabnt, until 2026-08-16.
     * Taking both is also the conservative reading: a boid may not squeeze through a
     * corner the line only grazes.
     */
    private static int[] octantPath(int dx, int dy) {
        int[] offsets = new int[4 * dx];
        int n = 0;
        for (int k = 1; k <= dx; k++) {
            int y = k * dy / dx;
            int rem = k * dy - y * dx;
            if (2 * rem <= dx) { offsets[n++] = k; offsets[n++] = y; }
            if (2 * rem >= dx && rem != 0) { offsets[n++] = k; offsets[n++] = y + 1; }
        }
        return Arrays.copyOf(offsets, n);
    }

    /** Reflects a path in {@code y = x}, or rotates it a quarter turn. */
    private static int[] transform(int[] path, boolean reflect) {
        int[] out = new int[path.length];
        for (int i = 0; i < path.length; i += 2) {
            int x = path[i], y = path[i + 1];
            out[i] = reflect ? y : -y;
            out[i + 1] = x;
        }
        return out;
    }

    /** Whether every pixel of each heading's step from each pixel stays in play. */
    private static long[] passable(boolean[] oob, int width, int height, int[][] path) {
        int turns = Params.TURNS;
        long[] bits = new long[(width * height * turns + 63) >>> 6];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (oob[x + y * width]) continue;
                for (int h = 0; h < turns; h++) {
                    int[] offsets = path[h];
                    boolean clear = true;
                    for (int k = 0; k < offsets.length && clear; k += 2) {
                        int px = x + offsets[k];
                        int py = y + offsets[k + 1];
                        clear = px >= 0 && py >= 0 && px < width && py < height
                                && !oob[px + py * width];
                    }
                    if (clear) set(bits, (x + y * width) * turns + h);
                }
            }
        }
        return bits;
    }

    /**
     * Backward reachability. Every in-play state starts alive with a count of how many
     * of its turns lead somewhere; a state dies when that count reaches zero, and its
     * predecessors are decremented in turn.
     * <p>
     * The predecessors of a state are exact rather than approximate, which is what makes
     * a worklist possible instead of sweeping to a fixed point: the step is an integer
     * vector, so it inverts exactly. Everything reaching {@code (x, y, h)} sits at
     * {@code (x - stepX[h], y - stepY[h])} on one of the three headings that can turn
     * into {@code h}.
     */
    private static long[] kernel(boolean[] oob, int width, int height,
                                 int[] stepX, int[] stepY, long[] passable) {
        int turns = Params.TURNS;
        int states = width * height * turns;

        long[] alive = new long[(states + 63) >>> 6];
        byte[] liveTurns = new byte[states];
        int[] stack = new int[1 << 16];
        int top = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (oob[x + y * width]) continue;
                for (int h = 0; h < turns; h++) {
                    int count = 0;
                    for (int t = -1; t <= 1; t++) {
                        int next = Math.floorMod(h + t, turns);
                        if (get(passable, (x + y * width) * turns + next)) count++;
                    }
                    int s = (x + y * width) * turns + h;
                    liveTurns[s] = (byte) count;
                    if (count == 0) {
                        if (top == stack.length) stack = grow(stack);
                        stack[top++] = s;
                    } else {
                        set(alive, s);
                    }
                }
            }
        }

        while (top > 0) {
            int s = stack[--top];
            int h = s % turns;
            int pixel = s / turns;
            int x = pixel % width - stepX[h];
            int y = pixel / width - stepY[h];

            if (x < 0 || y < 0 || x >= width || y >= height) continue;
            if (oob[x + y * width]) continue;
            if (!get(passable, (x + y * width) * turns + h)) continue;

            for (int t = -1; t <= 1; t++) {
                int from = Math.floorMod(h - t, turns);
                int p = (x + y * width) * turns + from;
                if (!get(alive, p)) continue;
                if (--liveTurns[p] == 0) {
                    clear(alive, p);
                    if (top == stack.length) stack = grow(stack);
                    stack[top++] = p;
                }
            }
        }
        return alive;
    }

    private static int[] grow(int[] stack) {
        int[] bigger = new int[stack.length * 2];
        System.arraycopy(stack, 0, bigger, 0, stack.length);
        return bigger;
    }

    private static void set(long[] bits, int i) { bits[i >>> 6] |= 1L << (i & 63); }

    private static void clear(long[] bits, int i) { bits[i >>> 6] &= ~(1L << (i & 63)); }

    private static boolean get(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }
}
