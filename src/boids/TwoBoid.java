package boids;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Every arrangement of one psyboid and one boid that a run could ever reach.
 * <p>
 * With two boids the whole system fits in a graph small enough to enumerate, which makes the
 * question exact rather than sampled: not "what did a thousand simulations happen to show" but
 * "what is possible at all". Everything true of two boids on this map — every configuration
 * that can occur, every one that cannot, and every deterministic consequence of the pair — is
 * a property of this set.
 * <p>
 * <b>Sampled after the psyboid moves.</b> Boids are advanced in index order, so a tick has an
 * interior: there is a moment when the psyboid has moved and the boid has not, and the two
 * halves of a tick are genuinely different states. Taking the snapshot at that moment makes the
 * state exactly two triples instead of two triples and a phase flag, halving the space — and
 * nothing is lost, because the other half is recovered by letting the boid take the move it was
 * always going to take.
 * <p>
 * <b>The psyboid branches, the boid does not.</b> An override replaces whatever the flocking
 * rules wanted with a free choice of left, straight or right, so the psyboid has up to three
 * successors; the boid has exactly one, being a pure function of the arrangement it sees. That
 * asymmetry is the whole reason the set is interesting: it is the set of positions a
 * <em>controller</em> can drive the pair into.
 */
public final class TwoBoid {
    private TwoBoid() {}

    private static final int FORMAT = 1;

    /**
     * @param bits  one per {@code (psyboid live index) * liveCount + (boid live index)}
     * @param count how many are set
     * @param start the arrangement the search grew from
     */
    public record Reachable(int liveCount, long[] bits, long count, long start) {
        public boolean has(long pair) {
            return (bits[(int) (pair >>> 6)] & (1L << (pair & 63))) != 0;
        }

        public long pairs() {
            return (long) liveCount * liveCount;
        }
    }

    /**
     * Grows the reachable set forward from one arrangement until nothing new appears.
     * <p>
     * Forward closure from a single seed rather than a search for states that can reach
     * themselves. The two differ — forward closure also picks up arrangements that occur once
     * and never recur — and the cheaper one is the right first answer: if the map's reachable
     * arrangements form one piece, which they should unless something degenerate is going on,
     * then one seed finds all of it and the size says so.
     *
     * @param startP the psyboid's live index to grow from
     * @param startB the boid's live index to grow from
     */
    public static Reachable explore(NavMap map, double turningRadius, int[] live, int liveCount,
                                    int startP, int startB) {
        long pairs = (long) liveCount * liveCount;
        long[] bits = new long[(int) ((pairs + 63) >>> 6)];

        // Where every request leads, resolved once. The search asks for these several billion
        // times, and recomputing a veto each time is the difference between an afternoon and a
        // week.
        int[] index = new int[map.width() * map.height() * Params.TURNS];
        java.util.Arrays.fill(index, -1);
        for (int i = 0; i < liveCount; i++) index[live[i]] = i;
        int[] steered = new int[liveCount * 3];
        byte[] steers = new byte[liveCount];
        int[] plain = new int[liveCount];
        int[] out = new int[3];
        for (int i = 0; i < liveCount; i++) {
            int n = map.steeredSuccessors(live[i], out);
            steers[i] = (byte) n;
            for (int k = 0; k < n; k++) steered[i * 3 + k] = index[out[k]];
            int straight = map.successor(live[i], 0);
            plain[i] = straight < 0 ? -1 : index[straight];
        }

        Walk walk = new Walk(map, turningRadius, live, index, plain);

        // The frontier holds the two indices side by side rather than the bit number, so
        // unpacking is a shift. Recovering them from a bit number would need a division by
        // liveCount, and at several billion expansions that division is most of the run.
        long[] stack = new long[1 << 16];
        int top = 0;
        boolean overflowed = false;
        long seen = 0;
        long start = ((long) startP << 32) | startB;
        set(bits, (long) startP * liveCount + startB);
        seen++;
        stack[top++] = start;

        long expanded = 0, began = System.nanoTime();
        for (;;) {
            while (top > 0) {
                long at = stack[--top];
                int p = (int) (at >>> 32), b = (int) at;
                int nb = walk.boid(p, b);
                expanded++;
                if ((expanded & 0x3FFFFFFL) == 0) {
                    System.out.printf("    %,d reached, %,d expanded, frontier %,d, %.0fs%n",
                            seen, expanded, top, (System.nanoTime() - began) / 1e9);
                }
                if (nb < 0) continue;
                for (int k = 0, n = steers[p]; k < n; k++) {
                    int np = steered[p * 3 + k];
                    if (np < 0) continue;
                    if (!set(bits, (long) np * liveCount + nb)) continue;
                    seen++;
                    if (top == stack.length) {
                        if (stack.length < STACK_CAP) {
                            long[] bigger = new long[Math.min(STACK_CAP, stack.length * 2)];
                            System.arraycopy(stack, 0, bigger, 0, stack.length);
                            stack = bigger;
                        } else {
                            // Dropped rather than grown without limit. Nothing is lost: the
                            // bit is already set, so the sweep below finds it again.
                            overflowed = true;
                            continue;
                        }
                    }
                    stack[top++] = ((long) np << 32) | nb;
                }
            }
            if (!overflowed) break;
            // Everything the frontier could not hold is still marked, so a pass over the marks
            // recovers it. Walked as nested indices rather than as bit numbers for the same
            // reason the frontier is packed: no division.
            overflowed = false;
            System.out.printf("    frontier overflowed; sweeping %,d marks%n", seen);
            long before = seen, at = 0;
            for (int p = 0; p < liveCount; p++) {
                for (int b = 0; b < liveCount; b++, at++) {
                    if ((bits[(int) (at >>> 6)] & (1L << (at & 63))) == 0) continue;
                    int nb = walk.boid(p, b);
                    if (nb < 0) continue;
                    for (int k = 0, n = steers[p]; k < n; k++) {
                        int np = steered[p * 3 + k];
                        if (np < 0) continue;
                        if (!set(bits, (long) np * liveCount + nb)) continue;
                        seen++;
                        if (top < stack.length) stack[top++] = ((long) np << 32) | nb;
                        else overflowed = true;
                    }
                }
            }
            if (seen == before && top == 0) break;
        }
        System.out.printf("    done: %,d reached of %,d pairs (%.4f%%), %,d expansions in %.0fs%n",
                seen, pairs, 100.0 * seen / pairs, expanded, (System.nanoTime() - began) / 1e9);
        return new Reachable(liveCount, bits, seen, start);
    }

    /** How much frontier to hold before falling back to sweeping the marks. 512 MiB. */
    private static final int STACK_CAP = 1 << 26;

    private static boolean set(long[] bits, long at) {
        int word = (int) (at >>> 6);
        long mask = 1L << (at & 63);
        if ((bits[word] & mask) != 0) return false;
        bits[word] |= mask;
        return true;
    }

    /**
     * The boid's half of a tick, run through the same rules the simulation runs.
     * <p>
     * The real {@link MovementLogic} rather than a copy of its arithmetic, on a two-element
     * array reused between calls. A reimplementation here would be a second definition of
     * flocking that has to be kept in step with the first, and the whole value of this
     * enumeration is that it describes the simulation and not something near it.
     */
    private static final class Walk {
        private final NavMap map;
        private final MovementControl rules;
        private final MovementControl.Movement movement;
        private final int[] live, index, plain;
        private final int width;
        private final double reach;

        Walk(NavMap map, double turningRadius, int[] live, int[] index, int[] plain) {
            this.map = map;
            this.live = live;
            this.index = index;
            this.plain = plain;
            this.width = map.width();
            this.rules = new MovementLogic(turningRadius);
            this.reach = Params.flock(turningRadius);
            this.movement = new MovementControl.Movement(new int[2], new int[2], new int[2], 0);
        }

        /** Where the boid goes, given a psyboid that has already moved this tick. */
        int boid(int p, int b) {
            int ps = live[p], bs = live[b];
            int pd = ps % Params.TURNS, pc = ps / Params.TURNS;
            int bd = bs % Params.TURNS, bc = bs / Params.TURNS;
            int px = pc % width, py = pc / width;
            int bx = bc % width, by = bc / width;

            // Out of sight is the common case by a wide margin, and it needs no rules at all:
            // a boid with nothing in view holds its heading.
            double dx = px - bx, dy = py - by;
            if (dx * dx + dy * dy > reach * reach) return plain[b];

            int[] x = movement.boids.x(), y = movement.boids.y(), h = movement.boids.h();
            x[0] = px; y[0] = py; h[0] = pd;
            x[1] = bx; y[1] = by; h[1] = bd;
            // Reset, because the rules leave the value alone when nothing is in view rather
            // than writing a zero.
            movement.movement[1] = 0;
            rules.calculate(movement, 1);
            int turn = Math.max(-1, Math.min(1, movement.movement[1]));
            int next = map.successor(bs, turn);
            return next < 0 ? -1 : index[next];
        }
    }

    /**
     * Every edge-to-edge move the boid makes anywhere in the reachable set, counted.
     * <p>
     * A boid never chooses; it goes where the flocking rules and the wall send it. So a
     * transition appearing here that holding straight would not produce is a transition some
     * psyboid <em>caused</em>, and the set says exactly which ones are possible rather than
     * which ones a simulation happened to show. A transition that does not appear cannot be
     * induced at all, however the psyboid flies.
     *
     * @return {@code [from][to]} counts over arrangements, so a move available from many
     *         arrangements counts many times
     */
    public static long[][] boidEdgeMoves(NavMap map, double turningRadius, int[] live,
                                         int liveCount, int[] index, int[] edge, int edges,
                                         Reachable r) {
        int[] plain = new int[liveCount];
        for (int i = 0; i < liveCount; i++) {
            int straight = map.successor(live[i], 0);
            plain[i] = straight < 0 ? -1 : index[straight];
        }
        Walk walk = new Walk(map, turningRadius, live, index, plain);
        long[][] moves = new long[edges][edges];
        long at = 0;
        for (int p = 0; p < liveCount; p++) {
            for (int b = 0; b < liveCount; b++, at++) {
                if ((r.bits()[(int) (at >>> 6)] & (1L << (at & 63))) == 0) continue;
                int nb = walk.boid(p, b);
                if (nb < 0) continue;
                int from = edge[live[b]], to = edge[live[nb]];
                if (from >= 0 && to >= 0) moves[from][to]++;
            }
        }
        return moves;
    }

    // ---- Storage -----------------------------------------------------------

    /**
     * Writes the set where the map's other derived data lives, gzipped.
     * <p>
     * The marks are written whole rather than as a list of what is set. A list is smaller only
     * while the set is sparse, and this one is not expected to be; the marks are also the form
     * every question about the set wants to be asked in.
     */
    public static void write(Path file, Reachable r) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".partial");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(tmp), 1 << 16)))) {
            out.writeInt(FORMAT);
            out.writeInt(r.liveCount());
            out.writeLong(r.start());
            out.writeLong(r.count());
            for (long word : r.bits()) out.writeLong(word);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static Reachable read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16)))) {
            if (in.readInt() != FORMAT) throw new IOException("wrong format");
            int liveCount = in.readInt();
            long start = in.readLong(), count = in.readLong();
            long pairs = (long) liveCount * liveCount;
            long[] bits = new long[(int) ((pairs + 63) >>> 6)];
            for (int i = 0; i < bits.length; i++) bits[i] = in.readLong();
            return new Reachable(liveCount, bits, count, start);
        }
    }
}
