package boids;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Where output derived from a map belongs, addressed by everything it is a function of.
 *
 * <h2>The rule</h2>
 * <b>An artifact's address is a hash of its whole input closure, including the addresses of its
 * inputs.</b> Two artifacts computed from different inputs can then never share a path, and
 * nothing can be read against inputs it was not built from.
 * <p>
 * This project used to record the discriminating input in an artifact's <em>content</em> and not
 * in its <em>address</em> — the physics version in {@code meta.txt}, the gate in the edge graph's
 * title, the configuration in the corpus header. None of those can stop a reader picking up the
 * wrong file. Two stores hashed their inputs into a filename and were safe; the rest were at fixed
 * paths and were not, so a physics change would have silently overwritten
 * {@code solver/facts.bin} and silently reread {@code psyboid/plans.tsv} — the corpus the solver
 * is graded against.
 *
 * <h2>Three tiers, because the dependencies really do separate</h2>
 * <pre>
 * ingests/&lt;map&gt;/                     pixels, radius, navigability, trap-trimming
 *   map.png  display.png  meta.txt
 *   structure/&lt;structure&gt;/            + step geometry, gate, weighting scheme
 *       edges/  metric/  navmap/  meta.txt
 *     behaviour/&lt;behaviour&gt;/          + physics version, flocking constants, aggregation
 *         envelope/  windows/  twoboid/  psyboid/  solver/  audit/  corpus/  meta.txt
 * </pre>
 * <b>Nothing in the structure tier reads a flocking constant.</b> The navmap is a viability
 * question about geometry, the decomposition is a question about the navmap, and the clock is a
 * question about the decomposition — verified when the physics-3 proposal was run end to end and
 * {@code pureStable}, map-wide stable, the envelope and cost-to-leave all came out identical.
 * So a physics change rebuilds only the behaviour tier, and the clock's thousands of gradient
 * steps survive it.
 * <p>
 * Behaviour nests <em>inside</em> structure rather than beside it, because it depends on it:
 * deleting a decomposition should take its tables with it, and a path should not be able to
 * describe a combination that never existed.
 *
 * <h2>What is fed to each hash</h2>
 * The structure tier takes the step geometry directly — {@code TURNS}, the radius and the speed
 * it implies — rather than trusting that radius reaches it through the map hash. That is the
 * mistake this class exists to stop making: an input that is only <em>transitively</em> in an
 * address is one nobody can check.
 * <p>
 * It deliberately does <b>not</b> take {@link Params#PHYSICS}. A physics version names the
 * decision rules, and the decision rules do not enter the navmap, the decomposition or the clock.
 * If one ever changes the step geometry instead, the geometry fed here changes with it and the
 * structure hash moves on its own.
 *
 * <h2>One deliberate over-keying, and why it is the safe direction</h2>
 * The critical envelope depends on the decomposition and the flocking constants; it does
 * <em>not</em> depend on the weighting scheme, which only fits the clock. But envelope tables sit
 * in the behaviour tier, which nests under a structure hash that includes the scheme — so changing
 * the scheme invalidates tables that would not have changed.
 * <p>
 * That is accepted on purpose. <b>Over-keying costs a rebuild; under-keying returns the wrong
 * answer without saying so</b>, and this project has already lost one conclusion that way. The
 * scheme has been {@code MOMENTUM} throughout, so the cost has never been paid, and the
 * alternative — a third tier between the two — buys one avoided rebuild at the price of a layout
 * nobody can hold in their head.
 */
public final class Derived {
    private Derived() {}

    /** Bump when the meaning of a tier's contents changes, not when its inputs do. */
    private static final int FORMAT = 1;

    private static final int SHORT = 16;

    /**
     * A decomposition of one map, and everything that follows from geometry alone.
     *
     * @param hash names the map, the step geometry, the gate and the weighting scheme
     */
    public record Structure(MapStore.Ingest ingest, String hash, Path dir) {

        /** A subdirectory of this tier, created on demand. */
        public Path at(String... parts) {
            Path p = dir;
            for (String part : parts) p = p.resolve(part);
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create " + p, e);
            }
            return p;
        }

        /** Everything that also depends on what a boid decides. */
        public Behaviour behaviour(Flocking flocking, Aggregation aggregation) {
            return Derived.behaviour(this, flocking, aggregation);
        }

        @Override
        public String toString() { return ingest + "/s:" + hash; }
    }

    /**
     * Everything downstream of the decision rules, for one set of them.
     *
     * @param hash names the structure, the physics version, the flocking constants and the
     *             aggregation
     */
    public record Behaviour(Structure structure, String hash, Path dir) {

        public Path at(String... parts) {
            Path p = dir;
            for (String part : parts) p = p.resolve(part);
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create " + p, e);
            }
            return p;
        }

        /** One corpus recipe flown under these rules. */
        public Corpus corpus(CorpusPreset preset) {
            return Derived.corpus(this, preset);
        }

        @Override
        public String toString() { return structure + "/b:" + hash; }
    }

    /**
     * One psyboid corpus: a recipe, flown under one set of rules, on one decomposition of one map.
     * <p>
     * A third level rather than a file in the behaviour tier, because a corpus is a function of
     * more than the physics: how many seeds, how long the warm-up, how far the search looks. Those
     * used to live in the file's header, where nothing could act on them — two recipes collided at
     * one {@code plans.tsv} and the second silently replaced the first.
     */
    public record Corpus(Behaviour behaviour, CorpusPreset preset, String hash, Path dir) {

        public Path at(String... parts) {
            Path p = dir;
            for (String part : parts) p = p.resolve(part);
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create " + p, e);
            }
            return p;
        }

        @Override
        public String toString() { return behaviour + "/c:" + preset + "@" + hash; }
    }

    /**
     * The structure tier for one map under one gate and weighting scheme.
     *
     * @param radius the turning radius the navmap is built at, which fixes the step geometry
     * @param chain  the transition-weight blend the clock is fitted with
     */
    public static Structure structure(MapStore.Ingest ingest, double radius,
                                      SolverFacts.Gate gate, EdgeWeights.Scheme scheme,
                                      double[][] chain) {
        Digest d = new Digest();
        d.text("boids-structure-v" + FORMAT);
        d.text(ingest.hash());
        d.number(Params.TURNS);
        d.real(radius);
        d.real(Params.speed(radius));
        d.text(gate.toString());
        d.text(scheme.name());
        for (double[] row : chain) for (double v : row) d.real(v);

        String hash = d.hex();
        Path dir = ingest.dir().resolve("structure").resolve(hash);
        Structure s = new Structure(ingest, hash, dir);
        explain(dir, """
                tier       structure
                of         %s
                hash       %s
                format     %d

                turns      %d
                radius     %s
                speed      %s
                gate       %s
                scheme     %s
                chain      %s

                What lives here depends on the map's geometry and on nothing a boid decides:
                the navmap, the edge decomposition, the clock, and anything derived from
                straight travel alone. A change to the decision rules does not belong in this
                hash and does not invalidate anything under it.
                """.formatted(ingest, hash, FORMAT, Params.TURNS, radius, Params.speed(radius),
                gate, scheme, Arrays.deepToString(chain)));
        return s;
    }

    /** The behaviour tier for one structure under one set of decision rules. */
    public static Behaviour behaviour(Structure structure, Flocking flocking,
                                      Aggregation aggregation) {
        Digest d = new Digest();
        d.text("boids-behaviour-v" + FORMAT);
        d.text(structure.hash());
        d.number(Params.PHYSICS);
        d.text(aggregation.id());
        d.real(flocking.wSep());
        d.real(flocking.wCoh());
        d.real(flocking.wAli());
        d.real(flocking.straightBias());
        d.real(flocking.rSep());
        d.real(flocking.rFlock());
        d.number(flocking.sepFalloff() ? 1 : 0);

        String hash = d.hex();
        Path dir = structure.dir().resolve("behaviour").resolve(hash);
        Behaviour b = new Behaviour(structure, hash, dir);
        explain(dir, """
                tier       behaviour
                of         %s
                hash       %s
                format     %d

                physics    %d
                aggregation %s
                wSep       %s
                wCoh       %s
                wAli       %s
                bias       %s
                rSep       %s
                rFlock     %s
                sepFalloff %s

                What lives here depends on what a boid decides: critical-envelope tables and
                their windows, two-boid reachability, the psyboid corpus, solver facts, audits
                and phase maps. Everything above this directory is untouched by a change to
                the decision rules, which is why a physics bump costs this tier and not the
                clock.
                """.formatted(structure, hash, FORMAT, Params.PHYSICS, aggregation.id(),
                flocking.wSep(), flocking.wCoh(), flocking.wAli(), flocking.straightBias(),
                flocking.rSep(), flocking.rFlock(), flocking.sepFalloff()));
        return b;
    }

    /** One corpus recipe under one set of decision rules. */
    public static Corpus corpus(Behaviour behaviour, CorpusPreset preset) {
        Digest d = new Digest();
        d.text("boids-corpus-v" + FORMAT);
        d.text(behaviour.hash());
        d.text(preset.name());
        d.text(preset.fingerprint());

        String hash = d.hex();
        Path dir = behaviour.dir().resolve("psyboid").resolve(preset.name().toLowerCase(
                java.util.Locale.ROOT) + "-" + hash);
        Corpus c = new Corpus(behaviour, preset, hash, dir);
        explain(dir, """
                tier       corpus
                of         %s
                preset     %s — %s
                hash       %s
                format     %d

                %s

                Seeds are 0 to %d. A corpus is a set of plans, each a seed plus the overrides
                that were committed for it, and every row was replayed from its own label and
                matched position for position before being written. The recipe is in this
                directory's name as well as in this file, so a corpus flown under a different
                one cannot be picked up in its place.
                """.formatted(behaviour, preset.name(), preset.describes(), hash, FORMAT,
                preset.fingerprint(), preset.seeds() - 1));
        return c;
    }

    /**
     * Writes the tier's own {@code meta.txt}, once.
     * <p>
     * A hash nobody can explain is a hash nobody will trust, and the first thing anyone does with
     * an unfamiliar directory full of them is try to work out which is which. Written on creation
     * and never rewritten, so it always describes the inputs the contents were actually built
     * from.
     */
    private static void explain(Path dir, String text) {
        try {
            Files.createDirectories(dir);
            Path meta = dir.resolve("meta.txt");
            if (!Files.isRegularFile(meta)) Files.writeString(meta, text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot describe " + dir, e);
        }
    }

    /** SHA-256 over a sequence of labelled parts, so two different inputs cannot collide. */
    private static final class Digest {
        private final MessageDigest md;

        Digest() {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required by the platform", e);
            }
        }

        /** Length-prefixed, so {@code "ab" + "c"} cannot hash as {@code "a" + "bc"}. */
        void text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            number(b.length);
            md.update(b);
        }

        void number(int v) {
            md.update(new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v});
        }

        void real(double v) {
            long bits = Double.doubleToLongBits(v);
            number((int) (bits >>> 32));
            number((int) bits);
        }

        String hex() {
            byte[] sum = md.digest();
            StringBuilder out = new StringBuilder(SHORT);
            for (int i = 0; i < SHORT / 2; i++) out.append(String.format("%02x", sum[i]));
            return out.toString();
        }
    }
}
