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

    /**
     * The same encoding over any per-{@code (x, y, heading)} set, marked headings playing
     * the part the unsurvivable ones play above.
     * <p>
     * Hue is the middle of the widest run of marked headings and value darkens as that run
     * widens, so a set is read the same way a navmap is: the colour says which way, and how
     * much of the compass. Sharing the encoding is the point — an overlay only means
     * anything against the map if the two are drawn in the same language.
     *
     * @param marked bits indexed as {@code (x + y * width) * TURNS + heading}
     * @param empty  colour for a pixel with nothing marked, which is the common case and so
     *               wants to recede rather than compete with the pixels that carry the answer
     * @param full   colour for a pixel where every heading is marked. Deliberately the
     *               caller's choice: {@link #TRAP} red means "nothing works here" on a
     *               navmap, and a set where everything works wants the opposite reading
     */
    public static void write(NavMap map, long[] marked, int empty, int full, int scale, Path out)
            throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        int w = map.width(), h = map.height();
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        boolean[] band = new boolean[Params.TURNS];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb;
                if (map.oob(x, y)) {
                    rgb = OUT_OF_BOUNDS;
                } else {
                    int count = 0;
                    for (int i = 0; i < Params.TURNS; i++) {
                        int at = (x + y * w) * Params.TURNS + i;
                        band[i] = (marked[at >>> 6] & (1L << (at & 63))) != 0;
                        if (band[i]) count++;
                    }
                    rgb = count == 0 ? empty : count == Params.TURNS ? full : bandColour(band, count);
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        img.setRGB(x * scale + dx, y * scale + dy, rgb);
                    }
                }
            }
        }
        ImageIO.write(img, "png", out.toFile());
    }

    /**
     * A scalar per pixel, drawn as a divergence from an expected value.
     * <p>
     * Grey where the value is what it should be, and further into one colour or the other the
     * further it strays either way. A divergent scale rather than a sequential one because
     * the interesting thing is the sign as much as the size: a clock running slow and a clock
     * running fast are different faults, and a single ramp would put them at opposite ends of
     * the same scale with the correct answer somewhere in the middle of it.
     *
     * @param value  per pixel, indexed {@code x + y * width}
     * @param has    whether that pixel has a value at all
     * @param centre the value that means nothing is wrong
     * @param span   how far from {@code centre} saturates the colour; beyond this it clamps
     * @param below  colour approached for values under {@code centre}
     * @param above  colour approached for values over it
     */
    public static void writeField(NavMap map, double[] value, boolean[] has, double centre,
                                  double span, int below, int above, int scale, Path out)
            throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        int w = map.width(), h = map.height();
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb;
                if (map.oob(x, y)) rgb = OUT_OF_BOUNDS;
                else if (!has[x + y * w]) rgb = NO_VALUE;
                else rgb = diverge(value[x + y * w], centre, span, below, above);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        img.setRGB(x * scale + dx, y * scale + dy, rgb);
                    }
                }
            }
        }
        ImageIO.write(img, "png", out.toFile());
    }

    /** In play but carrying no reading, which is not the same as reading zero. */
    private static final int NO_VALUE = 0x2A2E36;

    private static final int NEUTRAL = 0x9AA0AA;

    private static int diverge(double v, double centre, double span, int below, int above) {
        double t = span <= 0 ? 0 : (v - centre) / span;
        t = Math.max(-1, Math.min(1, t));
        int target = t < 0 ? below : above;
        // Square-rooted so the first part of the departure is the most visible: almost
        // everything sits near the centre, and a linear ramp would leave it all looking grey.
        return lerp(NEUTRAL, target, Math.sqrt(Math.abs(t)));
    }

    private static int lerp(int from, int to, double t) {
        int r = (int) Math.round((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * t);
        int g = (int) Math.round((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * t);
        int b = (int) Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return r << 16 | g << 8 | b;
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
        return bandColour(dead, deadCount);
    }

    /** Hue from the middle of the widest circular run, value from how much is marked. */
    private static int bandColour(boolean[] dead, int deadCount) {
        int turns = Params.TURNS;
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
