package boids;

import java.util.Arrays;
import java.util.Random;

/**
 * Measures candidate {@link Aggregation}s against the one the simulation uses.
 * <p>
 * A survey, not an optimisation. Every variant is scored on the same sampled arrangements and the
 * numbers are reported side by side; nothing here searches for a winner, and the metrics are
 * chosen so that a variant cannot look good on one without the cost showing up on another.
 *
 * <h2>Arrangements are drawn from the map, not from the plane</h2>
 * A boid state is a live navmap state and its neighbours are live states that it can actually
 * perceive. Sampling positions freely would spend most of its trials on geometry the map makes
 * impossible, and the question is about the flock this map produces.
 *
 * <h2>The metrics</h2>
 * <ul>
 *   <li><b>Pseudo-triangle</b> — how often the two-neighbour signal lands between the two
 *       one-neighbour signals, on the across-heading component that decides the turn.</li>
 *   <li><b>Amplification</b> — {@code |signal(A,B)| / max(|signal(A)|, |signal(B)|)}. Above one
 *       means the pair asked for something more decisive than either did alone.</li>
 *   <li><b>Chaos</b> — how often moving one neighbour a single pixel changes the turn.</li>
 *   <li><b>Agreement</b> — how often the turn matches what the simulation does today.</li>
 * </ul>
 */
public final class AggregationSurvey {
    private AggregationSurvey() {}

    /** A reservoir of samples, for statistics a mean would misreport. */
    static final class Spread {
        private final double[] kept;
        private int at;

        Spread(int capacity) { kept = new double[capacity]; }

        void add(double v) { if (at < kept.length && Double.isFinite(v)) kept[at++] = v; }

        int count() { return at; }

        /**
         * A quantile rather than a mean, because several of these are ratios with a near-zero
         * denominator and a single arrangement where two neighbours cancel to machine precision
         * would otherwise be the whole statistic.
         */
        double at(double p) {
            if (at == 0) return 0;
            double[] a = Arrays.copyOf(kept, at);
            Arrays.sort(a);
            return a[Math.min(a.length - 1, (int) (p * a.length))];
        }

        double above(double threshold) {
            int n = 0;
            for (int i = 0; i < at; i++) if (kept[i] > threshold) n++;
            return at == 0 ? 0 : 100.0 * n / at;
        }
    }

    /** Running totals for one variant. */
    private static final class Score {
        long pairs, triangleOk, triangleOkHalf, matched, flipped, flipTrials;
        long triples, tripleMatched;
        final Spread amps, jolt, reach;

        Score(int capacity) {
            amps = new Spread(capacity);
            jolt = new Spread(capacity);
            reach = new Spread(capacity);
        }
    }

    /** The across-heading component of a desired direction, positive to the boid's right. */
    public static double across(double vx, double vy, int heading) {
        int right = Math.floorMod(heading + Params.TURNS / 4, Params.TURNS);
        return vx * Params.COS[right] + vy * Params.SIN[right];
    }

    /** The turn a boid facing {@code heading} picks, given a desired direction. */
    public static int turn(double dirX, double dirY, int heading, Flocking f) {
        if (dirX == 0 && dirY == 0) return 0;
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int delta : new int[]{0, -1, +1}) {
            int a = Math.floorMod(heading + delta, Params.TURNS);
            double score = Params.COS[a] * dirX + Params.SIN[a] * dirY
                    + (delta == 0 ? f.straightBias() : 0.0);
            if (score > bestScore) { bestScore = score; best = delta; }
        }
        return best;
    }

    /**
     * Fills in the geometry for one deciding boid and a chosen set of neighbours.
     *
     * @return the neighbour count actually perceived, which may be fewer than offered
     */
    public static Aggregation.Neighbours see(NavMap map, MovementLogic rules, int self,
                                             int[] others, int count,
                                             Aggregation.Neighbours into) {
        int turns = Params.TURNS, w = map.width();
        int hd = self % turns, cell = self / turns, x0 = cell % w, y0 = cell / w;
        int n = 0;
        for (int k = 0; k < count; k++) {
            int s = others[k];
            int c = s / turns, x = c % w, y = c / w;
            // The real perception test, not a copy of it. A survey that decided for itself which
            // neighbours were visible would be comparing aggregations over a different flock than
            // the one the simulation flies, and the difference would look like a result.
            double d = rules.perceived(x0, y0, hd, x, y);
            if (d < 0) continue;
            into.ux()[n] = (x - x0) / d;
            into.uy()[n] = (y - y0) / d;
            into.d()[n] = d;
            into.ax()[n] = Params.COS[s % turns];
            into.ay()[n] = Params.SIN[s % turns];
            n++;
        }
        return into.count(n);
    }

    /** The desired direction one aggregation produces. */
    private static void desired(Aggregation a, Aggregation.Neighbours nb, Flocking f,
                                double[] scratch, double[] dir) {
        Arrays.fill(scratch, 0);
        if (nb.n() > 0) a.combine(nb, f, scratch);
        dir[0] = scratch[0] + scratch[2] + scratch[4];
        dir[1] = scratch[1] + scratch[3] + scratch[5];
    }

    /**
     * How much the normalisation is being asked to invent, for one rule.
     * <p>
     * {@code sum|contribution| / |sum of contributions|}. One means the neighbours agree
     * perfectly and normalising changes nothing but scale; ten means they very nearly cancelled
     * and the surviving direction is a residue that normalisation has restored to full strength.
     * <b>This is a property of the arrangement, not of the aggregation</b>, which is what makes it
     * usable as a test of whether the unaccounted exits are the ones with heavy cancellation.
     */
    public static double cancellation(double[] cx, double[] cy, int n) {
        double sx = 0, sy = 0, total = 0;
        for (int j = 0; j < n; j++) {
            sx += cx[j];
            sy += cy[j];
            total += Math.hypot(cx[j], cy[j]);
        }
        double m = Math.hypot(sx, sy);
        return m <= 0 ? Double.POSITIVE_INFINITY : total / m;
    }

    /** Alignment's cancellation for an arrangement, the term most prone to it. */
    public static double alignmentCancellation(Aggregation.Neighbours nb) {
        return cancellation(nb.ax(), nb.ay(), nb.n());
    }

    /**
     * Proves {@link Aggregation#CURRENT} is the simulation and not a description of it.
     * <p>
     * Every comparison below is against this baseline, so a baseline that has drifted from
     * {@link MovementLogic} would make the whole survey a measurement of my own transcription
     * error. Checked against the real thing on real arrangements, and loudly.
     */
    private static void checkFidelity(NavMap map, int[] live, int liveCount, Flocking f,
                                      double turningRadius, long seed) {
        MovementLogic rules = new MovementLogic(turningRadius);
        Random rng = new Random(seed ^ 0x5eed);
        double[] scratch = new double[6], dir = new double[2];
        Aggregation.Neighbours nb = Aggregation.Neighbours.of(8);
        int turns = Params.TURNS, w = map.width();
        int checked = 0, disagreed = 0;
        double worst = 0;

        for (int t = 0; t < 200_000 && checked < 20_000; t++) {
            int n = 2 + rng.nextInt(3);
            int[] states = new int[n + 1];
            for (int i = 0; i <= n; i++) states[i] = live[rng.nextInt(liveCount)];
            int[] xs = new int[n + 1], ys = new int[n + 1], hs = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                int c = states[i] / turns;
                xs[i] = c % w;
                ys[i] = c / w;
                hs[i] = states[i] % turns;
            }
            MovementLogic.Influence in = rules.decompose(new BoidArray(n + 1, xs, ys, hs, 0), 0);
            if (in.seen() < 2) continue;
            checked++;

            int[] others = new int[n];
            System.arraycopy(states, 1, others, 0, n);
            Aggregation.Neighbours seen = see(map, rules, states[0], others, n, nb);
            desired(Aggregation.CURRENT, seen, f, scratch, dir);
            double gap = Math.hypot(dir[0] - in.dirX(), dir[1] - in.dirY());
            worst = Math.max(worst, gap);
            if (turn(dir[0], dir[1], hs[0], f) != in.turn()) disagreed++;
        }
        System.out.printf("%nfidelity of Aggregation.CURRENT against MovementLogic over %,d "
                        + "arrangements: %d turn disagreements, worst vector gap %.3e%n",
                checked, disagreed, worst);
        if (disagreed > 0 || worst > 1e-9) {
            System.out.println("  *** THE BASELINE IS NOT THE SIMULATION -- every number below "
                    + "is measured against the wrong thing ***");
        }
    }

    /**
     * Whether the exits nothing accounts for are the ones where the normalisation is working
     * hardest.
     * <p>
     * The hypothesis is that a white cell is white <em>because</em> its arrangement is one the
     * per-rule normalisation had to invent a direction for: neighbours that nearly cancel, a short
     * residue, and a full-weight signal restored from it that no single neighbour asked for. If
     * so, the residue is an artefact of the aggregation rather than a fact about flocking, and
     * changing the aggregation is the right lever rather than modelling three boids.
     * <p>
     * Tested by taking the arrangement at envelope entry for a sample of accounted and
     * unaccounted exits and comparing the cancellation in each. Nothing here is fitted; both
     * samples come from the same run and the statistic does not depend on which aggregation is
     * in use.
     */
    public static void residueTest(PresetScenarioParameter preset, SolverFacts f,
                                   ExitAudit.Tables tables, int from, Flocking flock,
                                   java.nio.file.Path replays, int perClass, long seed)
            throws java.io.IOException {
        NavMap map = tables.map();
        MovementLogic rules = new MovementLogic(preset.turningRadius());
        Boids2DEngine engine = new Boids2DEngine(preset);
        Random rng = new Random(seed);
        Spread[] ali = {new Spread(perClass), new Spread(perClass)};
        Spread[] coh = {new Spread(perClass), new Spread(perClass)};
        Spread[] invented = {new Spread(perClass), new Spread(perClass)};
        int[] got = new int[2];
        // Reservoir-free: walk the file once and take every row with a probability that gives
        // roughly the wanted count, so the sample is not the first N rows of one region.
        double[] keep = {0, 0};

        try (java.io.BufferedReader in = java.nio.file.Files.newBufferedReader(replays)) {
            String line = in.readLine();
            long[] seen = new long[2];
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 12) continue;
                seen[p[4].equals("NONE") ? 0 : 1]++;
                rows.add(p);
            }
            keep[0] = Math.min(1, perClass / (double) Math.max(1, seen[0]));
            keep[1] = Math.min(1, perClass / (double) Math.max(1, seen[1]));
            System.out.printf("%n=== residue test: %,d unaccounted and %,d accounted exits ===%n",
                    seen[0], seen[1]);

            double[] scratch = new double[6], dirNow = new double[2], dirVote = new double[2];
            Aggregation.Neighbours nb = Aggregation.Neighbours.of(4);
            for (String[] p : rows) {
                int cls = p[4].equals("NONE") ? 0 : 1;
                if (got[cls] >= perClass || rng.nextDouble() > keep[cls]) continue;
                int[] at = ThreeBoidSamples.entryArrangement(engine, f, tables, from,
                        Integer.parseInt(p[9]), Integer.parseInt(p[10]), Integer.parseInt(p[11]),
                        Boolean.parseBoolean(p[8]));
                if (at == null) continue;
                got[cls]++;
                Aggregation.Neighbours seenNb = see(map, rules, at[2], new int[]{at[0], at[1]}, 2, nb);
                if (seenNb.n() < 2) continue;
                ali[cls].add(alignmentCancellation(seenNb));
                coh[cls].add(cancellation(seenNb.ux(), seenNb.uy(), seenNb.n()));
                desired(Aggregation.CURRENT, seenNb, flock, scratch, dirNow);
                desired(Aggregation.VOTE_MEAN, seenNb, flock, scratch, dirVote);
                double a = Math.hypot(dirNow[0], dirNow[1]), b = Math.hypot(dirVote[0], dirVote[1]);
                if (b > 1e-9) invented[cls].add(a / b);
            }
        }

        String[] name = {"unaccounted", "accounted"};
        System.out.printf("%n%-12s %7s %10s %10s %10s %10s %10s%n", "class", "n", "ali p50",
                "ali p90", "ali >4x", "coh p50", "invented");
        for (int c = 0; c < 2; c++) {
            System.out.printf("%-12s %7d %10.2f %10.2f %9.1f%% %10.2f %10.2f%n", name[c],
                    ali[c].count(), ali[c].at(0.5), ali[c].at(0.9), ali[c].above(4),
                    coh[c].at(0.5), invented[c].at(0.5));
        }
        System.out.println("  ali = alignment cancellation (sum of lengths / length of sum) at "
                + "envelope entry");
        System.out.println("  invented = |desired| today / |desired| under VOTE_MEAN, so how much "
                + "longer the current signal is than the average of what the neighbours asked");
    }

    /**
     * Proves the single-neighbour closed form is the aggregation restricted to one neighbour.
     * <p>
     * {@link EdgeInfluence#steer} is what the whole critical-envelope analysis reasons with, and
     * {@link Aggregation} is what the simulation flies. They are two implementations of the same
     * thing at {@code n = 1}, coupled only by {@link Aggregation#separationFalloffAtOne()} — which
     * is precisely the kind of coupling that drifts silently and invalidates every table built
     * afterwards. So it is checked rather than trusted, for every variant.
     *
     * @return true if all of them agree
     */
    public static boolean checkClosedForm(NavMap map, int[] live, int liveCount, Flocking base,
                                          double turningRadius, long seed, int trials) {
        return checkClosedForm(map, live, liveCount, base, turningRadius, seed, trials,
                Aggregation.ALL);
    }

    /**
     * The same, over a chosen set.
     * <p>
     * <b>Not every aggregation has a closed form, and that is a property worth reporting rather
     * than a failure to route around.</b> {@code VOTE_NORM} renormalises the total, so its
     * one-neighbour behaviour is not {@link EdgeInfluence#steer} at any setting — which
     * disqualifies it from being adopted without rewriting the whole critical-envelope analysis,
     * and is the reason it exists only as a control.
     */
    public static boolean checkClosedForm(NavMap map, int[] live, int liveCount, Flocking base,
                                          double turningRadius, long seed, int trials,
                                          Aggregation... which) {
        MovementLogic rules = new MovementLogic(turningRadius);
        Random rng = new Random(seed ^ 0xc10ed);
        double[] scratch = new double[6], dir = new double[2];
        Aggregation.Neighbours nb = Aggregation.Neighbours.of(2);
        boolean allWell = true;
        System.out.printf("%nclosed form vs aggregation at n=1:%n");
        for (Aggregation a : which) {
            Flocking f = base.sepFalloff(a.separationFalloffAtOne());
            int checked = 0, turnGap = 0;
            double worst = 0;
            for (int t = 0; t < trials * 8 && checked < trials; t++) {
                int self = live[rng.nextInt(liveCount)];
                Aggregation.Neighbours one = see(map, rules, self,
                        new int[]{live[rng.nextInt(liveCount)]}, 1, nb);
                if (one.n() != 1) continue;
                checked++;
                int heading = self % Params.TURNS;
                desired(a, one, f, scratch, dir);
                int mine = turn(dir[0], dir[1], heading, f);
                int theirs = EdgeInfluence.steer(heading,
                        (int) Math.round(one.d()[0] * one.ux()[0]),
                        (int) Math.round(one.d()[0] * one.uy()[0]), headingOf(one), f);
                if (mine != theirs) turnGap++;
                worst = Math.max(worst, Math.abs(mine - theirs));
            }
            boolean ok = turnGap == 0;
            allWell &= ok;
            System.out.printf("  %-16s falloff=%-5s %,6d checked, %d disagreements%s%n", a.id(),
                    a.separationFalloffAtOne(), checked, turnGap,
                    ok ? "" : "   *** THE CLOSED FORM AND THE SIMULATION DISAGREE ***");
        }
        return allWell;
    }

    /** The heading index a neighbour's unit heading vector came from. */
    private static int headingOf(Aggregation.Neighbours nb) {
        double best = -2;
        int at = 0;
        for (int h = 0; h < Params.TURNS; h++) {
            double dot = Params.COS[h] * nb.ax()[0] + Params.SIN[h] * nb.ay()[0];
            if (dot > best) { best = dot; at = h; }
        }
        return at;
    }

    // ---- the survey ----------------------------------------------------------

    public static void run(NavMap map, int[] live, int liveCount, Flocking f,
                           double turningRadius, long seed, int trials) {
        Random rng = new Random(seed);
        Aggregation[] all = Aggregation.ALL;
        Score[] score = new Score[all.length];
        for (int i = 0; i < all.length; i++) score[i] = new Score(Math.min(trials, 200_000));

        MovementLogic rules = new MovementLogic(turningRadius);
        double[] scratch = new double[6];
        double[] dirA = new double[2], dirB = new double[2], dirAB = new double[2];
        int[] pick = new int[3];
        Aggregation.Neighbours one = Aggregation.Neighbours.of(4);
        Aggregation.Neighbours both = Aggregation.Neighbours.of(4);
        Aggregation.Neighbours three = Aggregation.Neighbours.of(4);
        Aggregation.Neighbours bumped = Aggregation.Neighbours.of(4);

        long sampled = 0;
        Spread cancel = new Spread(Math.min(trials, 200_000));
        checkFidelity(map, live, liveCount, f, turningRadius, seed);

        for (long t = 0; t < trials; t++) {
            int self = live[rng.nextInt(liveCount)];
            // Two neighbours it can actually see. Rejection sampling, capped so a boid in a
            // corner of the map cannot spin here forever.
            int found = 0;
            for (int tries = 0; tries < 64 && found < 3; tries++) {
                int cand = live[rng.nextInt(liveCount)];
                pick[found] = cand;
                Aggregation.Neighbours probe = see(map, rules, self, pick, found + 1, one);
                if (probe.n() == found + 1) found++;
            }
            if (found < 2) continue;
            sampled++;

            int heading = self % Params.TURNS;
            Aggregation.Neighbours nbA = see(map, rules, self, new int[]{pick[0]}, 1, one);
            Aggregation.Neighbours nbB = see(map, rules, self, new int[]{pick[1]}, 1, bumped);
            Aggregation.Neighbours nbAB = see(map, rules, self, pick, 2, both);

            cancel.add(alignmentCancellation(nbAB));

            // A one-pixel nudge of the second neighbour, for the chaos measure. Moving the state
            // index by TURNS is exactly one pixel in x.
            int[] nudged = {pick[0], pick[1] + Params.TURNS};
            boolean nudgeOk = nudged[1] < map.width() * map.height() * Params.TURNS;

            int currentTurn = 0;
            for (int i = 0; i < all.length; i++) {
                desired(all[i], nbA, f, scratch, dirA);
                desired(all[i], nbB, f, scratch, dirB);
                desired(all[i], nbAB, f, scratch, dirAB);

                double sA = across(dirA[0], dirA[1], heading);
                double sB = across(dirB[0], dirB[1], heading);
                double sAB = across(dirAB[0], dirAB[1], heading);
                double lo = Math.min(sA, sB), hi = Math.max(sA, sB);

                Score sc = score[i];
                sc.pairs++;
                if (sAB >= lo - 1e-9 && sAB <= hi + 1e-9) sc.triangleOk++;
                if (0.5 * sAB >= lo - 1e-9 && 0.5 * sAB <= hi + 1e-9) sc.triangleOkHalf++;
                double biggest = Math.max(Math.abs(sA), Math.abs(sB));
                if (biggest > 1e-9) sc.amps.add(Math.abs(sAB) / biggest);
                sc.reach.add(Math.abs(sAB));

                int turnAB = turn(dirAB[0], dirAB[1], heading, f);
                if (i == 0) currentTurn = turnAB;
                else if (turnAB == currentTurn) sc.matched++;

                if (nudgeOk) {
                    Aggregation.Neighbours nb2 = see(map, rules, self, nudged, 2, three);
                    if (nb2.n() == 2) {
                        desired(all[i], nb2, f, scratch, dirA);
                        sc.flipTrials++;
                        if (turn(dirA[0], dirA[1], heading, f) != turnAB) sc.flipped++;
                        // How far the signal moved for one pixel, in units of the straight
                        // bias — which is the only scale on which a signal change means
                        // anything, since that is what a turn has to beat.
                        sc.jolt.add(Math.abs(across(dirA[0], dirA[1], heading) - sAB)
                                / f.straightBias());
                    }
                }
            }

            if (found >= 3) {
                Aggregation.Neighbours nb3 = see(map, rules, self, pick, 3, three);
                int base = 0;
                for (int i = 0; i < all.length; i++) {
                    desired(all[i], nb3, f, scratch, dirAB);
                    int tn = turn(dirAB[0], dirAB[1], heading, f);
                    score[i].triples++;
                    if (i == 0) base = tn;
                    else if (tn == base) score[i].tripleMatched++;
                }
            }
        }

        System.out.printf("%n=== aggregation survey: %,d arrangements sampled from the map ===%n",
                sampled);
        System.out.printf("alignment cancellation (sum of lengths / length of sum) over the "
                        + "sampled pairs:%n  median %.2fx, p90 %.2fx, p99 %.2fx; %.1f%% above 4x, "
                        + "%.1f%% above 10x%n", cancel.at(0.5), cancel.at(0.9), cancel.at(0.99),
                cancel.above(4), cancel.above(10));
        System.out.printf("%n%-16s %8s %8s %8s %8s %8s %8s %8s %8s %8s%n", "variant",
                "tri k=1", "tri k=.5", "amp p50", "amp p90", "amp p99", "jolt p99", "1px flip",
                "match 2", "match 3");
        for (int i = 0; i < all.length; i++) {
            Score sc = score[i];
            System.out.printf("%-16s %7.1f%% %7.1f%% %8.2f %8.2f %8.2f %8.2f %7.2f%% %7.1f%% "
                            + "%7.1f%%%n", all[i].id(),
                    100.0 * sc.triangleOk / Math.max(1, sc.pairs),
                    100.0 * sc.triangleOkHalf / Math.max(1, sc.pairs),
                    sc.amps.at(0.50), sc.amps.at(0.90), sc.amps.at(0.99), sc.jolt.at(0.99),
                    100.0 * sc.flipped / Math.max(1, sc.flipTrials),
                    i == 0 ? 100.0 : 100.0 * sc.matched / Math.max(1, sc.pairs),
                    i == 0 ? 100.0 : 100.0 * sc.tripleMatched / Math.max(1, sc.triples));
        }
        System.out.printf("%nsignal reach (median, p99) so the columns above are readable:%n");
        for (int i = 0; i < all.length; i++) {
            System.out.printf("  %-16s %8.1f %8.1f   (straight bias %.4f)%n", all[i].id(),
                    score[i].reach.at(0.5), score[i].reach.at(0.99), f.straightBias());
        }
        System.out.println();
        for (Aggregation a : all) System.out.printf("  %-16s %s%n", a.id(), a.describes());
    }
}
