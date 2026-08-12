package boids;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the path every boid took, over the play area it took it on.
 * <p>
 * Same two probes as the psyboid version, except it asks for the whole flock rather
 * than one member. Colours match {@link Boids2DRenderer#colorOf}, so a trail can be
 * matched against a frame from the ordinary renderer.
 */
public final class FlockTrailLogger implements SimObserver {

    public static final Trigger[] TRIGGERS = {
            Trigger.SIM_START, Trigger.STATE_INIT, Trigger.STATE_ADVANCE, Trigger.SIM_END
    };

    private final Path out;
    private final int scale;
    private final float width;

    private BufferedImage background;
    private final List<List<int[]>> trails = new ArrayList<>();

    /** Which trail belongs to the psyboid, if an override reveals one. */
    private int psyboid = -1;

    public FlockTrailLogger(Path out, int scale, float width) {
        this.out = out;
        this.scale = scale;
        this.width = width;
    }

    @java.lang.Override
    public void observe(Trigger trigger, Observation observation) {
        switch (trigger) {
            case SIM_START -> background = load(observation.get(Observation.BACKGROUND));
            case STATE_INIT, STATE_ADVANCE -> {
                Observation.Flock flock = observation.get(Observation.FLOCK);
                while (trails.size() < flock.n()) trails.add(new ArrayList<>());
                for (int i = 0; i < flock.n(); i++) {
                    trails.get(i).add(new int[]{flock.x()[i], flock.y()[i]});
                }
                Observation.Psyboid steered = observation.get(Observation.PSYBOID);
                if (steered != null) psyboid = steered.index();
            }
            case SIM_END -> flush();
            default -> { }
        }
    }

    private void draw(Graphics2D g, List<int[]> trail) {
        for (int k = 1; k < trail.size(); k++) {
            int[] a = trail.get(k - 1);
            int[] b = trail.get(k);
            g.drawLine(a[0] * scale, a[1] * scale, b[0] * scale, b[1] * scale);
        }
    }

    private static BufferedImage load(Path source) {
        try {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) throw new IOException("not a readable image: " + source);
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void flush() {
        if (background == null || trails.isEmpty()) return;

        int w = background.getWidth() * scale;
        int h = background.getHeight() * scale;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        g.drawImage(background, 0, 0, w, h, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Ordinary boids first, then the psyboid heavier and on top, so its path can be
        // followed through the tangle rather than lost in it.
        int n = trails.size();
        for (int i = 0; i < n; i++) {
            if (i == psyboid) continue;
            g.setColor(Boids2DRenderer.colorOf(i, n));
            draw(g, trails.get(i));
        }
        if (psyboid >= 0 && psyboid < n) {
            g.setStroke(new BasicStroke(width * 3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.BLACK);
            draw(g, trails.get(psyboid));
            g.setStroke(new BasicStroke(width * 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Boids2DRenderer.colorOf(psyboid, n));
            draw(g, trails.get(psyboid));
        }

        // Where each one finished, so a reader can tell heads from tails.
        for (int i = 0; i < n; i++) {
            List<int[]> trail = trails.get(i);
            if (trail.isEmpty()) continue;
            int[] last = trail.get(trail.size() - 1);
            int r = Math.max(2, scale);
            g.setColor(Color.BLACK);
            g.fillOval(last[0] * scale - r, last[1] * scale - r, 2 * r, 2 * r);
            g.setColor(Boids2DRenderer.colorOf(i, n));
            g.fillOval(last[0] * scale - r + 1, last[1] * scale - r + 1, 2 * r - 2, 2 * r - 2);
        }
        g.dispose();

        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            ImageIO.write(image, "png", out.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
