package boids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Hand-built state graphs fed to the real {@link EdgeDecomposition#refine}, to see what the axiom
 * forces at a junction without any map geometry in the way.
 * <p>
 * <b>Why it exists.</b> Edge insertion settles on binary splits and merges and blows up wherever
 * three edges meet. The hypothesis on 2026-09-12 was that a one-to-three branch should need at
 * most thirteen edges — {@code O}, the three pairs, and three pieces per outcome — and that
 * anything past that was a defect in {@code refine}. These graphs are the test. {@code FAN} is
 * the thirteen, exactly; {@code PLAZA} is the same branch with two <em>menus</em> in its
 * three-open layer, and the axiom splits the layer. That is <b>braiding</b>, canonical in
 * {@code EDGES.md} §2a, and it is the axiom's own behaviour rather than a bug: the axiom groups
 * by the set of next edges, not by the set of outcomes still open. {@code BINARY} is the control —
 * with two outcomes there is only one possible menu, so the same shape cannot braid.
 * <p>
 * Each graph is a dozen states. Successor and predecessor degree are capped at three, as on a
 * map. Run {@link #main} and read the pieces; nothing is written to disk.
 */
public final class RefineToy {
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> id = new HashMap<>();
    private final Map<String, String> label = new LinkedHashMap<>();
    private final List<int[]> arcs = new ArrayList<>();

    private RefineToy() {}

    /** Declares a state on a coarse edge. */
    private RefineToy s(String name, String edge) {
        if (!id.containsKey(name)) { id.put(name, names.size()); names.add(name); }
        label.put(name, edge);
        return this;
    }

    private RefineToy arc(String from, String... tos) {
        for (String t : tos) arcs.add(new int[]{id.get(from), id.get(t)});
        return this;
    }

    /** Refines from the coarse labels and prints every piece with its next and previous edges. */
    private void run(String title) {
        int n = names.size();
        int[] live = new int[n];
        for (int i = 0; i < n; i++) live[i] = i;
        int[] succ = new int[n * 3], pred = new int[n * 3];
        byte[] degree = new byte[n], predDegree = new byte[n];
        for (int[] a : arcs) {
            if (degree[a[0]] == 3 || predDegree[a[1]] == 3) {
                throw new IllegalStateException("degree > 3 at " + names.get(a[0]) + " -> " + names.get(a[1]));
            }
            succ[a[0] * 3 + degree[a[0]]++] = a[1];
            pred[a[1] * 3 + predDegree[a[1]]++] = a[0];
        }
        List<String> coarse = new ArrayList<>(new LinkedHashSet<>(label.values()));
        int[] edge = new int[n];
        for (int i = 0; i < n; i++) edge[i] = coarse.indexOf(label.get(names.get(i)));

        System.out.printf("%n=== %s: %d states, coarse edges %s ===%n", title, n, coarse);
        int after = EdgeDecomposition.refine(live, n, succ, degree, pred, predDegree, edge, coarse.size());
        Map<Integer, List<String>> pieces = new TreeMap<>();
        for (int i = 0; i < n; i++) pieces.computeIfAbsent(edge[i], k -> new ArrayList<>()).add(names.get(i));
        System.out.printf("fixed point: %d edges%n", after);
        for (var p : pieces.entrySet()) {
            Set<Integer> out = new TreeSet<>(), in = new TreeSet<>();
            for (String nm : p.getValue()) {
                int v = id.get(nm);
                for (int j = 0; j < degree[v]; j++) if (edge[succ[v * 3 + j]] != edge[v]) out.add(edge[succ[v * 3 + j]]);
                for (int j = 0; j < predDegree[v]; j++) if (edge[pred[v * 3 + j]] != edge[v]) in.add(edge[pred[v * 3 + j]]);
            }
            System.out.printf("  %2d (%s) %-26s <- %-12s -> %s%n", p.getKey(),
                    label.get(p.getValue().get(0)), p.getValue(), in, out);
        }
    }

    /** The committed tails and continuations shared by the one-to-three graphs. */
    private static RefineToy outcomes(RefineToy t) {
        return t.s("A", "A").s("A2", "A").s("B", "B").s("B2", "B").s("C", "C").s("C2", "C")
                .arc("A", "A2").arc("B", "B2").arc("C", "C2");
    }

    public static void main(String[] args) {
        // FAN: one three-open state feeding all three pairs; each pair feeds a tail per outcome;
        // tails merge pairwise. Settles at thirteen pieces of O — the count predicted by hand.
        RefineToy t = new RefineToy()
                .s("w", "W").s("o", "O").s("ab", "O").s("ac", "O").s("bc", "O")
                .s("a1", "O").s("a2", "O").s("a3", "O")
                .s("b1", "O").s("b2", "O").s("b3", "O")
                .s("c1", "O").s("c2", "O").s("c3", "O");
        outcomes(t).arc("w", "o").arc("o", "ab", "ac", "bc")
                .arc("ab", "a1", "b1").arc("ac", "a2", "c1").arc("bc", "b2", "c2")
                .arc("a1", "a3").arc("a2", "a3").arc("b1", "b3").arc("b2", "b3").arc("c1", "c3").arc("c2", "c3")
                .arc("a3", "A").arc("b3", "B").arc("c3", "C")
                .run("FAN  {O}->{A,B,C}, one menu in the three-open layer");

        // PLAZA: under one entrance, o2 can peel C ({AB, C}) and o3 can peel A ({A, BC}). Same
        // outcomes open at both; different menus; the layer splits in three. Eight pieces of O.
        t = new RefineToy()
                .s("w", "W").s("o1", "O").s("o2", "O").s("o3", "O")
                .s("ab", "O").s("bc", "O").s("a", "O").s("b", "O").s("c", "O");
        outcomes(t).arc("w", "o1").arc("o1", "o2", "o3")
                .arc("o2", "ab", "c").arc("o3", "a", "bc")
                .arc("ab", "a", "b").arc("bc", "b", "c")
                .arc("a", "A").arc("b", "B").arc("c", "C")
                .run("PLAZA  {O}->{A,B,C}, two menus in the three-open layer");

        // BINARY control: the PLAZA shape with two outcomes. Only one menu is possible, so the
        // layer stays one edge whatever its internal shape. Three pieces of O.
        t = new RefineToy()
                .s("w", "W").s("o1", "O").s("o2", "O").s("o3", "O")
                .s("a", "O").s("b", "O").s("a'", "O").s("b'", "O")
                .s("A", "A").s("A2", "A").s("B", "B").s("B2", "B");
        t.arc("w", "o1").arc("o1", "o2", "o3").arc("o2", "a", "b").arc("o3", "a'", "b'")
                .arc("a", "A").arc("a'", "A").arc("b", "B").arc("b'", "B").arc("A", "A2").arc("B", "B2")
                .run("BINARY  {O}->{A,B}, the PLAZA shape with two outcomes");

        // TRIDENT: a direct commit O -> A alongside the pair-pieces. A gains a fourth entrance
        // (via O), and the three-open layer splits because o and o' have different menus.
        t = new RefineToy()
                .s("w", "W").s("o", "O").s("o'", "O").s("ab", "O").s("bc", "O").s("a0", "O").s("c0", "O")
                .s("a1", "O").s("b1", "O").s("b2", "O").s("c2", "O").s("a3", "O").s("b3", "O").s("c3", "O");
        outcomes(t).arc("w", "o").arc("o", "a0", "o'").arc("o'", "ab", "bc", "c0")
                .arc("ab", "a1", "b1").arc("bc", "b2", "c2")
                .arc("a0", "a3").arc("a1", "a3").arc("b1", "b3").arc("b2", "b3").arc("c0", "c3").arc("c2", "c3")
                .arc("a3", "A").arc("b3", "B").arc("c3", "C")
                .run("TRIDENT  {O}->{A,B,C}, a direct commit beside the pairs");

        // Two edges into two, meeting only in the committed layer: each source splits three ways
        // and each outcome is one merge. Ten edges in all.
        t = new RefineToy()
                .s("wx", "WX").s("wy", "WY").s("x", "X").s("y", "Y")
                .s("xa", "X").s("xb", "X").s("ya", "Y").s("yb", "Y")
                .s("a", "A").s("A2", "A").s("b", "B").s("B2", "B");
        t.arc("wx", "x").arc("wy", "y").arc("x", "xa", "xb").arc("y", "ya", "yb")
                .arc("xa", "a").arc("ya", "a").arc("xb", "b").arc("yb", "b").arc("a", "A2").arc("b", "B2")
                .run("XY-FAN  {X,Y}->{A,B}, meeting in the committed layer");

        // The same with a still-open region j reachable from both X and Y: j is its own edge with
        // its own two tails, and each outcome has three ways in. Thirteen edges.
        t = new RefineToy()
                .s("wx", "WX").s("wy", "WY").s("x", "X").s("y", "Y").s("j", "X")
                .s("xa", "X").s("xb", "X").s("ya", "Y").s("yb", "Y").s("ja", "X").s("jb", "X")
                .s("a", "A").s("A2", "A").s("b", "B").s("B2", "B");
        t.arc("wx", "x").arc("wy", "y").arc("x", "xa", "xb", "j").arc("y", "ya", "yb", "j").arc("j", "ja", "jb")
                .arc("xa", "a").arc("ya", "a").arc("ja", "a").arc("xb", "b").arc("yb", "b").arc("jb", "b")
                .arc("a", "A2").arc("b", "B2")
                .run("XY-MERGE  {X,Y}->{A,B}, with a still-open merge region");
    }
}
