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

    /** The pixels each heading's step passes through; see {@link #stepPath}. */
    private final int[][] path;

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
           int[] stepX, int[] stepY, int[][] path, long[] alive, long[] passable) {
        this.width = width;
        this.height = height;
        this.radius = radius;
        this.oob = oob;
        this.score = score;
        this.stepX = stepX;
        this.stepY = stepY;
        this.path = path;
        this.alive = alive;
        this.passable = passable;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int radius() { return radius; }

    public int stepX(int heading) { return stepX[heading]; }
    public int stepY(int heading) { return stepY[heading]; }

    /**
     * The pixels this heading's step passes through, as {@code x, y} offset pairs from the
     * origin, excluding the origin and ending on the step itself.
     * <p>
     * This is the sample set {@link #passable} was built from, so it is exactly what the
     * collision test sees. Anything reasoning about where a boid is <em>between</em> two
     * ticks wants those samples rather than a second derivation of them.
     */
    public int[] stepPath(int heading) { return path[heading]; }

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

    public int index(int x, int y, int heading) {
        return (x + y * width) * Params.TURNS + heading;
    }

    /**
     * Where a boid in {@code state} ends up having asked for {@code turn}, or -1 if there is
     * nowhere legal to go.
     * <p>
     * The request goes through {@link #constrainTurn}, so this is the steered move: what a
     * boid asking for that turn actually does, veto included.
     */
    public int successor(int state, int turn) {
        int turns = Params.TURNS;
        int d = state % turns, cell = state / turns, x = cell % width, y = cell / width;
        int got = constrainTurn(x, y, d, turn);
        int nd = Math.floorMod(d + got, turns);
        int nx = x + stepX[nd], ny = y + stepY[nd];
        if (nx < 0 || ny < 0 || nx >= width || ny >= height || !alive(nx, ny, nd)) return -1;
        return index(nx, ny, nd);
    }

    /**
     * Every state reachable from {@code state} in one tick, listed once each.
     * <p>
     * Not the same as asking for each of the three turns in turn: the veto can answer two
     * different requests with the same turn, so a bare sweep of the requests reports the same
     * successor twice. That is harmless when the answer feeds a set, and quietly wrong when it
     * feeds a count — a graph built that way is not symmetric with
     * {@link #steeredPredecessors}, which does report each predecessor once.
     *
     * @param out filled with the successors; must hold at least three
     * @return how many there are
     */
    public int steeredSuccessors(int state, int[] out) {
        int n = 0;
        for (int turn = -1; turn <= 1; turn++) {
            int u = successor(state, turn);
            if (u < 0) continue;
            boolean seen = false;
            for (int i = 0; i < n && !seen; i++) seen = out[i] == u;
            if (!seen) out[n++] = u;
        }
        return n;
    }

    /**
     * Every state that can reach {@code state} in one tick, whatever it asked for.
     * <p>
     * There are only ever three candidates, and finding them needs no search. Run the tick
     * backwards: flip the heading, take the step, undo the turn, flip back. The flip works
     * because the step table is antipodally exact — {@code step(d + TURNS/2)} is precisely
     * {@code -step(d)} — so the move inverts on the integer lattice rather than approximately.
     * That leaves one pixel and three headings, and only the turn is unknown.
     * <p>
     * Candidates are then confirmed forwards, because arriving at a live state from a live
     * state is not enough on its own: the segment between them still has to be clear, and a
     * turn the veto refuses is not a turn that was taken.
     *
     * @param out filled with the predecessors; must hold at least three
     * @return how many there are
     */
    public int steeredPredecessors(int state, int[] out) {
        int turns = Params.TURNS;
        int d = state % turns, cell = state / turns, x = cell % width, y = cell / width;
        int back = (d + turns / 2) % turns;                     // flip
        int px = x + stepX[back], py = y + stepY[back];         // move
        if (px < 0 || py < 0 || px >= width || py >= height) return 0;
        int n = 0;
        for (int t = -1; t <= 1; t++) {
            int pd = Math.floorMod(back + t + turns / 2, turns);  // turn, then flip back
            if (!alive(px, py, pd)) continue;
            int p = index(px, py, pd);
            for (int ask = -1; ask <= 1; ask++) {
                if (successor(p, ask) == state) { out[n++] = p; break; }
            }
        }
        return n;
    }

    /** The same, but only those that arrive here by steering straight. */
    public int unsteeredPredecessors(int state, int[] out) {
        int turns = Params.TURNS;
        int d = state % turns, cell = state / turns, x = cell % width, y = cell / width;
        int back = (d + turns / 2) % turns;
        int px = x + stepX[back], py = y + stepY[back];
        if (px < 0 || py < 0 || px >= width || py >= height) return 0;
        int n = 0;
        for (int t = -1; t <= 1; t++) {
            int pd = Math.floorMod(back + t + turns / 2, turns);
            if (!alive(px, py, pd)) continue;
            int p = index(px, py, pd);
            if (successor(p, 0) == state) out[n++] = p;
        }
        return n;
    }

    private static boolean get(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }
}
