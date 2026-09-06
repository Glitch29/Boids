package boids;

/**
 * A named recipe for generating a psyboid corpus.
 * <p>
 * {@link PresetScenarioParameter} names maps so that nothing downstream has to carry a filename
 * and a radius around; this does the same for corpora, and for the same reason. A corpus is a
 * function of half a dozen numbers, and passing them separately means nothing records them
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
 */
public enum CorpusPreset {

    /**
     * Three seeds and a short run, for checking that the pipeline is intact.
     * <p>
     * Not a sample of anything — three seeds says nothing about a map. It exists so that a change
     * to the physics, the addressing or the search can be shown to still produce a corpus that
     * replays, in a minute rather than an hour.
     */
    SMOKE("smoke test, not a sample", 3, PsyboidBits.WARM, 600, 640, 320, 0.95,
            PsyboidBits.SETTLE),

    /**
     * The standard corpus: forty seeds over a three-thousand-tick run.
     * <p>
     * The shape the project's figures were taken at through physics 2. Spread and lookahead are
     * {@link PsyboidBits#standard}'s, which are measured rather than chosen — see its javadoc for
     * why 640 is a floor and not a knob.
     */
    PLANS_40("the standard corpus", 40, PsyboidBits.WARM, 3000, 640, 320, 0.95,
            PsyboidBits.SETTLE);

    private final String describes;
    private final int seeds;
    private final int warm;
    private final int run;
    private final int spread;
    private final int lookahead;
    private final double alpha;
    private final int settle;

    CorpusPreset(String describes, int seeds, int warm, int run, int spread, int lookahead,
                 double alpha, int settle) {
        this.describes = describes;
        this.seeds = seeds;
        this.warm = warm;
        this.run = run;
        this.spread = spread;
        this.lookahead = lookahead;
        this.alpha = alpha;
        this.settle = settle;
    }

    public String describes() { return describes; }

    /** Seeds {@code 0 .. seeds-1}. */
    public int seeds() { return seeds; }

    public int warm() { return warm; }

    public int run() { return run; }

    /** The search settings, as {@link PsyboidBits} wants them. */
    public PsyboidBits.Config config() {
        return PsyboidBits.Config.of(warm, spread, lookahead, alpha, run, settle);
    }

    /** Every field, in a form a hash and a {@code meta.txt} can both use. */
    public String fingerprint() {
        return String.format("seeds=%d warm=%d run=%d spread=%d lookahead=%d alpha=%s settle=%d",
                seeds, warm, run, spread, lookahead, alpha, settle);
    }
}
