package boids;

import java.awt.image.BufferedImage;
import java.io.IOException;

public interface Renderer {
    BufferedImage render(Sim.State state) throws IOException;
}
