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

/**
 * Draws the moment an exit nobody can account for happened.
 * <p>
 * A count of unexplained exits says only that the account is incomplete. What is wanted is the
 * arrangement itself: where the suspect was, which way it was pointing, and which neighbours it
 * could see â because the suspicion is that several of them together did what none of them
 * would do alone, and that is a claim about a picture.
 * <p>
 * Cropped to what the suspect could possibly have been reacting to. Nothing outside its
 * flocking radius entered the decision, so drawing the rest of the map would be drawing
 * things that provably did not matter.
 */
public final class ExitRender {
    private ExitRender() {}

    private static final int SUSPECT = 0xE6194B;
    private static final int VISIBLE = 0xFFB000;
    private static final int UNSEEN = 0x5A6272;
    private static final int SEP_RING = 0xE6194B;
    private static final int FLOCK_RING = 0x4363D8;
    private static final int TEXT = 0xF0F3F8;
    private static final int SHADE = 0x0E1116;

    /** Height of the caption band above the arrangement. */
    private static final int HEADER = 74;

    /**
     * Where a map pixel lands in the picture {@link #image} returns.
     * <p>
     * Published so that a caller assembling several of these can annotate the arrangement — put
     * a marker on a particular boid, say — without restating the crop arithmetic. It depends on
     * nothing but the suspect's position, the flocking radius and the scale.
     */
    public record Frame(int left, int top, int scale, int header) {
        public int px(int mapX) { return (mapX - left) * scale + scale / 2; }

        public int py(int mapY) { return (mapY - top) * scale + scale / 2 + header; }
    }

    /** The crop {@link #image} will take for a suspect standing at {@code (x, y)}. */
    public static Frame frame(int x, int y, double turningRadius, int scale) {
        int margin = (int) Math.ceil(Params.flock(turningRadius)) + 24;
        return new Frame(x - margin, y - margin, scale, HEADER);
    }

    /**
     * @param background the play area to draw over; only its pixels are used, so the display
     *                   copy is the right one rather than the frozen physics map
     * @param x          positions as the suspect saw them, mid-tick
     */
    public static void write(Path background, Path out, ExitAudit.Exit e, int[] x, int[] y,
                             int[] h, int n, MovementLogic rules, double turningRadius,
                             int scale) throws IOException {
        BufferedImage img = image(background, e, x, y, h, n, rules, turningRadius, scale);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
    }

    /**
     * The same picture, handed back rather than written.
     * <p>
     * Split out so a caller assembling several of these into one sheet does not have to write
     * each to disk and read it back. {@link #write} is this plus a file.
     */
    public static BufferedImage image(Path background, ExitAudit.Exit e, int[] x, int[] y,
                                      int[] h, int n, MovementLogic rules, double turningRadius,
                                      int scale) throws IOException {
        double rSep = Params.separation(turningRadius);
        double rFlock = Params.flock(turningRadius);
        // The suspect is read out of the arrangement rather than off the exit, because an exit
        // is anchored where the boid crossed while the picture worth drawing is the arrangement
        // handed in — which the caller chooses, and which is normally the envelope entry.
        final int ex = x[e.suspect()], ey = y[e.suspect()], eh = h[e.suspect()];
        BufferedImage map = ImageIO.read(background.toFile());

        Frame frame = frame(ex, ey, turningRadius, scale);
        int margin = (int) Math.ceil(rFlock) + 24;
        int left = frame.left(), top = frame.top();
        int span = margin * 2 + 1;

        BufferedImage img = new BufferedImage(span * scale, span * scale + HEADER,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(SHADE));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        for (int py = 0; py < span; py++) {
            for (int px = 0; px < span; px++) {
                int sx = left + px, sy = top + py;
                if (sx < 0 || sy < 0 || sx >= map.getWidth() || sy >= map.getHeight()) continue;
                // Dimmed, so the boids and rings read over it rather than competing with it.
                int rgb = map.getRGB(sx, sy) & 0xFFFFFF;
                int dim = ((rgb >> 16 & 255) * 45 / 100) << 16
                        | ((rgb >> 8 & 255) * 45 / 100) << 8 | (rgb & 255) * 45 / 100;
                g.setColor(new Color(dim));
                g.fillRect(px * scale, py * scale + HEADER, scale, scale);
            }
        }

        int cx = frame.px(ex), cy = frame.py(ey);

        g.setStroke(new BasicStroke(1.5f));
        ring(g, cx, cy, rSep * scale, SEP_RING);
        ring(g, cx, cy, rFlock * scale, FLOCK_RING);

        // The blind arc behind the suspect, since a neighbour inside the radius but behind it
        // is invisible and the picture should say so.
        double hx = Params.COS[eh], hy = Params.SIN[eh];
        double back = Math.acos(Params.COS_FOV);
        double facing = Math.atan2(hy, hx);
        g.setColor(new Color(FLOCK_RING));
        for (int s : new int[]{-1, 1}) {
            double t = facing + s * back;
            g.drawLine(cx, cy, (int) (cx + Math.cos(t) * rFlock * scale),
                    (int) (cy + Math.sin(t) * rFlock * scale));
        }

        for (int j = 0; j < n; j++) {
            if (j == e.suspect()) continue;
            boolean seen = rules.perceived(ex, ey, eh, x[j], y[j]) >= 0;
            boid(g, frame.px(x[j]), frame.py(y[j]),
                    h[j], seen ? VISIBLE : UNSEEN, scale, String.valueOf(j));
        }
        boid(g, cx, cy, eh, SUSPECT, scale, String.valueOf(e.suspect()));

        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.drawString(String.format("%s exit  %d -> %d",
                e.unexplained() ? "UNEXPLAINED" : String.valueOf(e.best()),
                e.fromEdge(), e.toEdge()), 10, 20);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString(String.format("crossed at tick %d, steered onto the envelope at tick %d;"
                        + " boid %d at (%d,%d,%d)", e.tick(), e.entryTick(), e.suspect(),
                ex, ey, eh), 10, 40);
        g.drawString(String.format("%d neighbours in view, %d reasons"
                        + "   |  red ring = separation %.0f, blue = flocking %.0f, blue rays"
                        + " = blind arc", e.seen(), e.reasons().size(), rSep, rFlock), 10, 58);
        g.dispose();
        return img;
    }

    private static void ring(Graphics2D g, int cx, int cy, double r, int rgb) {
        g.setColor(new Color(rgb));
        g.drawOval(cx - (int) r, cy - (int) r, (int) r * 2, (int) r * 2);
    }

    private static void boid(Graphics2D g, int cx, int cy, int heading, int rgb, int scale,
                             String label) {
        int reach = 7 * scale;
        g.setColor(new Color(rgb));
        g.setStroke(new BasicStroke(Math.max(2, scale / 2f)));
        g.drawLine(cx, cy, (int) (cx + Params.COS[heading] * reach),
                (int) (cy + Params.SIN[heading] * reach));
        int dot = Math.max(4, scale);
        g.fillOval(cx - dot, cy - dot, dot * 2, dot * 2);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString(label, cx + dot + 2, cy - dot - 2);
    }
}
