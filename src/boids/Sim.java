package boids;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The simulation engine: it owns the play area's navigation and knows how to advance a
 * {@link State}. It holds nothing that changes as a run proceeds, so one instance can
 * drive any number of independent timelines concurrently.
 * <p>
 * Leaving the play area carries no special handling — a boid out of bounds is steered
 * by the navigation map like any other, and simply flies on if it is beyond the map's
 * reach. Leaving the <em>image</em> is fatal and throws.
 */
public final class Sim {

    private final Engine engine;
    private List<State> states = new ArrayList<>();
    private final List<List<State>> printGrid = new ArrayList<>();
    private final Renderer renderer;

    /**
     * @param playArea PNG where {@code #000000} is out of bounds
     * @param navRadius the turning radius the navigation map is built at. Normally
     *                  {@link Params#R_TURN}, but building at a larger radius is the
     *                  knob for making boids commit to their turns earlier than they
     *                  strictly must.
     */
    public Sim(ScenarioParameter parameter) throws IOException {
        engine = new Boids2DEngine(parameter);
        renderer = new Boids2DRenderer(parameter.mapPath());
    }

    public void startSeeds(long... seeds) {
        for (long seed : seeds) {
            states.add(engine.init(seed));
        }
    }

    /**
     * Advance until the state reaches {@code targetTick}. Returns the argument
     * unchanged if it is already there.
     */
    public void stepTo(long targetTick) {
        List<State> result = new ArrayList<>();
        for (State s : states) {
            while (s.tick < targetTick) s = engine.tick(s);
            result.add(s);
        }
        states = result;
    }

    public void logAll() {
        printGrid.add(new ArrayList<>(states));
    }

    public void print(Path out) throws IOException {
        List<BufferedImage> rows = new ArrayList<>();
        for (List<State> states : printGrid) {
            List<BufferedImage> images = new ArrayList<>();
            for (State state : states) {
                images.add(renderer.render(state));
            }
            rows.add(stitchHorizontal(images));
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        writePng(out,stitchVertical(rows));
    }
    private static void writePng(Path out, BufferedImage image) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
    }

    private static BufferedImage stitchHorizontal(List<BufferedImage> images) {
        BufferedImage result;
        {
            int w = 0;
            int h = 0;
            for (BufferedImage image : images) {
                w += image.getWidth();
                h = Math.max(h, image.getHeight());
            }
            result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = result.createGraphics();
        int x = 0;
        for (BufferedImage image : images) {
            g.drawImage(image,x,0,null);
            x += image.getWidth();
        }
        g.dispose();
        return result;
    }

    private static BufferedImage stitchVertical(List<BufferedImage> images) {
        BufferedImage result;
        {
            int w = 0;
            int h = 0;
            for (BufferedImage image : images) {
                w = Math.max(w, image.getWidth());
                h += image.getHeight();
            }
            result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = result.createGraphics();
        int y = 0;
        for (BufferedImage image : images) {
            g.drawImage(image,0,y,null);
            y += image.getHeight();
        }
        g.dispose();
        return result;
    }

    /**
     * One instant of a timeline: every boid's position and heading, the tick, and the
     * override in force.
     * <p>
     * Treat instances as immutable. The arrays are exposed directly rather than copied,
     * because the decision layer reads them in its inner loop and a copy per access would
     * dominate the cost. Nothing writes to a {@code State} once it has been constructed —
     * {@link Sim#step} allocates fresh arrays and returns a new instance.
     * <p>
     * Because an old state can never be clobbered, the decide and act phases no longer
     * need separating: reading the previous tick is guaranteed safe by construction.
     */
    public static final class State {

        public final int n;
        public final double[] x;
        public final double[] y;
        public final int[] h;
        public final long tick;
        public final long score;
        public final PsyboidOverride[] psyboidOverrides;

        /**
         * Sets every field explicitly. Called by {@link Sim} and by the {@code with...}
         * methods below; there is no other way to build a state.
         */
        State(int n, double[] x, double[] y, int[] h, long tick, long score, PsyboidOverride... psyboidOverrides) {
            this.n = n;
            this.x = x;
            this.y = y;
            this.h = h;
            this.tick = tick;
            this.score = score;
            this.psyboidOverrides = psyboidOverrides;
        }
    }
}
