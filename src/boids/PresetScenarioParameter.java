package boids;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.Override;
import java.nio.file.Path;

public enum PresetScenarioParameter implements ScenarioParameter{
    HAMBURGER ("hamburger.png", 20f, 20),
    PEANUT ("peanut.png", 40f, 20),
    PLINKO ("plinko.png", 40f, 20),
    WORMWAY ("wormway.png", 40f, 20),
    ROUNDABOUT ("roundabout.png", 40f, 20),
    OUTLOOPED ("outlooped.png", 40f, 20),
    DAB ("dab.png", 40f, 4),
    BERT ("bert.png", 40f, 4),
    DABNT ("dabnt.png", 40f, 4),
    DABEONE ("dabeone.png", 40f, 4),
    PLAIT ("plait.png", 40f, 4),
    DAISY ("daisy.png", 40f, 10),
    BLOSSOM ("blossom.png", 40f, 10),

    /**
     * A map for testing the decomposition itself, not for flying flocks on.
     * <p>
     * 379x407 at radius 40, the dab family's geometry, drawn so that edges are long and plainly
     * separated — which is what makes it useful for checking that a construction over edges
     * behaves the way its derivation says it should.
     */
    EDGE_TEST ("edge_test.png", 40f, 4);

    private final String filename;
    private final float r;
    private final int n;

    PresetScenarioParameter(String filename, float r, int n) {
        this.filename = filename;
        this.r = r;
        this.n = n;
    }

    /** The editable PNG's filename. Use {@link #mapPath()} to actually read a map. */
    public String sourceName() {
        return filename;
    }

    /** The frozen map for this preset's current content — never the mutable source. */
    @Override
    public Path mapPath() {
        return ingest().png();
    }

    /** Where output derived from this map belongs, so it cannot outlive its map. */
    public MapStore.Ingest ingest() {
        try {
            return MapStore.open(filename.substring(0, filename.length() - 4),
                    MapStore.Build.of(Math.round(r)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open map for " + name(), e);
        }
    }

    @Override
    public float turningRadius() {
        return r;
    }

    @Override
    public int flockSize() {
        return n;
    }
}
