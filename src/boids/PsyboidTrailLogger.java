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
 * Draws the path the psyboid took, over the play area it took it on.
 * <p>
 * Knows nothing about the simulation beyond two probes: where the play area image is,
 * and where the psyboid is. It collects the second at every tick and writes the picture
 * when the run ends.
 */
public final class PsyboidTrailLogger implements SimObserver {

    /** Triggers this logger needs; pass to {@link Sim#register}. */
    public static final Trigger[] TRIGGERS = {
            Trigger.SIM_START, Trigger.STATE_INIT, Trigger.STATE_ADVANCE, Trigger.SIM_END
    };

    private static final Color EARLY = new Color(0x20, 0x40, 0xFF);
    private static final Color LATE = new Color(0xFF, 0x30, 0x10);

    private final Path out;
    private final int scale;

    private BufferedImage background;
    private final List<double[]> trail = new ArrayList<>();
    private int psyboid = -1;

    public PsyboidTrailLogger(Path out, int scale) {
        this.out = out;
        this.scale = scale;
    }

    /** Which boid the trail belongs to, once a state has revealed it. */
    public int psyboid() { return psyboid; }

    @java.lang.Override
    public void observe(Trigger trigger, Observation observation) {
        switch (trigger) {
            case SIM_START -> background = load(observation.get(Observation.BACKGROUND));
            case STATE_INIT, STATE_ADVANCE -> {
                Observation.Psyboid p = observation.get(Observation.PSYBOID);
                if (p == null) return;      // no override yet, so no psyboid to follow
                psyboid = p.index();
                trail.add(new double[]{p.x(), p.y()});
            }
            case SIM_END -> flush();
            default -> { }
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

    /**
     * The trail is drawn as a run of short segments rather than one path, so each can
     * carry its own colour — early blue through late red, which is what makes the
     * direction of travel and any doubling back readable.
     */
    private void flush() {
        if (background == null || trail.size() < 2) return;

        int w = background.getWidth() * scale;
        int h = background.getHeight() * scale;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        g.drawImage(background, 0, 0, w, h, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(Math.max(1f, scale * 0.6f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (int i = 1; i < trail.size(); i++) {
            g.setColor(blend(EARLY, LATE, (i - 1) / (double) (trail.size() - 2)));
            double[] a = trail.get(i - 1);
            double[] b = trail.get(i);
            g.drawLine((int) Math.round(a[0] * scale), (int) Math.round(a[1] * scale),
                    (int) Math.round(b[0] * scale), (int) Math.round(b[1] * scale));
        }

        mark(g, trail.get(0), Color.WHITE);
        mark(g, trail.get(trail.size() - 1), Color.BLACK);
        g.dispose();

        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            ImageIO.write(image, "png", out.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void mark(Graphics2D g, double[] at, Color color) {
        int r = Math.max(2, scale);
        g.setColor(color);
        g.fillOval((int) Math.round(at[0] * scale) - r, (int) Math.round(at[1] * scale) - r,
                2 * r, 2 * r);
    }

    private static Color blend(Color from, Color to, double t) {
        return new Color(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }
}
