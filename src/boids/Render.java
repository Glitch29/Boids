package boids;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Draws a state to an image. JDK only, no dependencies, headless-safe.
 * <p>
 * Boid size is derived from the flock's own spread rather than being a constant,
 * so tweaking the arena size, the boid count or the turning radius does not
 * require readjusting the drawing parameters.
 */
public final class Render {
    private Render() {}

    private static final Color BACKGROUND = new Color(0xE6, 0xE6, 0xE6);
    private static final Color BOID = new Color(0x28, 0x32, 0x46);

    /**
     * Boid length as a multiple of the flock's characteristic spacing.
     * <p>
     * sqrt(variance / n) runs about half the true mean spacing for a well-spread
     * flock, so this lands a boid at roughly a third of the gap to its neighbour.
     */
    public static final double SIZE_K = 0.6;

    /** Nose-to-base length over base width. Larger is pointier. */
    private static final double ASPECT = 2.5;

    /**
     * Safety clamps for degenerate flock shapes. The circular estimator diverges as
     * the flock approaches a uniform spread, which is exactly the starting state, so
     * the upper clamp keeps the first few frames sane. Neither should bind once the
     * flock has organised.
     */
    private static final double MIN_LEN = 2.0;
    private static final double MAX_LEN = Params.W / 10.0;

    public static void writePng(Sim s, int scale, Path out) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(toImage(s, scale), "png", out.toFile());
    }

    public static BufferedImage toImage(Sim s, int scale) {
        int w = (int) Math.round(Params.W * scale);
        int h = (int) Math.round(Params.H * scale);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);
        g.setColor(BOID);

        double len = boidLength(s);
        for (int i = 0; i < s.n; i++) {
            double cx = Sim.COS[s.h[i]];
            double cy = Sim.SIN[s.h[i]];
            // Draw the wrapped copies too. Without this a boid straddling an edge
            // loses half its triangle and reads as a rendering glitch rather than
            // as a boid crossing the seam.
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    double px = s.x[i] + ox * Params.W;
                    double py = s.y[i] + oy * Params.H;
                    if (px < -len || px > Params.W + len || py < -len || py > Params.H + len) continue;
                    g.fill(triangle(px, py, cx, cy, len, scale));
                }
            }
        }

        g.dispose();
        return img;
    }

    /**
     * Boid length, proportional to the mean spacing between boids in the flock.
     * <p>
     * n boids spread over an area A sit about sqrt(A/n) apart, and A is proportional
     * to the total positional variance, so length goes as sqrt(variance / n).
     */
    public static double boidLength(Sim s) {
        double sx = circularStd(s.x, s.n, Params.W);
        double sy = circularStd(s.y, s.n, Params.H);
        double len = SIZE_K * Math.sqrt((sx * sx + sy * sy) / s.n);
        return Math.min(Math.max(len, MIN_LEN), MAX_LEN);
    }

    /**
     * Standard deviation of a coordinate that lives on a circle of circumference
     * {@code span}.
     * <p>
     * Ordinary variance is useless on a wrapping axis: a flock straddling the seam
     * has boids at x=5 and x=475 that are 10 apart in reality but 470 apart
     * arithmetically, which would balloon the computed spread and draw enormous
     * triangles every time a flock crosses an edge. This is the standard circular
     * estimator and reduces to the ordinary deviation for a concentrated flock.
     */
    private static double circularStd(double[] v, int n, double span) {
        double c = 0, s = 0;
        for (int i = 0; i < n; i++) {
            double t = 2.0 * Math.PI * v[i] / span;
            c += Math.cos(t);
            s += Math.sin(t);
        }
        double r = Math.sqrt(c * c + s * s) / n;
        if (r < 1e-6) return span / 4.0;   // no concentration at all; the estimator diverges
        return span / (2.0 * Math.PI) * Math.sqrt(-2.0 * Math.log(r));
    }

    /** Isosceles triangle centred on (px,py), nose along the unit vector (cx,cy). */
    private static Path2D.Double triangle(double px, double py,
                                          double cx, double cy,
                                          double len, int scale) {
        double halfBase = len / (2.0 * ASPECT);

        double noseX = px + cx * len * (2.0 / 3.0);
        double noseY = py + cy * len * (2.0 / 3.0);
        double backX = px - cx * len * (1.0 / 3.0);
        double backY = py - cy * len * (1.0 / 3.0);

        double perpX = -cy * halfBase;
        double perpY = cx * halfBase;

        Path2D.Double t = new Path2D.Double();
        t.moveTo(noseX * scale, noseY * scale);
        t.lineTo((backX + perpX) * scale, (backY + perpY) * scale);
        t.lineTo((backX - perpX) * scale, (backY - perpY) * scale);
        t.closePath();
        return t;
    }
}
