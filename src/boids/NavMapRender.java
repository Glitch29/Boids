package boids;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Encodes a {@link NavMap} visually.
 * <p>
 * Out of bounds is black. A traversable pixel with no prohibited range is white.
 * Otherwise the longest range is shown directly: hue is its midpoint heading, and
 * value falls from a medium pastel toward black as the cube root of its length
 * approaches the cube root of 360 degrees. Saturation halves for each range beyond
 * the first, so a pixel with two ranges reads as a washed-out version of its longest.
 */
public final class NavMapRender {
    private NavMapRender() {}

    /** Saturation of a pixel whose single range has length near zero. */
    private static final double BASE_SATURATION = 0.5;

    public static void write(NavMap map, int scale, Path out) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(toImage(map, scale), "png", out.toFile());
    }

    /** {@code scale} replicates pixels; there is no interpolation, so no information is invented. */
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
        if (map.oob(x, y)) return 0x000000;

        List<NavMap.Range> ranges = map.ranges(x, y);
        if (ranges.isEmpty()) return 0xFFFFFF;

        NavMap.Range longest = ranges.get(0);
        double length = longest.lengthDeg();

        float hue = (float) (longest.midDeg() / 360.0);
        float value = (float) (1.0 - Math.cbrt(length / 360.0));
        float saturation = (float) (BASE_SATURATION / Math.pow(2, ranges.size() - 1));

        return Color.HSBtoRGB(hue, saturation, value) & 0xFFFFFF;
    }
}
