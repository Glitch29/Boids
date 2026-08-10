package boids;

/**
 * Which (position, heading) states a boid can survive from, for one play area at one
 * turning radius.
 * <p>
 * Positions are integer pixels and each heading has a fixed integer step, so the
 * dynamics are a finite graph rather than an approximation of one. That makes the
 * question exactly answerable: a state is <b>alive</b> if some sequence of turns keeps
 * the boid inside the play area forever, and dead otherwise. The answer is computed by
 * backward reachability over the whole graph, so it holds for any shape — no
 * assumption about curvature, convexity or feature size anywhere.
 * <p>
 * A move is only legal if <em>every</em> pixel along its segment is in play, not just
 * its endpoint, so a boid can never step over a wall thinner than its stride.
 * <p>
 * The guarantee this buys: start every boid in a live state and let {@link
 * #constrainTurn} veto its choice, and a boid can never occupy an out-of-bounds pixel.
 * A live state has at least one live successor by definition, so the veto always has
 * something to offer.
 */
public final class NavMap {

    private final int width;
    private final int height;
    private final int radius;

    private final boolean[] oob;
    private final int[] score;

    /** Integer displacement per tick for each heading. */
    private final int[] stepX;
    private final int[] stepY;

    /** Bitsets over {@code (x, y, heading)}; see {@link #index}. */
    private final long[] alive;
    private final long[] passable;

    /** Fallback turn order when the proposed one is vetoed, indexed by {@code proposed + 1}. */
    private static final int[][] FALLBACK = {
            {-1, 0, 1},   // was turning left: try straight, then right
            {0, -1, 1},   // was straight: try left, then right
            {1, 0, -1},   // was turning right: try straight, then left
    };

    NavMap(int width, int height, int radius, boolean[] oob, int[] score,
           int[] stepX, int[] stepY, long[] alive, long[] passable) {
        this.width = width;
        this.height = height;
        this.radius = radius;
        this.oob = oob;
        this.score = score;
        this.stepX = stepX;
        this.stepY = stepY;
        this.alive = alive;
        this.passable = passable;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int radius() { return radius; }

    public int stepX(int heading) { return stepX[heading]; }
    public int stepY(int heading) { return stepY[heading]; }

    public boolean oob(int x, int y) { return oob[x + y * width]; }

    public int score(int x, int y) { return score[x + y * width]; }

    /** Anything off the image counts as out of bounds; the play area is the image. */
    public boolean traversable(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        return !oob[x + y * width];
    }

    /** Whether a boid here on this heading can stay in play indefinitely. */
    public boolean alive(int x, int y, int heading) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        return get(alive, index(x, y, heading));
    }

    /** Whether every pixel of this heading's step from here stays in play. */
    public boolean passable(int x, int y, int heading) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        return get(passable, index(x, y, heading));
    }

    /** How many of the headings here are survivable. Zero means the pixel is unreachable. */
    public int liveHeadings(int x, int y) {
        int count = 0;
        for (int h = 0; h < Params.TURNS; h++) if (alive(x, y, h)) count++;
        return count;
    }

    /**
     * The proposed turn if it keeps the boid alive, otherwise the nearest turn that
     * does.
     * <p>
     * Preferring the proposal means the flock's own decision stands unless it would be
     * fatal, rather than being overridden whenever a wall is merely nearby. From a live
     * state at least one turn always survives, so the only way to get the proposal back
     * unchanged when it is unsafe is to already be in a dead state — which cannot be
     * reached if every boid starts alive and every turn goes through here.
     */
    public int constrainTurn(int x, int y, int heading, int proposed) {
        for (int turn : FALLBACK[proposed + 1]) {
            if (survives(x, y, heading, turn)) return turn;
        }
        return proposed;
    }

    private boolean survives(int x, int y, int heading, int turn) {
        int next = Math.floorMod(heading + turn, Params.TURNS);
        if (!passable(x, y, next)) return false;
        return alive(x + stepX[next], y + stepY[next], next);
    }

    int index(int x, int y, int heading) {
        return (x + y * width) * Params.TURNS + heading;
    }

    private static boolean get(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }
}
