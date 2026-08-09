package boids;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The simulation engine: it owns the play area's navigation and knows how to advance a
 * {@link State}. It holds nothing that changes as a run proceeds, so one instance can
 * drive any number of independent timelines concurrently.
 * <p>
 * Leaving the play area carries no special handling — a boid out of bounds is steered
 * by the navigation map like any other, and simply flies on if it is beyond the map's
 * reach. Leaving the <em>image</em> is fatal and throws.
 */
public final class Sim {

    /** Which boid an override steers. Fixed for now. */
    public static final int PSYBOID = 0;

    /** Ticks to swing an eighth of a turn — the shortest override worth committing to. */
    public static final int MIN_OVERRIDE_TICKS = Params.TURNS / 8;
    public static final int MAX_OVERRIDE_TICKS = 3 * MIN_OVERRIDE_TICKS;

    private final Engine engine;
    private List<State> states = new ArrayList<>();
    private final List<List<State>> printGrid = new ArrayList<>();
    private final Renderer renderer;
    private Random overrideRng = new Random(0);

    /**
     * @param playArea PNG where {@code #000000} is out of bounds
     * @param navRadius the turning radius the navigation map is built at. Normally
     *                  {@link Params#R_TURN}, but building at a larger radius is the
     *                  knob for making boids commit to their turns earlier than they
     *                  strictly must.
     */
    public Sim(ScenarioParameter parameter) throws IOException {
        engine = new Boids2DEngine(parameter);
        renderer = new Boids2DRenderer(parameter.mapPath());
    }

    public void startSeeds(long... seeds) {
        for (long seed : seeds) {
            states.add(engine.init(seed));
        }
        // Overrides drawn later come from the same seed that made the states, so a run
        // is reproducible end to end from what was passed here.
        if (seeds.length > 0) overrideRng = new Random(seeds[0]);
    }

    /**
     * A random override for the psyboid, beginning within {@code maxDelay} ticks of
     * wherever the timelines currently stand.
     * <p>
     * Everything else is fixed for now: an even split between turning each way with a
     * small chance of holding straight instead, and a duration between the time it
     * takes to swing an eighth of a turn and three times that.
     */
    public PsyboidOverride randomOverride(int maxDelay) {
        long now = states.isEmpty() ? 0 : states.get(0).tick;

        int onset = (int) now + overrideRng.nextInt(maxDelay + 1);
        int duration = MIN_OVERRIDE_TICKS
                + overrideRng.nextInt(MAX_OVERRIDE_TICKS - MIN_OVERRIDE_TICKS + 1);

        double roll = overrideRng.nextDouble();
        int direction = roll < 0.45 ? -1 : roll < 0.55 ? 0 : +1;

        return new PsyboidOverride(onset, duration, direction, PSYBOID);
    }

    /** A single-segment override, equivalent to {@code segmentedOverride(maxDelay, duration, 1)}. */
    public PsyboidOverride[] segmentedOverride(int maxDelay, int duration) {
        return segmentedOverride(maxDelay, duration, 1);
    }

    /** As {@link #segmentedOverride(int, int, int, int, int, Random)}, based at the current tick. */
    public PsyboidOverride[] segmentedOverride(int maxDelay, int duration, int segments) {
        long now = states.isEmpty() ? 0 : states.get(0).tick;
        return segmentedOverride((int) now, maxDelay, duration, segments, PSYBOID, overrideRng);
    }

    /**
     * An override chopped into consecutive segments, each free to turn either way or
     * hold straight. Maximum control over the psyboid rather than a single committed
     * sweep.
     * <p>
     * The whole thing starts somewhere in {@code [0, maxDelay]} past {@code baseTick},
     * then {@code segments - 1} cut points are drawn uniformly over the duration and
     * sorted, partitioning it into {@code segments} consecutive intervals. Each interval
     * picks a direction with equal probability. Cut points may coincide, which yields a
     * zero-length segment — harmless, since such an override never fires.
     */
    public static PsyboidOverride[] segmentedOverride(int baseTick, int maxDelay, int duration,
                                                      int segments, int psyboid, Random rng) {
        int start = baseTick + rng.nextInt(maxDelay + 1);

        int[] cuts = new int[segments - 1];
        for (int i = 0; i < cuts.length; i++) cuts[i] = rng.nextInt(duration);
        java.util.Arrays.sort(cuts);

        PsyboidOverride[] out = new PsyboidOverride[segments];
        int from = 0;
        for (int i = 0; i < segments; i++) {
            int to = i < cuts.length ? cuts[i] : duration;
            out[i] = new PsyboidOverride(start + from, to - from, rng.nextInt(3) - 1, psyboid);
            from = to;
        }
        return out;
    }

    /**
     * Fans each timeline out into one variant per override, preceded by an untouched
     * control. With a single state in and {@code k} overrides, the result is
     * {@code k + 1} states: the control at index 0, then the variants in order.
     */
    public void splitByOverrides(PsyboidOverride... overrides) {
        List<State> result = new ArrayList<>(states.size() * (overrides.length + 1));
        for (State s : states) {
            result.add(new State(s.n, s.x, s.y, s.h, s.tick, s.score, s.boidScore,
                    s.label + "|control"));
            for (PsyboidOverride o : overrides) {
                result.add(new State(s.n, s.x, s.y, s.h, s.tick, s.score, s.boidScore,
                        s.label + "|" + o.label(), o));
            }
        }
        states = result;
    }

    /**
     * Advance every state until it reaches {@code targetTick}. States already there are
     * left alone.
     * <p>
     * Run in parallel, one timeline per task. This is safe because a tick reads nothing
     * mutable: the engine's navigation map and its arrays are built in the constructor
     * and never written afterwards, so final-field semantics publish them safely, and
     * every working array a tick needs is allocated inside it. Timelines never see each
     * other. Encounter order is preserved, so results stay aligned with their seeds and
     * runs remain reproducible.
     */
    public void stepTo(long targetTick) {
        states = new ArrayList<>(states.parallelStream()
                .map(start -> {
                    State s = start;
                    while (s.tick < targetTick) s = engine.tick(s);
                    return s;
                })
                .toList());
    }

    /** Zeroes the accumulated score on every state, leaving the timelines untouched. */
    public void resetScores() {
        List<State> result = new ArrayList<>(states.size());
        for (State s : states) {
            result.add(new State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n],
                    s.label, s.psyboidOverrides));
        }
        states = result;
    }

    /** Per-boid accumulated score of each state, in the same order as {@link #scores()}. */
    public List<long[]> boidScores() {
        List<long[]> out = new ArrayList<>(states.size());
        for (State s : states) out.add(s.boidScore);
        return out;
    }

    /** Accumulated score of each state, in order. */
    public List<Long> scores() {
        List<Long> out = new ArrayList<>(states.size());
        for (State s : states) out.add(s.score);
        return out;
    }

    /** Scenario label of each state, in the same order as {@link #scores()}. */
    public List<String> labels() {
        List<String> out = new ArrayList<>(states.size());
        for (State s : states) out.add(s.label);
        return out;
    }

    public int size() { return states.size(); }

    public long tick() { return states.isEmpty() ? 0 : states.get(0).tick; }

    public void logAll() {
        printGrid.add(new ArrayList<>(states));
    }

    public void print(Path out) throws IOException {
        List<BufferedImage> rows = new ArrayList<>();
        for (List<State> states : printGrid) {
            List<BufferedImage> images = new ArrayList<>();
            for (State state : states) {
                images.add(renderer.render(state));
            }
            rows.add(stitchHorizontal(images));
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        writePng(out,stitchVertical(rows));
    }
    private static void writePng(Path out, BufferedImage image) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
    }

    static BufferedImage stitchHorizontal(List<BufferedImage> images) {
        BufferedImage result;
        {
            int w = 0;
            int h = 0;
            for (BufferedImage image : images) {
                w += image.getWidth();
                h = Math.max(h, image.getHeight());
            }
            result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = result.createGraphics();
        int x = 0;
        for (BufferedImage image : images) {
            g.drawImage(image,x,0,null);
            x += image.getWidth();
        }
        g.dispose();
        return result;
    }

    static BufferedImage stitchVertical(List<BufferedImage> images) {
        BufferedImage result;
        {
            int w = 0;
            int h = 0;
            for (BufferedImage image : images) {
                w = Math.max(w, image.getWidth());
                h += image.getHeight();
            }
            result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = result.createGraphics();
        int y = 0;
        for (BufferedImage image : images) {
            g.drawImage(image,0,y,null);
            y += image.getHeight();
        }
        g.dispose();
        return result;
    }

    /**
     * One instant of a timeline: every boid's position and heading, the tick, and the
     * override in force.
     * <p>
     * Treat instances as immutable. The arrays are exposed directly rather than copied,
     * because the decision layer reads them in its inner loop and a copy per access would
     * dominate the cost. Nothing writes to a {@code State} once it has been constructed —
     * {@link Sim#step} allocates fresh arrays and returns a new instance.
     * <p>
     * Because an old state can never be clobbered, the decide and act phases no longer
     * need separating: reading the previous tick is guaranteed safe by construction.
     */
    public static final class State {

        public final int n;
        public final double[] x;
        public final double[] y;
        public final int[] h;
        public final long tick;
        public final long score;

        /**
         * Score accumulated by each boid individually; sums to {@link #score}.
         * <p>
         * Kept alongside the total because a psyboid that simply parks itself in the
         * scoring zone is trivially visible, whereas one whose gain comes from moving
         * the rest of the flock is not. Telling those apart needs the breakdown.
         */
        public final long[] boidScore;

        /**
         * Identifies which scenario this timeline came from. Carried through every tick
         * untouched and appended to whenever a timeline is split, so a score at the end
         * of a batch can be traced back to the branch that produced it rather than
         * being an anonymous number in a list.
         */
        public final String label;

        public final PsyboidOverride[] psyboidOverrides;

        /**
         * Sets every field explicitly. Called by {@link Sim} and by the {@code with...}
         * methods below; there is no other way to build a state.
         */
        State(int n, double[] x, double[] y, int[] h, long tick, long score, long[] boidScore,
              String label, PsyboidOverride... psyboidOverrides) {
            this.n = n;
            this.x = x;
            this.y = y;
            this.h = h;
            this.tick = tick;
            this.score = score;
            this.boidScore = boidScore;
            this.label = label;
            this.psyboidOverrides = psyboidOverrides;
        }
    }
}
