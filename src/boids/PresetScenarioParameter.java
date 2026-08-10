package boids;

import java.lang.Override;
import java.nio.file.Path;

public enum PresetScenarioParameter implements ScenarioParameter{
    HAMBURGER ("hamburger.png", 20f, 20),
    PEANUT ("peanut.png", 40f, 20),
    PLINKO ("plinko.png", 40f, 20),
    WORMWAY ("wormway.png", 40f, 20),
    ROUNDABOUT ("roundabout.png", 40f, 20),
    OUTLOOPED ("outlooped.png", 40f, 20);

    private final String filename;
    private final float r;
    private final int n;

    PresetScenarioParameter(String filename, float r, int n) {
        this.filename = filename;
        this.r = r;
        this.n = n;
    }

    @Override
    public Path mapPath() {
        return Path.of("areas", filename);
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
