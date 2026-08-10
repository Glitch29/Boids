package boids;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Encodes a {@link NavMap} visually.
 * <p>
 * Out of bounds is pale blue. A pixel every heading survives is pale green. Otherwise
 * hue is the middle of the widest band of headings that cannot be survived, and value
 * darkens as that band widens — so the colour says which way a boid must not be
 * pointing, and how badly the pixel is constrained.
 * <p>
 * A pixel with no survivable heading at all is drawn red. Those are traps: in play, but
 * a boid that reaches one is already lost. They should not exist in a sane map, and
 * seeing any is a signal that the play area has a pocket no turning circle fits inside.
 */
public final class NavMapRender {
    private NavMapRender() {}

    private static final double BASE_SATURATION = 0.5;

    private static final int OUT_OF_BOUNDS = 0xBAD5F5;
    private static final int UNCONSTRAINED = 0xE9F5E1;
    private static final int TRAP = 0xFF0000;

    public static void write(NavMap map, int scale, Path out) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(toImage(map, scale), "png", out.toFile());
    }

    /** {@code scale} replicates pixels; there is no interpolation, so nothing is invented. */
    public static BufferedImage toImage(NavMap map, int scale) {
        int w = map.width();
        int h = map.height();
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = colorOf(map, x, y);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        img.setRGB(x * scale + dx, y * scale + dy, rgb);
                    }
                }
            }
        }
        return img;
    }

    private static int colorOf(NavMap map, int x, int y) {
        if (map.oob(x, y)) return OUT_OF_BOUNDS;

        int turns = Params.TURNS;
        boolean[] dead = new boolean[turns];
        int deadCount = 0;
        for (int i = 0; i < turns; i++) {
            dead[i] = !map.alive(x, y, i);
            if (dead[i]) deadCount++;
        }
        if (deadCount == 0) return UNCONSTRAINED;
        if (deadCount == turns) return TRAP;

        // Widest circular run of dead headings, and where its middle points.
        int bestStart = 0;
        int bestLength = 0;
        int firstLive = 0;
        while (dead[firstLive]) firstLive++;
        for (int i = 0; i < turns; ) {
            int at = (firstLive + i) % turns;
            if (!dead[at]) { i++; continue; }
            int length = 0;
            while (i < turns && dead[(firstLive + i) % turns]) { length++; i++; }
            if (length > bestLength) {
                bestLength = length;
                bestStart = (firstLive + i - length) % turns;
            }
        }

        double middle = (bestStart + (bestLength - 1) / 2.0) * 360.0 / turns;
        float hue = (float) (((middle % 360) + 360) % 360 / 360.0);
        float value = (float) (1.0 - 0.8 * Math.cbrt(deadCount / (double) turns));

        return Color.HSBtoRGB(hue, (float) BASE_SATURATION, value) & 0xFFFFFF;
    }
}
