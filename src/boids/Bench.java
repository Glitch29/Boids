package boids;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The psyboid benchmark: every algorithm on the same seeds, against the same controls, with what
 * it spent alongside what it scored.
 * <p>
 * <b>Why the controls are the point.</b> A psyboid score means nothing on its own — a map where
 * the flock scores anyway and a map where nothing scores without help produce the same number for
 * different reasons. Three controls bracket it:
 * <ol>
 *   <li><b>none</b> — no override at all. What the seed does by itself, and the floor.</li>
 *   <li><b>route</b> — the psyboid held to the price-optimal scoring cycle and otherwise coasting.
 *       <b>The one that matters.</b> It is pure selfishness with no attention to the flock, so
 *       anything that does not beat it is not herding, whatever else it is doing.</li>
 *   <li><b>bits</b> — the established branch search, at its own budget and at a much larger one,
 *       so the difference between them says how much of any gap is compute rather than method.</li>
 * </ol>
 *
 * <h2>The lookahead is a map property, not a constant</h2>
 * The search's coasting lookahead was 320 ticks with a per-second discount of 0.95, both fitted on
 * a map whose scoring lap is 506 ticks. On plait, whose lap is 1,738, that discounts the payoff of
 * every decision to 0.006 of its face value and the search declines everything. So both are
 * derived here: <b>the lookahead is one optimal scoring lap, and the discount is set so that a
 * point at the end of it is worth 0.4</b> — {@code alpha = 0.4 ^ (SECOND / lap)}. That is the
 * shape which has worked before, expressed in terms the map supplies.
 *
 * <h2>What is spent</h2>
 * <b>Impactful ticks, not override ticks.</b> An override the map refuses, or one the flock would
 * have obeyed anyway, changes nothing; what a psyboid spends is the ticks on which its turn
 * <em>after the veto</em> differed from what the rules alone would have produced. Compute time is
 * reported per thousand ticks of corpus, which is the budget the goal is stated in.
 */
public final class Bench {
    private Bench() {}

    /** One map at one flock size. */
    public record Scenario(PresetScenarioParameter preset, SolverFacts.Gate gate, int boids) {

        public String name() { return preset.name().toLowerCase(java.util.Locale.ROOT) + "-" + boids; }
    }

    /**
     * What one algorithm did on one scenario.
     *
     * @param occFlock   mean fraction of the whole flock in a scoring zone
     * @param occPsy     the psyboid's own share, already a rate since it is one boid
     * @param occOthers  everyone else's, which is what herding moves
     * @param impactful  ticks the psyboid's turn differed from the rules' after the veto
     * @param seconds    wall clock for the whole scenario, search included
     */
    public record Row(String scenario, String algorithm, int seeds, int ticks, double occFlock,
                      double occPsy, double occOthers, double perTick, double impactful,
                      double seconds) {

        /** Seconds per thousand ticks of corpus — the budget the goal is stated in. */
        public double cost(int seeds, int ticks) {
            return 1000 * seconds / ((double) seeds * ticks);
        }

        /**
         * Occupancy per thousand impactful ticks: what the psyboid got for what it spent.
         * <p>
         * <b>The ranking changes when override ticks are priced.</b> They are unbudgeted today and
         * will not stay that way, and on dabnt the scheduled search outscores the pilot while
         * spending nearly twice as many — so per tick spent the pilot is ahead. A column that only
         * shows what was scored hides which algorithm would survive a budget.
         */
        public double perSpend() {
            return impactful <= 0 ? Double.NaN : 1000 * occFlock / impactful;
        }
    }

    /** An algorithm under test: given a warmed flock, install whatever steering it wants. */
    public interface Entrant {
        String name();

        /** Overrides to install on a flock about to be flown from {@code warm}. */
        PsyboidOverride[] steer(Prepared p, long seed) throws IOException;
    }

    /** Everything derived from a scenario once, so an entrant does not rebuild it per seed. */
    public record Prepared(Scenario scenario, Pipeline.Built built, EdgePrice.Price price,
                           Spawn spawn, PsyboidBits.Branches branches, int warm, int lap,
                           double alpha) {

        public SolverFacts facts() { return built.facts(); }

        public NavMap map() { return built.labelling().map(); }
    }

    /**
     * Derives everything a scenario needs, including the map-derived lookahead and discount.
     */
    public static Prepared prepare(Scenario s, CorpusPreset recipe) throws IOException {
        Pipeline.Built built = Pipeline.build(s.preset(), s.gate(), recipe);
        EdgePrice.Price price = EdgePrice.of(built.labelling().map(), built.facts(), true);
        Spawn spawn = Spawn.of(s.preset(), built.facts(), built.plus(), recipe.spawn())
                .resized(s.boids());
        int lap = (int) Math.ceil(price.best() == null ? 320 : price.best().ticks());
        // A point at the far end of the lookahead is worth 0.4 of one collected now.
        double alpha = Math.pow(0.4, PsyboidBits.SECOND / (double) lap);
        return new Prepared(s, built, price, spawn, built.branches(), built.warm(), lap, alpha);
    }

    /** Runs one entrant over the seeds and measures it. */
    public static Row run(Prepared p, Entrant entrant, int seeds, int ticks) throws IOException {
        Boids2DEngine engine = p.spawn().engine();
        MovementLogic rules = new MovementLogic(p.spawn().scenario().turningRadius());
        double flock = 0, psy = 0, others = 0, impact = 0;
        long began = System.nanoTime();

        for (long seed = 0; seed < seeds; seed++) {
            PsyboidOverride[] steering = entrant.steer(p, seed);
            Sim.State s = p.spawn().warmed(seed, p.warm());
            s = SimTest.withOverrides(s, steering);
            long base = s.score, basePsy = s.boidScore[Sim.PSYBOID], from = s.tick;

            int[] spent = {0};
            long until = from + ticks;
            engine.trace((tick, i, boids, want) -> {
                if (i != Sim.PSYBOID || tick < from || tick >= until) return;
                int x = boids.x()[i], y = boids.y()[i], h = boids.h()[i];
                if (p.map().constrainTurn(x, y, h, want)
                        != p.map().constrainTurn(x, y, h, rules.decompose(boids, i).turn())) {
                    spent[0]++;
                }
            });
            try {
                while (s.tick < until) s = engine.tick(s);
            } finally {
                engine.trace(null);
            }
            flock += (s.score - base) / (double) ticks / p.scenario().boids();
            psy += (s.boidScore[Sim.PSYBOID] - basePsy) / (double) ticks;
            others += p.scenario().boids() <= 1 ? 0
                    : ((s.score - base) - (s.boidScore[Sim.PSYBOID] - basePsy))
                            / (double) ticks / (p.scenario().boids() - 1);
            impact += spent[0];
        }
        double secs = (System.nanoTime() - began) / 1e9;
        return new Row(p.scenario().name(), entrant.name(), seeds, ticks, flock / seeds,
                psy / seeds, others / seeds, flock / seeds * p.scenario().boids(), impact / seeds,
                secs);
    }

    /** No steering at all: what the seed does by itself. */
    public static Entrant none() {
        return new Entrant() {
            public String name() { return "none"; }

            public PsyboidOverride[] steer(Prepared p, long seed) {
                return new PsyboidOverride[0];
            }
        };
    }

    /**
     * The psyboid held to the price-optimal scoring cycle, coasting otherwise.
     * <p>
     * Pure selfishness: it reads the map and never looks at the flock. <b>The control any claim
     * about herding has to beat</b>, and the one a search that only values its own score
     * converges to.
     */
    public static Entrant route() {
        return new Entrant() {
            public String name() { return "route"; }

            public PsyboidOverride[] steer(Prepared p, long seed) {
                return new PsyboidOverride[]{EdgePilot.of(Sim.PSYBOID, p.price().exit(), p.map(),
                        p.facts(), p.built().labelling())};
            }
        };
    }

    /**
     * The established branch search, with the map-derived lookahead and discount.
     * <p>
     * <b>Told how long the run is.</b> The search commits decisions for as long as its {@code run}
     * asks, so planning past the measured window is work thrown away — and it is not a little:
     * planning 100,000 ticks to fly 5,000 was costing twenty times the compute the number was
     * meant to report.
     *
     * @param spreadFactor 1 for its own budget; larger to see how much of any gap is compute
     */
    public static Entrant bits(String name, double spreadFactor, boolean priced, int run) {
        return new Entrant() {
            int lastSpread;

            public String name() { return lastSpread == 0 ? name : name + "/" + lastSpread; }

            public PsyboidOverride[] steer(Prepared p, long seed) throws IOException {
                int floor = PsyboidBits.minimumSpread(p.facts(), p.branches());
                int spread = (int) Math.ceil(Math.max(640, floor) * spreadFactor);
                PsyboidBits.Config config = PsyboidBits.Config.of(p.warm(), spread, p.lap(),
                        p.alpha(), run, PsyboidBits.SETTLE);
                if (priced) config = config.priced(p.price());
                PsyboidBits.Plan plan = PsyboidBits.search(p.spawn(), p.map(), p.facts(),
                        p.branches(), config, seed);
                lastSpread = spread;
                return plan.overrides();
            }
        };
    }

    /**
     * The search decides; a pilot executes. <b>The two failures are separate and this separates
     * them.</b>
     * <p>
     * A held turn is a schedule: the search predicts when the psyboid will reach the last state a
     * straight tick would commit it wrongly, and writes a turn there with eight ticks of margin on
     * each side. On a map whose branch edge is a hundred ticks long that lands; on plait, where the
     * prediction runs up to 775 ticks ahead, the boid has drifted further than the margin by the
     * time it arrives and <b>the turn simply misses</b>. Measured: the searches take plait's
     * scoring exit less than half as often as a pilot that never predicts anything.
     * <p>
     * So each decision the search commits becomes an {@link EdgePilot} confined to that decision's
     * own stretch of the timeline, carrying the route the search chose rather than the tick it
     * guessed. Deciding and steering stop sharing a failure mode.
     */
    public static Entrant piloted(String name, double spreadFactor, boolean priced, int run) {
        return new Entrant() {
            int lastSpread;

            public String name() { return lastSpread == 0 ? name : name + "/" + lastSpread; }

            public PsyboidOverride[] steer(Prepared p, long seed) throws IOException {
                int floor = PsyboidBits.minimumSpread(p.facts(), p.branches());
                int spread = (int) Math.ceil(Math.max(640, floor) * spreadFactor);
                lastSpread = spread;
                PsyboidBits.Config config = PsyboidBits.Config.of(p.warm(), spread, p.lap(),
                        p.alpha(), run, PsyboidBits.SETTLE);
                if (priced) config = config.priced(p.price());
                PsyboidBits.Plan plan = PsyboidBits.search(p.spawn(), p.map(), p.facts(),
                        p.branches(), config, seed);

                PsyboidOverride[] bits = plan.overrides();
                PsyboidOverride[] out = new PsyboidOverride[bits.length];
                long previous = Long.MIN_VALUE;
                for (int i = 0; i < bits.length; i++) {
                    // Taking the branch means the price route; declining means coasting, which a
                    // route equal to straightTo expresses without a second kind of override.
                    int[] route = bits[i].asks() ? p.price().exit() : p.facts().straightTo().clone();
                    long to = i + 1 < bits.length ? bits[i + 1].from() : Long.MAX_VALUE;
                    out[i] = EdgePilot.of(Sim.PSYBOID, route, p.map(), p.facts(),
                            p.built().labelling(), previous, to);
                    previous = to;
                }
                return out;
            }
        };
    }

    /** Every entrant on every scenario, as one table. */
    public static List<Row> run(List<Scenario> scenarios, List<Entrant> entrants, int seeds,
                                int ticks, CorpusPreset recipe) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (Scenario s : scenarios) {
            Prepared p = prepare(s, recipe);
            System.out.printf("%n=== %s: %d seeds x %,d ticks, warm %,d, lap %,d, alpha %.5f ===%n",
                    s.name(), seeds, ticks, p.warm(), p.lap(), p.alpha());
            System.out.printf("gain %.6f on %s; flock ceiling %.6f%n", p.price().gain(),
                    java.util.Arrays.toString(p.price().best().edges()),
                    p.price().gain() * s.boids());
            System.out.printf("%-16s %10s %10s %10s %10s %10s %10s %10s%n", "algorithm",
                    "occFlock", "occPsy", "occOthers", "perTick", "impactful", "occ/1000sp",
                    "s/1000t");
            for (Entrant e : entrants) {
                Row r = run(p, e, seeds, ticks);
                rows.add(r);
                System.out.printf("%-16s %10.6f %10.6f %10.6f %10.6f %10.1f %10.5f %10.4f%n",
                        r.algorithm(), r.occFlock(), r.occPsy(), r.occOthers(), r.perTick(),
                        r.impactful(), r.perSpend(), r.cost(seeds, ticks));
            }
        }
        return rows;
    }
}
