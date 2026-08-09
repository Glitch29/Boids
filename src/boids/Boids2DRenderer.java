package boids;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.Override;
import java.nio.file.Path;

/**
 * Draws a state to an image. JDK only, no dependencies, headless-safe.
 * <p>
 * Boid size is derived from the flock's own spread rather than being a constant, so
 * changing the play area, the boid count or the turning radius does not require
 * readjusting the drawing parameters.
 */
public final class Boids2DRenderer implements Renderer{
    private static final int TRAVERSABLE = 0xE6E6E6;
    private static final int WALL = 0x000000;
    private static final int SCORING = 0xF7E0CC;

    /** The colour the source images use to mark a scoring zone. */
    private static final int SOURCE_SCORING = 0xFF7F27;

    private final BufferedImage background;
    private final int scale;
    private final int sourceWidth;
    private final int sourceHeight;

    public Boids2DRenderer(Path source) throws IOException {
        this(source, 1);
    }

    /** {@code scale} enlarges the output by whole pixels; the play area is unchanged. */
    public Boids2DRenderer(Path source, int scale) throws IOException {
        BufferedImage recoloured = buildBackground(source);
        this.scale = scale;
        this.sourceWidth = recoloured.getWidth();
        this.sourceHeight = recoloured.getHeight();
        this.background = scale == 1 ? recoloured : enlarge(recoloured, scale);
    }

    private static BufferedImage enlarge(BufferedImage src, int scale) {
        BufferedImage out = new BufferedImage(
                src.getWidth() * scale, src.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                out.setRGB(x, y, src.getRGB(x / scale, y / scale));
            }
        }
        return out;
    }

    /**
     * Recolours the source play area once, into a fresh opaque image.
     * <p>
     * Two reasons not to paint over the loaded image directly. It is read back at
     * whatever type the PNG encoder chose — the maps drawn in Paint come back as
     * {@code TYPE_4BYTE_ABGR} while a generated one comes back as {@code TYPE_3BYTE_BGR}
     * — and writing a plain {@code 0xRRGGBB} constant into an image that has an alpha
     * channel sets alpha to zero, so every pixel becomes transparent and composites to
     * black later. Building an opaque image of a known type sidesteps the question.
     * And doing it once in the constructor beats re-reading the file on every frame.
     */
    private static BufferedImage buildBackground(Path source) throws IOException {
        BufferedImage src = ImageIO.read(source.toFile());
        if (src == null) throw new IOException("not a readable image: " + source);

        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y) & 0xFFFFFF;
                row[x] = rgb == 0x000000 ? WALL
                       : rgb == SOURCE_SCORING ? SCORING
                       : TRAVERSABLE;
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /**
     * Boid length as a multiple of the flock's characteristic spacing.
     * <p>
     * sqrt(variance / n) runs about half the true mean spacing for a well-spread
     * flock, so this lands a boid at roughly a third of the gap to its neighbour.
     */
    public static final double SIZE_K = 0.6;

    /** Nose-to-base length over base width. Larger is pointier. */
    private static final double ASPECT = 2.5;

    private static final double MIN_LEN = 2.0;

    @Override
    public BufferedImage render(Sim.State s) {
        int w = background.getWidth();
        int h = background.getHeight();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(background, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double len = boidLength(s, sourceWidth, sourceHeight) * scale;
        for (int i = 0; i < s.n; i++) {
            g.setColor(colorOf(i, s.n));
            g.fill(triangle(s.x[i] * scale, s.y[i] * scale,
                    Params.COS[s.h[i]], Params.SIN[s.h[i]], len));
        }

        g.dispose();
        return img;
    }

    /**
     * Equally spaced hues around the wheel, with even-numbered boids pastel and
     * odd-numbered boids mostly saturated so that neighbouring indices stay
     * distinguishable despite their hues being close.
     */
    public static Color colorOf(int index, int n) {
        float hue = (float) index / n;
        boolean pastel = (index % 2) == 0;
        return Color.getHSBColor(hue, pastel ? 0.35f : 0.95f, pastel ? 0.98f : 0.78f);
    }

    /**
     * Boid length, proportional to the mean spacing between boids in the flock.
     * <p>
     * n boids spread over an area A sit about sqrt(A/n) apart, and A is proportional
     * to the total positional variance, so length goes as sqrt(variance / n).
     */
    public static double boidLength(Sim.State s, int w, int h) {
        double mx = 0, my = 0;
        for (int i = 0; i < s.n; i++) { mx += s.x[i]; my += s.y[i]; }
        mx /= s.n;
        my /= s.n;

        double variance = 0;
        for (int i = 0; i < s.n; i++) {
            double dx = s.x[i] - mx;
            double dy = s.y[i] - my;
            variance += dx * dx + dy * dy;
        }
        variance /= s.n;

        double len = SIZE_K * Math.sqrt(variance / s.n);
        double max = Math.min(w, h) / 10.0;
        return Math.min(Math.max(len, MIN_LEN), max);
    }

    /** Isosceles triangle centred on (px,py), nose along the unit vector (cx,cy). */
    private static Path2D.Double triangle(double px, double py,
                                          double cx, double cy,
                                          double len) {
        double halfBase = len / (2.0 * ASPECT);

        double noseX = px + cx * len * (2.0 / 3.0);
        double noseY = py + cy * len * (2.0 / 3.0);
        double backX = px - cx * len * (1.0 / 3.0);
        double backY = py - cy * len * (1.0 / 3.0);

        double perpX = -cy * halfBase;
        double perpY = cx * halfBase;

        Path2D.Double t = new Path2D.Double();
        t.moveTo(noseX, noseY);
        t.lineTo((backX + perpX), (backY + perpY));
        t.lineTo((backX - perpX), (backY - perpY));
        t.closePath();
        return t;
    }
}
