package boids;

/**
 * An exhaustive set of override choices, enumerated rather than sampled.
 * <p>
 * A random search finds a good line often; an enumerated one finds the best line in its
 * vocabulary every time. That difference matters when a case is meant to rest on a clue
 * that only holds when the psyboid played well — a missed line becomes a false positive,
 * and the only clean way to avoid arguing about missed lines is to make missing one
 * impossible.
 * <p>
 * The delay range is exactly the commit interval, not the interval plus the duration.
 * Overrides from consecutive commits may therefore overlap, which is the lesser evil: a
 * delay range shorter than the interval would leave ticks no commit could ever act on.
 *
 * @param interval      ticks between commits; delays enumerate {@code [0, interval)}
 * @param delayStep     spacing between candidate onsets
 * @param directions    turn directions to enumerate, from {-1, 0, +1}
 * @param durationLo    shortest override, in ticks, inclusive
 * @param durationHi    longest override, in ticks, inclusive
 * @param durationStep  spacing between candidate durations
 * @param keepOriginal  whether to include a branch that does nothing this interval
 */
public record OverridePlan(int interval, int delayStep, int[] directions,
                           int durationLo, int durationHi, int durationStep,
                           boolean keepOriginal) {

    public OverridePlan {
        if (interval < 1 || delayStep < 1 || interval % delayStep != 0) {
            throw new IllegalArgumentException("delayStep must divide interval");
        }
        if (directions.length == 0) throw new IllegalArgumentException("no directions");
        for (int d : directions) {
            if (d < -1 || d > 1) throw new IllegalArgumentException("direction out of range: " + d);
        }
        if (durationLo < 1 || durationHi < durationLo || durationStep < 1) {
            throw new IllegalArgumentException("bad duration range");
        }
        if ((durationHi - durationLo) % durationStep != 0) {
            throw new IllegalArgumentException("durationStep must divide the duration range");
        }
    }

    public int delays() { return interval / delayStep; }

    public int durations() { return (durationHi - durationLo) / durationStep + 1; }

    /** How many branches this plan produces — the branch factor the tree must be built at. */
    public int size() {
        return delays() * directions.length * durations() + (keepOriginal ? 1 : 0);
    }

    /**
     * Every choice available at {@code baseTick}, in a fixed order.
     * <p>
     * The do-nothing branch is a zero-length override rather than an empty array, so it
     * still appears in the label. A branch that leaves no trace in the canonical line
     * would make the line ambiguous about what was actually decided.
     */
    public PsyboidOverride[][] branches(int baseTick, int psyboid) {
        PsyboidOverride[][] out = new PsyboidOverride[size()][];
        int k = 0;
        for (int d = 0; d < delays(); d++) {
            for (int direction : directions) {
                for (int u = 0; u < durations(); u++) {
                    out[k++] = new PsyboidOverride[]{new PsyboidOverride(
                            baseTick + d * delayStep,
                            durationLo + u * durationStep,
                            direction, psyboid)};
                }
            }
        }
        if (keepOriginal) {
            out[k] = new PsyboidOverride[]{new PsyboidOverride(baseTick, 0, 0, psyboid)};
        }
        return out;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(delays()).append(" delays x ").append(directions.length)
                .append(" direction(s) x ").append(durations()).append(" duration(s)");
        if (keepOriginal) sb.append(" + no-op");
        sb.append(" = ").append(size());
        return sb.toString();
    }
}
