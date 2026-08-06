package boids;

import java.awt.image.BufferedImage;

public interface Engine {
    Sim.State tick(Sim.State state);
    Sim.State init(long seed);
    BufferedImage print(Sim.State state);
}
