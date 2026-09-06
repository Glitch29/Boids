package boids;

import java.io.IOException;

/**
 * A map and a gate in, a psyboid corpus out, every tier built on the way.
 * <p>
 * <b>What this replaces.</b> `PIPELINE.md` describes seventeen steps invoked by hand from a
 * driver, each taking the outputs of the last as loose arguments. That is fine for asking one
 * question of one map and hopeless for bringing a second map up to the first one's level, which
 * is what this exists for: everything below is derivable from the map, so nothing about it should
 * need remembering.
 * <p>
 * <b>The gate is the one thing that is not derivable</b>, and it is a bootstrap rather than an
 * analysis input — a line across a corridor used to cut cycles so a first decomposition can be
 * constructed. It must be a real cut, it does not fail loudly when it is not, and choosing one
 * is a human judgement. See `EDGES.md` §2. It is the only argument here that is not either the
 * map or a named recipe.
 *
 * <h2>What is still missing before this runs unattended</h2>
 * <ol>
 *   <li><b>The gate.</b> As above. A gate-finder is possible in principle — a cut that every
 *       cycle crosses is a graph property — and does not exist.</li>
 *   <li><b>A generic psyboid algorithm.</b> {@link PsyboidBits} searches one bit per visit to a
 *       branching edge, and reads a branch as <em>an arc that holding right reaches</em>. On a
 *       map whose branch needs a left hold it finds nothing, and reports no branches rather than
 *       failing — see {@link #report}, which says so out loud.</li>
 * </ol>
 * Everything else — ingest, navmap, decomposition, clock, per-edge navigation, solver facts,
 * stable+, warm-up, spawn, corpus — is derived here without a further decision.
 */
public final class Pipeline {
    private Pipeline() {}

    /**
     * Everything derived from one map under one gate, in the order it has to be derived.
     *
     * @param facts what a solver may know: the decomposition, the clock and per-edge navigation
     * @param plus  stable+, the states ordinary multi-boid traffic reaches. Needed by the spawn
     * @param warm  what the recipe's warm-up policy comes to on this map
     */
    public record Built(PresetScenarioParameter preset, SolverFacts.Gate gate, SolverFacts facts,
                        Labelling labelling, StateSet plus, PsyboidBits.Branches branches,
                        int warm, Derived.Behaviour where) {

        /** Total edge length in ticks: the time to traverse every edge once. */
        public double totalEdgeLength() {
            double total = 0;
            for (double l : facts.length()) total += l;
            return total;
        }

        /** Whether a psyboid can be searched at all on this map. */
        public boolean steerable() { return branches.branch().length > 0; }
    }

    /** The decomposition, kept together because everything downstream wants all four parts. */
    public record Labelling(NavMap map, int[] live, int liveCount, int[] edge, int edges) {}

    /**
     * Ingests the map if it is not already frozen, then derives every tier above it.
     * <p>
     * Idempotent and cheap on a second call: the ingest is content-addressed, the clock and the
     * facts are cached under their own input closures, and only stable+ is recomputed — it is
     * seconds, and caching it would need a store of its own for no benefit yet.
     */
    public static Built build(PresetScenarioParameter preset, SolverFacts.Gate gate,
                              CorpusPreset recipe) throws IOException {
        Flocking flock = Flocking.of(preset.turningRadius());
        System.out.printf("%n=== %s: pipeline from the map ===%n", preset.name());
        System.out.printf("source %s, turning radius %.0f, flock %d%n", preset.sourceName(),
                preset.turningRadius(), preset.flockSize());
        System.out.printf("ingest    %s%n", preset.ingest().dir());

        SolverFacts facts = SolverStore.prepare(preset, gate, SimTest.SCHEME, SimTest.CHAIN,
                flock);
        Derived.Structure structure = Derived.structure(preset.ingest(), preset.turningRadius(),
                gate, SimTest.SCHEME, SimTest.CHAIN);
        Derived.Behaviour where = structure.behaviour(flock, Aggregation.SIMULATION);
        System.out.printf("structure %s%nbehaviour %s%n", structure.dir(), where.dir());

        SimTest.Labelling lab = SimTest.labelFor(preset, gate.horizontal(), gate.line(),
                gate.lo(), gate.hi(), gate.dir());
        Labelling labelling = new Labelling(lab.map(), lab.live(), lab.liveCount(), lab.edge(),
                lab.edges());

        MapStates states = MapStates.of(lab.map(), flock, lab.live(), lab.liveCount());
        StateSet plus = states.stablePlus(SimTest.QUORUM);
        PsyboidBits.Branches branches = PsyboidBits.branches(lab.map(), facts);
        int warm = recipe.warm(facts);
        return new Built(preset, gate, facts, labelling, plus, branches, warm, where);
    }

    /**
     * What the map turned out to be, and whether the rest of the pipeline can run on it.
     * <p>
     * <b>Prints the blockers rather than throwing at them.</b> A map that cannot be steered is
     * still worth having a decomposition and a clock for, and the useful output of bringing a new
     * map up is a list of what it lacks.
     */
    public static void report(Built b, CorpusPreset recipe) {
        SolverFacts f = b.facts();
        System.out.printf("%n-- %s @%s --%n", b.preset().name(), b.preset().ingest().hash());
        System.out.printf("%d edges, gate %s%n", f.edges(), b.gate());
        System.out.printf("%-5s %9s %11s %8s %8s%n", "edge", "length", "straightTo", "stable",
                "scoring");
        for (int e = 0; e < f.edges(); e++) {
            System.out.printf("%-5d %9.2f %11d %8b %8b%n", e, f.length()[e], f.straightTo()[e],
                    f.stable(e), f.scoring(e));
        }
        System.out.printf("total edge length %.2f ticks; warm-up policy %s gives %,d%n",
                b.totalEdgeLength(), recipe.warmup(), b.warm());
        System.out.printf("stable+ %,d states: %s%n", b.plus().size(),
                MapStates.byEdge(b.plus(), b.labelling().edge(), f.edges()));

        System.out.printf("%nsteered arcs (an arc unsteered travel does not take):%n");
        int arcs = 0;
        for (int a = 0; a < f.edges(); a++) {
            for (int c = 0; c < f.edges(); c++) {
                if (a == c || f.straightTo()[a] == c) continue;
                if ((f.arcs()[a] & (1L << c)) == 0) continue;
                arcs++;
                boolean found = false;
                for (int i = 0; i < b.branches().branch().length; i++) {
                    if (b.branches().branch()[i] == a && b.branches().exit()[i] == c) found = true;
                }
                if (found) {
                    System.out.printf("  %d -> %d  searchable, hold right %d ticks%n", a, c,
                            holdOf(b.branches(), a, c));
                    continue;
                }
                PsyboidBits.Reach r = PsyboidBits.reaches(b.labelling().map(), f, a, c);
                System.out.printf("  %d -> %d  ** NOT SEARCHABLE — %s **%n", a, c, r.reached()
                        ? "a LEFT hold of " + r.ticks() + " ticks reaches it, and PsyboidBits "
                                + "only tries the right"
                        : "no single held turn reaches it from any critical state, so it is not "
                                + "a decision the psyboid has");
            }
        }
        System.out.printf("%d steered arc(s), %d searchable%n", arcs,
                b.branches().branch().length);
        if (!b.steerable()) {
            System.out.printf("** no branch is searchable, so no psyboid can be planned here **%n");
        }
    }

    private static int holdOf(PsyboidBits.Branches b, int from, int to) {
        for (int i = 0; i < b.branch().length; i++) {
            if (b.branch()[i] == from && b.exit()[i] == to) return b.hold()[i];
        }
        return -1;
    }

    /**
     * The whole thing: a map, a gate and a named recipe in, a verified corpus out.
     * <p>
     * Refuses rather than writing an empty corpus when the map has no searchable branch, because
     * a corpus of plans that steer nothing is indistinguishable from a corpus of controls and
     * would grade a solver against nothing at all.
     */
    public static PsyboidCorpus.Corpus corpus(PresetScenarioParameter preset,
                                              SolverFacts.Gate gate, CorpusPreset recipe)
            throws IOException {
        Built b = build(preset, gate, recipe);
        report(b, recipe);
        if (!b.steerable()) {
            throw new IllegalStateException(preset.name() + " has no searchable branch, so there "
                    + "is no psyboid to plan. See the report above: the map has steered arcs but "
                    + "PsyboidBits reaches none of them by holding right.");
        }
        return PsyboidCorpus.build(preset, b.facts(), b.plus(), recipe);
    }
}
