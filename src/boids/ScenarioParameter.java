package boids;

import java.nio.file.Path;

public interface ScenarioParameter {
    Path mapPath();
    float turningRadius();
    int flockSize();
}
