package boids;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Draws the arrangements a solver's answer turned on, side by side.
 * <p>
 * A solver that gets a case wrong cannot be debugged from its output, and it cannot usefully
 * be debugged from a table of a hundred cases either — the aggregate says how often something
 * is wrong and never says what. What is wanted is the two pictures the argument actually
 * rests on: where the flock is now, and where it was at the moment the boid that needs
 * explaining first crossed onto the edge it cannot hold. The first is the evidence. The second
 * is the event, and it is where any leader that could account for it had to be standing.
 * <p>
 * Everything is drawn at whole-map scale rather than cropped to a flocking radius, because the
 * question here is about routes and distances along them rather than about who could see whom.
 * Boids are coloured by the edge they are on, so the same colour means the same edge across
 * every panel and across the decomposition render.
 */
public final class SceneRender {
    private SceneRender() {}

    /**
     * One colour per edge, shared with the decomposition graph so that an edge looks the same
     * everywhere it is drawn. Wraps for maps with more edges than colours.
     */
    public static final int[] EDGE_PALETTE = {
            0xE6194B, 0x3CB44B, 0x4363D8, 0xFFE119, 0xF58231,
            0x911EB4, 0x46F0F0, 0xF032E6, 0xBCF60C, 0x008080,
    };

    private static final int SHADE = 0x0E1116;
    private static final int TEXT = 0xF0F3F8;
    private static final int FAINT = 0x8A93A3;
    private static final int MARK = 0xFFFFFF;
    private static final int SEP_RING = 0xE6194B;
    private static final int FLOCK_RING = 0x4363D8;

    /**
     * @param caption  what this moment is, in a few words
     * @param mark     the boid the panel is about; ringed and outlined, or -1 for none
     */
    public record Panel(String caption, Sim.State state, int mark) {}

    /**
     * @param title    the case, in one line: which map, seed, psyboid, verdict
     * @param note     what went wrong, or what to look at
     */
    public static void write(Path background, Path out, SolverFacts f, String title, String note,
                             List<Panel> panels, double turningRadius, int scale)
            throws IOException {
        BufferedImage map = ImageIO.read(background.toFile());
        int w = map.getWidth() * scale, h = map.getHeight() * scale;
        int head = 52, foot = 18 * (panels.isEmpty() ? 1 : panels.getFirst().state().n) + 40;
        int gap = 12;

        BufferedImage img = new BufferedImage(panels.size() * (w + gap) + gap,
                head + h + foot, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(SHADE));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString(title, gap, 22);
        g.setColor(new Color(FAINT));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString(note, gap, 42);

        for (int p = 0; p < panels.size(); p++) {
            panel(g, map, f, panels.get(p), gap + p * (w + gap), head, scale, turningRadius,
                    h, w);
        }
        g.dispose();

        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
    }

    private static void panel(Graphics2D g, BufferedImage map, SolverFacts f, Panel panel,
                              int ox, int oy, int scale, double turningRadius, int h, int w) {
        for (int py = 0; py < map.getHeight(); py++) {
            for (int px = 0; px < map.getWidth(); px++) {
                int rgb = map.getRGB(px, py) & 0xFFFFFF;
                int dim = ((rgb >> 16 & 255) * 40 / 100) << 16
                        | ((rgb >> 8 & 255) * 40 / 100) << 8 | (rgb & 255) * 40 / 100;
                g.setColor(new Color(dim));
                g.fillRect(ox + px * scale, oy + py * scale, scale, scale);
            }
        }
        g.setColor(new Color(0x2A303A));
        g.drawRect(ox, oy, w, h);

        Sim.State s = panel.state();
        if (panel.mark() >= 0) {
            int cx = ox + s.x[panel.mark()] * scale, cy = oy + s.y[panel.mark()] * scale;
            g.setStroke(new BasicStroke(1f));
            ring(g, cx, cy, Params.separation(turningRadius) * scale, SEP_RING);
            ring(g, cx, cy, Params.flock(turningRadius) * scale, FLOCK_RING);
        }
        for (int i = 0; i < s.n; i++) {
            int e = f.edgeAt(s.x[i], s.y[i], s.h[i]);
            boid(g, ox + s.x[i] * scale, oy + s.y[i] * scale, s.h[i], colour(e), scale,
                    String.valueOf(i), i == panel.mark());
        }

        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString(panel.caption(), ox, oy + h + 18);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int i = 0; i < s.n; i++) {
            int e = f.edgeAt(s.x[i], s.y[i], s.h[i]);
            g.setColor(new Color(colour(e)));
            g.fillRect(ox, oy + h + 30 + i * 16, 9, 9);
            g.setColor(new Color(i == panel.mark() ? MARK : FAINT));
            g.drawString(String.format("boid %d   edge %d%s   tick %6.1f   (%d, %d, d%d)", i, e,
                            e >= 0 && !f.stable(e) ? "*" : " ", f.tickAt(s.x[i], s.y[i], s.h[i]),
                            s.x[i], s.y[i], s.h[i]),
                    ox + 16, oy + h + 39 + i * 16);
        }
    }

    private static int colour(int edge) {
        return edge < 0 ? 0x707070 : EDGE_PALETTE[edge % EDGE_PALETTE.length];
    }

    private static void ring(Graphics2D g, int cx, int cy, double r, int rgb) {
        g.setColor(new Color(rgb));
        g.drawOval(cx - (int) r, cy - (int) r, (int) r * 2, (int) r * 2);
    }

    private static void boid(Graphics2D g, int cx, int cy, int heading, int rgb, int scale,
                             String label, boolean marked) {
        int reach = 9 * scale;
        g.setStroke(new BasicStroke(Math.max(2, scale / 1.5f)));
        if (marked) {
            g.setColor(new Color(MARK));
            g.fillOval(cx - scale * 3, cy - scale * 3, scale * 6, scale * 6);
        }
        g.setColor(new Color(rgb));
        g.drawLine(cx, cy, (int) (cx + Params.COS[heading] * reach),
                (int) (cy + Params.SIN[heading] * reach));
        int dot = Math.max(3, scale * 2);
        g.fillOval(cx - dot, cy - dot, dot * 2, dot * 2);
        g.setColor(new Color(marked ? MARK : TEXT));
        g.setFont(new Font("SansSerif", marked ? Font.BOLD : Font.PLAIN, 12));
        g.drawString(label, cx + dot + 3, cy - dot - 2);
    }
}
