package boids;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Names the psyboid from one arrangement, or says who it could still be.
 * <p>
 * The evaluation shows a solver photographs, not a simulation. Reconstructing positions from
 * pixels is a separate problem and is assumed solved here: what arrives is a {@link Sim.State}
 * and nothing else — no tick number, no history, no knowledge of which boid is which between
 * frames. Everything the answer is built from beyond that comes out of {@link SolverFacts},
 * which was derived from the map long before any scene existed.
 * <p>
 * The work is done by {@link Clue}s. Each looks at the arrangement for one kind of evidence
 * and returns, per boid, how much more likely that boid became — and the solver multiplies
 * them. Today there is one clue and it is exact, so the multiplication is an intersection of
 * survivors and the answer is a list rather than a ranking. That is deliberately not baked in:
 * as soon as drift is modelled the same clue starts returning leanings instead of vetoes, and
 * the only thing that has to change is this class's last three lines.
 * <p>
 * <b>What a solver may not do.</b> It reads facts and it reads the scene. It does not build a
 * navmap, decompose a map, solve a clock or run a leader search — all of that costs minutes
 * and belongs to {@link SolverStore#build}, which a human runs once per map. A solver that
 * quietly did map-level work would also be answering a question the evaluation is not asking.
 */
public final class Solver {

    /** The search ran further back than any dab-like map should need. */
    public static final class TooDeep extends RuntimeException {
        public TooDeep(String message) { super(message); }
    }

    private final SolverFacts facts;
    private final List<Clue> clues;

    public Solver(SolverFacts facts, Clue... clues) {
        this.facts = facts;
        this.clues = List.of(clues);
        if (this.clues.isEmpty()) {
            throw new IllegalArgumentException("a solver with no clues would call every boid "
                    + "a candidate, which is true and useless");
        }
    }

    /**
     * The solver for a configuration whose facts have already been built.
     * <p>
     * A configuration, not a map: facts depend on the gate, the weighting scheme and the
     * flocking constants too, so naming only the map would let one map answer with another
     * configuration's model of it.
     *
     * @throws IllegalStateException if they have not — see {@link #prepare}
     */
    public static Solver of(Derived.Behaviour where) {
        return new Solver(SolverStore.load(where), new UnstableEdgeClue());
    }

    /**
     * Builds whatever this map is missing, then returns a solver for it.
     * <p>
     * Minutes of work on a first call and a file read afterwards. Meant to be invoked by hand,
     * once per map version, by someone who has chosen the gate and looked at what it produced;
     * it is not a fallback for {@link #of} and nothing on the answering path calls it.
     */
    public static Solver prepare(PresetScenarioParameter preset, SolverFacts.Gate gate,
                                 EdgeWeights.Scheme scheme, double[][] chain, Flocking flock)
            throws IOException {
        return new Solver(SolverStore.prepare(preset, gate, scheme, chain, flock),
                new UnstableEdgeClue());
    }

    public SolverFacts facts() { return facts; }

    /**
     * Every boid the arrangement leaves standing as the psyboid.
     * <p>
     * One name means the scene determines the answer. Several means it does not determine it
     * yet — which is a real outcome and not a failure, since a scenario is allowed to be
     * ambiguous and saying so is better than picking.
     *
     * An empty list is a real answer rather than a failure. It says no one boid accounts for
     * everything the arrangement demands — which, on a scene with two boids off the stable
     * cycle and no window to explain either, is exactly the truth.
     */
    public int[] candidates(Sim.State state) {
        int[] odds = odds(state);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < state.n; i++) if (odds[i] > 0) out.add(i);
        int[] ids = new int[out.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = out.get(i);
        return ids;
    }

    /** The combined weights, before they are read as a shortlist. */
    public int[] odds(Sim.State state) {
        int[] odds = new int[state.n];
        Arrays.fill(odds, 1);
        for (Clue clue : clues) {
            int[] each = clue.odds(facts, state);
            if (each.length < state.n) {
                throw new IllegalStateException("clue " + clue.name() + " returned "
                        + each.length + " odds for " + state.n + " boids");
            }
            for (int i = 0; i < state.n; i++) odds[i] *= each[i];
        }
        return odds;
    }

    /** Each clue's own verdict, for seeing which one did the excluding. */
    public String report(Sim.State state) {
        StringBuilder s = new StringBuilder();
        s.append(String.format("%-16s", "boid"));
        for (int i = 0; i < state.n; i++) s.append(String.format("%8d", i));
        s.append(System.lineSeparator());
        s.append(String.format("%-16s", "edge"));
        for (int i = 0; i < state.n; i++) {
            int e = facts.edgeAt(state.x[i], state.y[i], state.h[i]);
            s.append(String.format("%8s", e < 0 ? "-" : e + (facts.stable(e) ? "" : "*")));
        }
        s.append(System.lineSeparator());
        for (Clue clue : clues) {
            int[] each = clue.odds(facts, state);
            s.append(String.format("%-16s", clue.name()));
            for (int i = 0; i < state.n; i++) s.append(String.format("%8d", each[i]));
            s.append(System.lineSeparator());
        }
        s.append("(* marks an edge unsteered travel does not keep a boid on)");
        return s.toString();
    }
}
