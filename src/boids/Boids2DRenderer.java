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
    private final Path background;

    public Boids2DRenderer(Path background) {
        this.background = background;
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
    public BufferedImage render(Sim.State s) throws IOException {
        BufferedImage img = ImageIO.read(background.toFile());;
        int w = img.getWidth();
        int h = img.getHeight();

//        int[] row = new int[w];
//        for (int y = 0; y < h; y++) {
//            for (int x = 0; x < w; x++) {
//                row[x] = (img.getRGB(x, y) & 0x00FFFFFF) == 0 ? WALL : TRAVERSABLE;
//            }
//            img.setRGB(0, y, w, 1, row, 0, w);
//        }

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double len = boidLength(s, w, h);
        for (int i = 0; i < s.n; i++) {
            g.setColor(colorOf(i, s.n));
            g.fill(triangle(s.x[i], s.y[i], Params.COS[s.h[i]], Params.SIN[s.h[i]], len));
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
