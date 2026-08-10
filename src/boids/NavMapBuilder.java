package boids;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

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

    /** {@code #000000} is out of bounds, anything else is traversable. */
    public static NavMap buildFromPng(Path png, int radius) throws IOException {
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

    public static NavMap build(boolean[] oob, int[] score, int width, int height, int radius) {
        int turns = Params.TURNS;
        double speed = Params.speed(radius);

        int[] stepX = new int[turns];
        int[] stepY = new int[turns];
        for (int h = 0; h < turns; h++) {
            stepX[h] = round(speed * Params.COS[h]);
            stepY[h] = round(speed * Params.SIN[h]);
            if (stepX[h] == 0 && stepY[h] == 0) {
                throw new IllegalArgumentException(
                        "turning radius " + radius + " gives a sub-pixel step at heading " + h);
            }
        }

        int[][] path = segmentOffsets(stepX, stepY);
        long[] passable = passable(oob, width, height, path);
        long[] alive = kernel(oob, width, height, stepX, stepY, passable);

        return new NavMap(width, height, radius, oob, score, stepX, stepY, alive, passable);
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
        int[][] path = new int[stepX.length][];
        for (int h = 0; h < stepX.length; h++) {
            int dx = stepX[h];
            int dy = stepY[h];
            int steps = Math.max(Math.abs(dx), Math.abs(dy));

            int[] offsets = new int[steps * 2];
            for (int k = 1; k <= steps; k++) {
                offsets[(k - 1) * 2] = round((double) k * dx / steps);
                offsets[(k - 1) * 2 + 1] = round((double) k * dy / steps);
            }
            path[h] = offsets;
        }
        return path;
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
