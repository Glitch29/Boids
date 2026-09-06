package boids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A body of psyboid activity to test a solver against, and the file that makes it repeatable.
 * <p>
 * A solver is only as trustworthy as the corpus it was graded on, and the corpus this project
 * had was not one: {@code data/searches.tsv} covers blossom, daisy, plinko and hamburger and
 * has no dab-like map in it at all. Grading on overrides invented inside the grading harness is
 * how a measurement ends up describing the harness — a single held turn does nothing to edge
 * routing seven times in eight, and resampling one that did every thirteen ticks turns fourteen
 * events into a hundred and thirty scenes with the sample size hidden.
 * <p>
 * So this is separate from anything that grades. It runs {@link PsyboidBits} per seed, keeps
 * the plan, and writes a row that names it. <b>The label is the artifact.</b> Everything else in
 * a row can be recomputed from it; nothing in a row substitutes for it.
 * <p>
 * <b>Every row is verified by replay before it is written.</b> A plan is flown a second time
 * from its own label and the resulting arrangement compared to the one the search ended on,
 * position by position. A row that does not reproduce is a row that would silently corrupt
 * every later measurement, and the whole point of writing labels down is that they are exact.
 */
public final class PsyboidCorpus {
    private PsyboidCorpus() {}

    private static final String FILE = "plans.tsv";

    /**
     * The columns, label last so a row stays readable as the metrics grow.
     * <p>
     * <b>Occupancy rather than raw score.</b> One score point is one boid in a scoring zone for
     * one tick, so dividing by the flock size gives the fraction of the flock scoring at any
     * moment — a number that means the same thing across flock sizes and run lengths, which raw
     * score does not. The psyboid and non-psyboid shares are separated because a psyboid that
     * scores by parking itself in a zone and one that scores by moving the flock look identical
     * otherwise, and only the second is what the project is about.
     * <p>
     * <b>{@code impactful} is what a psyboid spends.</b> Not the override count: an override the
     * map refuses, or one the flock would have obeyed anyway, costs nothing and changes nothing.
     * This counts ticks where the psyboid's turn <em>after the veto</em> differed from what the
     * flocking rules alone would have produced, which is the quantity a psyboid algorithm should
     * be judged against occupancy on.
     */
    private static final String HEADER = "seed\tpsyboid\twarm\tspread\tlookahead\talpha"
            + "\trun\tusableFrom\tusableTo\tusable\tperTick\tcontrol"
            + "\toccFlock\toccPsy\toccOthers\toccControl\timpactful"
            + "\tbits\tturns\tlabel";

    /**
     * @param plans   one per seed, in seed order
     * @param scoring how many plans scored at all. A psyboid that never reaches a scoring
     *                region left no evidence of itself and is a case nobody can solve
     * @param lifted  how many beat the same seed flown with no psyboid
     */
    public record Corpus(List<PsyboidBits.Plan> plans, int scoring, int lifted, Path file) {

        public double meanPerTick() {
            double total = 0;
            for (PsyboidBits.Plan p : plans) total += p.perTick();
            return plans.isEmpty() ? Double.NaN : total / plans.size();
        }

        public double meanControl() {
            double total = 0;
            for (PsyboidBits.Plan p : plans) total += p.control();
            return plans.isEmpty() ? Double.NaN : total / plans.size();
        }
    }

    /**
     * Searches every seed and writes the corpus into the map's own ingest.
     *
     * @param measure ticks past the warmup the plan is scored over, and the window a scene
     *                should be sampled from
     */
    public static Corpus build(PresetScenarioParameter preset, SolverFacts f,
                               CorpusPreset recipe) throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        PsyboidBits.Branches b = PsyboidBits.branches(map, f);
        PsyboidBits.Config config = recipe.config();
        int seeds = recipe.seeds();
        Derived.Corpus where = SimTest.behaviour(preset, f, SimTest.flockingOf(preset))
                .corpus(recipe);

        System.out.printf("%n=== %s @%s: psyboid corpus %s (%s), %d seeds ===%n%s%n%s%n",
                preset.name(), preset.ingest().hash(), recipe.name(), recipe.describes(), seeds,
                recipe.fingerprint(), where);
        for (int i = 0; i < b.branch().length; i++) {
            System.out.printf("  branch: edge %d -> %d, right held %d ticks%n", b.branch()[i],
                    b.exit()[i], b.hold()[i]);
        }

        long began = System.nanoTime();
        List<PsyboidBits.Plan> plans = new ArrayList<>();
        int scoring = 0, lifted = 0, unverified = 0;
        for (long seed = 0; seed < seeds; seed++) {
            PsyboidBits.Plan plan = PsyboidBits.search(preset, map, f, b, config, seed);
            if (!verify(preset, plan, config)) {
                unverified++;
                continue;
            }
            plans.add(plan);
            if (plan.perTick() > 0) scoring++;
            if (plan.perTick() > plan.control()) lifted++;
        }
        if (unverified > 0) {
            throw new IllegalStateException(unverified + " plans did not reproduce from their "
                    + "own labels; the corpus format is not exact and nothing built on it "
                    + "would be either");
        }

        Path file = where.at().resolve(FILE);
        write(file, preset, plans, config, recipe);
        System.out.printf("%,d plans in %.0fs; %,d score, %,d beat their own control%n",
                plans.size(), (System.nanoTime() - began) / 1e9, scoring, lifted);
        System.out.printf("mean %.5f per tick against %.5f unsteered%n",
                mean(plans, true), mean(plans, false));
        occupancy(plans);

        int asked = 0, took = 0, unbid = 0, exactPlans = 0, reported = 0;
        long usable = 0;
        for (PsyboidBits.Plan p : plans) {
            Fidelity fit = fidelity(preset, f, map, p);
            asked += fit.asked();
            took += fit.took();
            unbid += fit.unbid();
            reported += fit.reported();
            if (fit.exact()) exactPlans++;
            usable += p.usable();
        }
        System.out.printf("usable window: %,d ticks per plan on average%n", usable / Math.max(1, plans.size()));
        System.out.printf("fidelity: %,d turns asked, %,d crossed (%.1f%%), %,d crossings "
                + "nobody asked for; %,d of %,d plans match branch for branch%n", asked, took,
                100.0 * took / Math.max(1, asked), unbid, exactPlans, plans.size());
        System.out.printf("%,d crossings in the edge path; the audit comparison is not being "
                + "run -- see fidelity()%n", took + unbid);
        System.out.printf("wrote %s%n", file);
        return new Corpus(plans, scoring, lifted, file);
    }

    /** How many bits in a plan asked for the turn rather than declining it. */
    private static int turns(PsyboidBits.Plan plan) {
        int n = 0;
        for (PsyboidOverride o : plan.overrides()) if (o.duration() > 0) n++;
        return n;
    }

    /**
     * @param asked    overrides that asked the psyboid to take a branch
     * @param took     of those, ones where its edge path actually crossed that branch
     * @param unbid    branch crossings with no override asking for one, which would mean the
     *                 flock turned the psyboid off its route on its own
     * @param reported how many of the crossings {@link ExitAudit} raised as route changes
     */
    public record Fidelity(int asked, int took, int unbid, int reported) {

        public double rate() { return asked == 0 ? Double.NaN : took / (double) asked; }

        /** Whether the plan and the flight say exactly the same thing. */
        public boolean exact() { return took == asked && unbid == 0; }
    }

    /**
     * Whether the psyboid's flight matches the plan, branch for branch.
     * <p>
     * The truth is the psyboid's <b>edge path</b>: a crossing counts when it leaves an edge for
     * somewhere unsteered travel would not have taken it. That is the same test the classifier
     * applies, read off the route rather than off a decision.
     * <p>
     * <b>{@link ExitAudit} is reported alongside but must not be the test, and the gap between
     * them is a finding rather than a nuisance.</b> The audit fires on the tick a boid's request
     * lands somewhere its straight step would not, and skips the crossing itself as
     * follow-through once the question is already settled. That is exactly right for finding the
     * one decisive moment a leader created — and exactly wrong here, because an override held for
     * thirty ticks turns the boid early and gradually, so it arrives at the boundary already
     * committed and there is no such moment. On dabeone the audit sees eight of thirteen genuine
     * branch crossings by a planned psyboid. <b>A psyboid steering itself round a corner is
     * substantially invisible to the classifier</b>, which is worth knowing well beyond this
     * method.
     * <p>
     * An override with no crossing is a turn the boid failed to make. A crossing with no override
     * is the flock steering the psyboid, which is legal physics but means the plan has stopped
     * describing the flight.
     */
    public static Fidelity fidelity(PresetScenarioParameter preset, SolverFacts f, NavMap map,
                                    PsyboidBits.Plan plan) throws IOException {
                Boids2DEngine engine = new Boids2DEngine(preset);
        Sim.State s = engine.init(plan.seed());
        for (int t = 0; t < PsyboidBits.WARM; t++) s = engine.tick(s);
        s = SimTest.withOverrides(s, plan.overrides());

        int who = plan.psyboid();
        List<Long> crossings = new ArrayList<>();
        int was = f.edgeAt(s.x[who], s.y[who], s.h[who]);
                while (s.tick < plan.usableTo()) {
            s = engine.tick(s);
            int now = f.edgeAt(s.x[who], s.y[who], s.h[who]);
            if (now != was) {
                if (was >= 0 && now >= 0 && f.straightTo()[was] != now) crossings.add(s.tick);
                was = now;
            }
        }
                int asked = 0, took = 0;
        boolean[] claimed = new boolean[crossings.size()];
        for (PsyboidOverride o : plan.overrides()) {
            if (o.duration() <= 0) continue;
            asked++;
            for (int i = 0; i < crossings.size(); i++) {
                // A crossing belongs to an override when it lands inside the window that
                // override was holding the turn for, plus the ticks the turn itself takes.
                if (claimed[i] || crossings.get(i) < o.onset()
                        || crossings.get(i) > o.onset() + o.duration()) {
                    continue;
                }
                claimed[i] = true;
                took++;
                break;
            }
        }
        int unbid = 0;
        for (boolean c : claimed) if (!c) unbid++;
        return new Fidelity(asked, took, unbid, -1);
    }

    private static double mean(List<PsyboidBits.Plan> plans, boolean psyboid) {
        double total = 0;
        for (PsyboidBits.Plan p : plans) total += psyboid ? p.perTick() : p.control();
        return plans.isEmpty() ? Double.NaN : total / plans.size();
    }

    /**
     * Flies the plan again from its label and checks it lands in the same place.
     * <p>
     * Position and heading of every boid, at the end of the measured window. Anything less
     * would pass a label that reproduces the score by coincidence.
     */
    private static boolean verify(PresetScenarioParameter preset, PsyboidBits.Plan plan,
                                  PsyboidBits.Config config) throws IOException {
        long at = plan.usableTo();
        Sim.State replayed = PsyboidBits.replay(preset, plan.label(), config.warm(), at);
        Boids2DEngine engine = new Boids2DEngine(preset);
        Sim.State direct = engine.init(plan.seed());
        for (int t = 0; t < config.warm(); t++) direct = engine.tick(direct);
        direct = SimTest.withOverrides(direct, plan.overrides());
        while (direct.tick < at) direct = engine.tick(direct);
        for (int i = 0; i < direct.n; i++) {
            if (replayed.x[i] != direct.x[i] || replayed.y[i] != direct.y[i]
                    || replayed.h[i] != direct.h[i]) {
                return false;
            }
        }
        return replayed.score == direct.score;
    }

    /** Reads a corpus back. Labels only; everything else is recomputed by whoever wants it. */
    /**
     * The plan labels of one corpus.
     * <p>
     * Takes the tier rather than the ingest. A corpus is a set of flown plans, so it is a
     * function of the decision rules as much as of the map — and every row was verified by
     * replay when it was <em>written</em> and never when it is read, so a corpus reached under
     * physics it was not flown under would be believed.
     */
    /**
     * The plan labels of the one corpus under these rules, or an error naming the choices.
     * <p>
     * A reader that does not care which recipe it gets is usually a reader that has only ever
     * seen one. Rather than pick — which is how the old fixed path came to hand back whatever had
     * been written last — this insists there is exactly one and says what the alternatives are
     * when there is not. Naming a {@link CorpusPreset} is then the fix, and it is a fix the
     * caller has to make deliberately.
     */
    public static List<String> labels(Derived.Behaviour where) throws IOException {
        Path root = where.at("psyboid");
        List<Path> found = new ArrayList<>();
        try (java.util.stream.Stream<Path> kids = Files.list(root)) {
            kids.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve(FILE)))
                    .forEach(found::add);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no psyboid corpus under " + root
                    + " — run PsyboidCorpus.build for this configuration first");
        }
        if (found.size() > 1) {
            StringBuilder s = new StringBuilder("several corpora under " + root
                    + "; name a CorpusPreset rather than letting this guess:");
            for (Path p : found) s.append(System.lineSeparator()).append("    ")
                    .append(p.getFileName());
            throw new IllegalStateException(s.toString());
        }
        return read(found.get(0).resolve(FILE));
    }

    /** The plan labels of one named corpus. */
    public static List<String> labels(Derived.Corpus where) throws IOException {
        return read(where.at().resolve(FILE));
    }

    private static List<String> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no psyboid corpus at " + file
                    + " — run PsyboidCorpus.build for this configuration first");
        }
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("seed\t")) continue;
            String[] cells = line.split("\t");
            out.add(cells[cells.length - 1]);
        }
        return out;
    }

    /**
     * The four occupancy rates, and the one comparison that says what the psyboid is doing.
     * <p>
     * <b>A psyboid can raise the flock's score two ways</b>, and only one of them is what this
     * project is about: it can herd the others into a scoring region, or it can fly into one
     * itself. Total score cannot tell those apart. Splitting the psyboid's own occupancy from
     * everyone else's can, and a corpus where {@code others} sits near zero is a corpus of plans
     * that score by parking — which is a fact about what the search optimises, not about the map.
     */
    private static void occupancy(List<PsyboidBits.Plan> plans) {
        if (plans.isEmpty()) return;
        double flock = 0, psy = 0, others = 0, control = 0, impact = 0;
        int parked = 0;
        for (PsyboidBits.Plan p : plans) {
            flock += p.steered().occupancy();
            psy += p.steered().psyOccupancy();
            others += p.steered().othersOccupancy();
            control += p.unsteered().occupancy();
            impact += p.steered().impactful();
            if (p.steered().othersOccupancy() <= 0) parked++;
        }
        int n = plans.size();
        System.out.printf("occupancy: flock %.4f, psyboid %.4f, others %.4f, control %.4f%n",
                flock / n, psy / n, others / n, control / n);
        System.out.printf("  %.1f impactful ticks per plan; %d of %d plans move nobody at all%n",
                impact / n, parked, n);
        if (parked > 0) {
            System.out.printf("  ** %d plan(s) score only through the psyboid itself. Total score "
                    + "cannot see this; the split can. **%n", parked);
        }
    }

    private static void write(Path file, PresetScenarioParameter preset,
                              List<PsyboidBits.Plan> plans, PsyboidBits.Config config,
                              CorpusPreset recipe) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("# psyboid plans for ").append(preset.name()).append(" @")
                .append(preset.ingest().hash()).append(", ")
                .append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(System.lineSeparator());
        s.append("# The label is the artifact: seed, then one override per decision, in the")
                .append(System.lineSeparator());
        s.append("# format PsyboidOverride.parse reads. A zero-duration override is a decision")
                .append(System.lineSeparator());
        s.append("# recorded as not taken, so a plan says what was chosen and what was not.")
                .append(System.lineSeparator());
        s.append("# Every row below was replayed from its own label and matched position for")
                .append(System.lineSeparator());
        s.append("# position before being written.").append(System.lineSeparator());
        s.append(HEADER).append(System.lineSeparator());
        for (PsyboidBits.Plan p : plans) {
            s.append(p.seed()).append('\t').append(p.psyboid()).append('\t')
                    .append(config.warm()).append('\t').append(config.spread()).append('\t')
                    .append(config.lookahead()).append('\t').append(config.alpha()).append('\t')
                    .append(config.run()).append('\t')
                    .append(p.usableFrom()).append('\t').append(p.usableTo()).append('\t')
                    .append(p.usable()).append('\t')
                    .append(String.format("%.6f", p.perTick())).append('\t')
                    .append(String.format("%.6f", p.control())).append('\t')
                    .append(String.format("%.6f", p.steered().occupancy())).append('\t')
                    .append(String.format("%.6f", p.steered().psyOccupancy())).append('\t')
                    .append(String.format("%.6f", p.steered().othersOccupancy())).append('\t')
                    .append(String.format("%.6f", p.unsteered().occupancy())).append('\t')
                    .append(p.steered().impactful()).append('\t')
                    .append(p.overrides().length).append('\t').append(turns(p)).append('\t')
                    .append(p.label()).append(System.lineSeparator());
        }
        Files.writeString(file, s.toString());
    }
}
