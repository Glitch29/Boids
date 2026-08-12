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
 * Draws the flock's path a window at a time and stitches the windows into a grid.
 * <p>
 * A single accumulated trail answers where the boids went; a frame grid answers where they
 * were at an instant. Neither answers how the route itself changed, because the first
 * collapses the whole run onto one image and the second throws the route away. Each panel
 * here holds only its own window, so a loop that tightens, migrates or breaks up is visible
 * as a difference between panels.
 * <p>
 * Windows are joined rather than merely adjacent: the last point of one panel opens the
 * next, so a path followed across the sequence has no gaps at the seams.
 */
public final class TrailGridLogger implements SimObserver {

    public static final Trigger[] TRIGGERS = {
            Trigger.SIM_START, Trigger.STATE_INIT, Trigger.STATE_ADVANCE, Trigger.SIM_END
    };

    private final Path out;
    private final int interval;
    private final int columns;
    private final int scale;
    private final float width;

    private BufferedImage background;
    private final List<List<int[]>> window = new ArrayList<>();
    private final List<BufferedImage> panels = new ArrayList<>();
    private long firstTick = Long.MIN_VALUE;
    private int psyboid = -1;

    public TrailGridLogger(Path out, int interval, int columns, int scale, float width) {
        this.out = out;
        this.interval = interval;
        this.columns = columns;
        this.scale = scale;
        this.width = width;
    }

    @java.lang.Override
    public void observe(Trigger trigger, Observation observation) {
        switch (trigger) {
            case SIM_START -> background = load(observation.get(Observation.BACKGROUND));
            case STATE_INIT, STATE_ADVANCE -> {
                Observation.Psyboid steered = observation.get(Observation.PSYBOID);
                if (steered != null) psyboid = steered.index();

                Observation.Flock flock = observation.get(Observation.FLOCK);
                while (window.size() < flock.n()) window.add(new ArrayList<>());
                for (int i = 0; i < flock.n(); i++) {
                    window.get(i).add(new int[]{flock.x()[i], flock.y()[i]});
                }

                if (firstTick == Long.MIN_VALUE) firstTick = flock.tick();
                long elapsed = flock.tick() - firstTick;
                if (elapsed > 0 && elapsed % interval == 0) closeWindow();
            }
            case SIM_END -> {
                closeWindow();
                flush();
            }
            default -> { }
        }
    }

    /** Renders what has accumulated, then restarts each trail from its own last point. */
    private void closeWindow() {
        if (window.isEmpty()) return;

        int w = background.getWidth() * scale;
        int h = background.getHeight() * scale;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        g.drawImage(background, 0, 0, w, h, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int n = window.size();
        for (int i = 0; i < n; i++) {
            if (i == psyboid) continue;
            g.setColor(Boids2DRenderer.colorOf(i, n));
            draw(g, window.get(i));
        }
        if (psyboid >= 0 && psyboid < n) {
            g.setStroke(new BasicStroke(width * 3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.BLACK);
            draw(g, window.get(psyboid));
            g.setStroke(new BasicStroke(width * 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Boids2DRenderer.colorOf(psyboid, n));
            draw(g, window.get(psyboid));
        }

        // Where each boid ended the window, so a panel can be read for direction of travel.
        for (int i = 0; i < n; i++) {
            List<int[]> trail = window.get(i);
            if (trail.isEmpty()) continue;
            int[] last = trail.get(trail.size() - 1);
            int r = Math.max(2, scale);
            g.setColor(Color.BLACK);
            g.fillOval(last[0] * scale - r, last[1] * scale - r, 2 * r, 2 * r);
            g.setColor(Boids2DRenderer.colorOf(i, n));
            g.fillOval(last[0] * scale - r + 1, last[1] * scale - r + 1, 2 * r - 2, 2 * r - 2);
        }
        g.dispose();
        panels.add(image);

        for (List<int[]> trail : window) {
            int[] last = trail.isEmpty() ? null : trail.get(trail.size() - 1);
            trail.clear();
            if (last != null) trail.add(last);
        }
    }

    private void draw(Graphics2D g, List<int[]> trail) {
        for (int k = 1; k < trail.size(); k++) {
            int[] a = trail.get(k - 1);
            int[] b = trail.get(k);
            g.drawLine(a[0] * scale, a[1] * scale, b[0] * scale, b[1] * scale);
        }
    }

    private void flush() {
        if (panels.isEmpty()) return;

        List<BufferedImage> rows = new ArrayList<>();
        for (int i = 0; i < panels.size(); i += columns) {
            rows.add(Sim.stitchHorizontal(panels.subList(i, Math.min(i + columns, panels.size()))));
        }
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            ImageIO.write(Sim.stitchVertical(rows), "png", out.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
}
