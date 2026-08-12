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
 * Snapshots the flock at a fixed interval and stitches the frames into a grid.
 * <p>
 * A trail says where boids went overall; a grid says <em>when</em>. It answers questions
 * a trail cannot — whether a formation was reached early and held, or repeatedly lost
 * and rebuilt.
 */
public final class FrameGridLogger implements SimObserver {

    public static final Trigger[] TRIGGERS = {
            Trigger.SIM_START, Trigger.STATE_INIT, Trigger.STATE_ADVANCE, Trigger.SIM_END
    };

    private final Path out;
    private final int interval;
    private final int columns;
    private final int scale;

    private BufferedImage background;
    private final List<BufferedImage> frames = new ArrayList<>();
    private long firstTick = Long.MIN_VALUE;
    private int psyboid = -1;

    public FrameGridLogger(Path out, int interval, int columns, int scale) {
        this.out = out;
        this.interval = interval;
        this.columns = columns;
        this.scale = scale;
    }

    @java.lang.Override
    public void observe(Trigger trigger, Observation observation) {
        switch (trigger) {
            case SIM_START -> background = load(observation.get(Observation.BACKGROUND));
            case STATE_INIT, STATE_ADVANCE -> {
                Observation.Psyboid steered = observation.get(Observation.PSYBOID);
                if (steered != null) psyboid = steered.index();

                Observation.Flock flock = observation.get(Observation.FLOCK);
                if (firstTick == Long.MIN_VALUE) firstTick = flock.tick();
                if ((flock.tick() - firstTick) % interval == 0) frames.add(frame(flock));
            }
            case SIM_END -> flush();
            default -> { }
        }
    }

    /**
     * Boids are drawn as discs rather than the usual oriented triangles: at grid scale a
     * triangle's heading is not legible anyway, and a disc reads more clearly against a
     * busy background. The psyboid gets a ring so it can be picked out at a glance.
     */
    private BufferedImage frame(Observation.Flock flock) {
        int w = background.getWidth() * scale;
        int h = background.getHeight() * scale;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        g.drawImage(background, 0, 0, w, h, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int r = Math.max(3, 4 * scale);
        for (int i = 0; i < flock.n(); i++) {
            int px = flock.x()[i] * scale;
            int py = flock.y()[i] * scale;
            g.setColor(Boids2DRenderer.colorOf(i, flock.n()));
            g.fillOval(px - r, py - r, 2 * r, 2 * r);
            if (i == psyboid) {
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(Math.max(1.5f, scale)));
                g.drawOval(px - r - 2, py - r - 2, 2 * r + 4, 2 * r + 4);
            }
        }
        g.dispose();
        return image;
    }

    private void flush() {
        if (frames.isEmpty()) return;

        List<BufferedImage> rows = new ArrayList<>();
        for (int i = 0; i < frames.size(); i += columns) {
            rows.add(Sim.stitchHorizontal(frames.subList(i, Math.min(i + columns, frames.size()))));
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
