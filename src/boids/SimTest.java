package boids;

import java.io.IOException;
import java.nio.file.Path;

/**
 * One-off runs against {@link Sim}. Nothing here is part of the simulation; this is
 * where a specific question gets asked and its frames get written.
 */
public final class SimTest {

    public static void main(String[] args) throws IOException {
        Sim sim = new Sim(PresetScenarioParameter.PLINKO);
        sim.startSeeds(0,1,2,3,4);
        for (int s = 0; s < 50; s+=5) {
            sim.stepTo(s);
            sim.logAll();
        }
        sim.print(Path.of("render","Hamburger_Init","stitched.png"));
    }

}
