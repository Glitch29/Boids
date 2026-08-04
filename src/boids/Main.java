package boids;

import java.io.IOException;
import java.nio.file.Path;

/** Runs one simulation and writes a PNG at each of a few ticks. */
public final class Main {

    private static final int SCALE = 2;
    private static final long DEFAULT_SEED = 1234L;
    private static final int[] FRAMES = {0, 50, 100, 200, 400, 800, 1600};

    public static void main(String[] args) throws IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_SEED;
        // Not out/ — that belongs to the IDE's compiler output and is marked excluded.
        Path out = Path.of("render", "seed" + seed);

        Sim sim = new Sim(Params.N_BOIDS, seed);

        System.out.printf("arena %.0f x %.0f | %d boids | speed %.3f | turn radius %.0f | seed %d%n",
                Params.W, Params.H, sim.n, Params.SPEED, Params.R_TURN, seed);

        int prev = 0;
        for (int t : FRAMES) {
            sim.advance(t - prev);
            prev = t;
            Path p = out.resolve(String.format("tick_%05d.png", t));
            Render.writePng(sim, SCALE, p);
            System.out.printf("t=%-5d  boid length %5.1f  ->  %s%n", t, Render.boidLength(sim), p);
        }
    }
}
