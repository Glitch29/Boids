package boids;

/**
 * A named recipe for generating a psyboid corpus.
 * <p>
 * {@link PresetScenarioParameter} names maps so that nothing downstream has to carry a filename
 * and a radius around; this does the same for corpora, and for the same reason. A corpus is a
 * function of half a dozen settings, and passing them separately means nothing records them
 * <em>together</em> — which is how corpora came to be under-labelled: the recipe lived in a
 * header comment, where it cannot stop a reader picking up the wrong file.
 * <p>
 * Naming the recipe puts it in the <b>address</b> instead. {@link Derived.Corpus} hashes the
 * preset alongside the behaviour it was flown under, so two recipes cannot collide and a
 * directory says what produced it.
 *
 * <h2>Seeds are always 0 to N-1</h2>
 * A corpus takes the first {@code seeds} seeds, so a preset is reproducible from its name alone
 * and a larger preset is a superset of a smaller one. Augmenting an existing sample, or drawing a
 * held-out set for a packet, is a different job and wants its own preset rather than an offset
 * hidden in a call.
 *
 * <h2>The warm-up is a policy, not a number</h2>
 * It has to be, because the right value is a property of the map — see {@link Warmup}. What the
 * recipe fixes is which question is being asked; the tier above supplies the map that answers it.
 */
public enum CorpusPreset {

    /**
     * Three seeds and a short run, for checking that the pipeline is intact.
     * <p>
     * Not a sample of anything — three seeds says nothing about a map. It exists so that a change
     * to the physics, the addressing or the search can be shown to still produce a corpus that
     * replays, in a minute rather than an hour.
     */
    SMOKE("smoke test, not a sample", 3, Warmup.TOTAL_EDGE_LENGTH, Spawn.Rule.TAU_UNIFORM,
            600),

    /**
     * The standard corpus: forty seeds over a three-thousand-tick run.
     * <p>
     * Forty seeds and a three-thousand-tick run. The search settings are no longer part of a
     * recipe: they belonged to a search that has been removed, and the one replacing it is not
     * yet specified.
     */
    PLANS_40("the standard corpus", 40, Warmup.TOTAL_EDGE_LENGTH, Spawn.Rule.TAU_UNIFORM,
            3000),

    /**
     * The recipe every figure recorded before 2026-09-06 was taken under: the simulation's own
     * spawn and a flat 5,000-tick warm-up.
     * <p>
     * <b>Kept so those figures stay reproducible, not because it is a good recipe.</b> The scoring
     * floor in `CORPUS.md`, the psyboid/others split and the fidelity counts are all measured on
     * this one, and a recipe nobody can re-run is a set of numbers nobody can check.
     */
    LEGACY_40("the pre-2026-09-06 recipe, kept for reproducibility", 40, Warmup.FIXED_5000,
            Spawn.Rule.UNIFORM, 3000);

    /**
     * How long a flock flies before the psyboid is switched on.
     * <p>
     * <b>A warm-up corrects the spawn, and cannot do more than that.</b> A boid advances one step
     * per tick along a route of fixed length, so its phase at tick {@code t} is its spawn phase
     * plus {@code t}: the flock's distribution over phase is carried rather than mixed, and a
     * warm-up, being a time shift, cannot flatten it. What a warm-up removes is the part of a
     * spawn that is not about phase — boids sitting where a settled flock never goes. Full
     * treatment in `CORPUS.md`.
     */
    public enum Warmup {

        /**
         * The time it takes to traverse every edge once. <b>The default.</b>
         * <p>
         * <b>This is "total edge length over speed".</b> The clock already measures an edge in
         * ticks rather than pixels — a length is a pixel distance divided by the speed — so the
         * sum of {@link SolverFacts#length} <em>is</em> that quantity and no further division
         * applies. Dividing the tick-lengths by the speed a second time would give 239 ticks on
         * dabeone, which is below the 500 the decay measurement requires; the reading used here
         * gives <b>940</b>, comfortably above it.
         * <p>
         * Chosen because it is map-generic and needs no per-map measurement: every boid has had
         * time to cross everything, whatever the map's shape. It is not the minimum — dabeone's
         * measured minimum is 500 — but the goal was never long-term equilibrium, only a
         * sufficiently obfuscated history, and a formula that transfers beats a constant that was
         * fitted once.
         */
        TOTAL_EDGE_LENGTH {
            @Override
            public int ticks(SolverFacts f) {
                double total = 0;
                for (double l : f.length()) total += l;
                return (int) Math.ceil(total);
            }
        },

        /**
         * A flat 5,000 ticks, as every corpus before 2026-09-06 used.
         * <p>
         * Chosen on a criterion that <b>never terminates</b>: how many seeds still score with no
         * psyboid keeps falling for as long as anyone measures it, so longer was always better and
         * 5,000 is where someone stopped. Kept only to reproduce what was flown under it.
         */
        FIXED_5000 {
            @Override
            public int ticks(SolverFacts f) { return 5000; }
        };

        /** How many ticks, on this map. */
        public abstract int ticks(SolverFacts f);
    }

    private final String describes;
    private final int seeds;
    private final Warmup warmup;
    private final Spawn.Rule spawn;
    private final int run;

    CorpusPreset(String describes, int seeds, Warmup warmup, Spawn.Rule spawn, int run) {
        this.describes = describes;
        this.seeds = seeds;
        this.warmup = warmup;
        this.spawn = spawn;
        this.run = run;
    }

    public String describes() { return describes; }

    /** Seeds {@code 0 .. seeds-1}. */
    public int seeds() { return seeds; }

    public Warmup warmup() { return warmup; }

    public Spawn.Rule spawn() { return spawn; }

    public int run() { return run; }

    /** How many ticks of warm-up this recipe asks for on this map. */
    public int warm(SolverFacts f) { return warmup.ticks(f); }

    /**
     * Every field, in a form a hash and a {@code meta.txt} can both use.
     * <p>
     * <b>The warm-up appears as its policy, not its value.</b> The value is a function of the map,
     * and the map is already in the address one tier up, so hashing the resolved number would put
     * the same information in twice and make the same recipe look like two.
     */
    public String fingerprint() {
        return String.format("seeds=%d warmup=%s spawn=%s run=%d",
                seeds, warmup, spawn, run);
    }
}
