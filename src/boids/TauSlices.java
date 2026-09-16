package boids;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A per-state scalar over a map, drawn so its texture can be seen: {@code (x, y, d)} compressed
 * to {@code (x, y, d mod 16)}, the sixteen slices tiled four by four in reading order, the value
 * shown modulo a period as hue.
 * <p>
 * <b>Specified by the user 2026-09-15.</b> Metrics had never quite said what a clock was doing;
 * this is for looking. Sixteen slices because the route's headings at any pixel span far fewer
 * than a quarter turn, so folding {@code d} by 16 is almost always collision-free while cutting
 * the picture to a size that can be read. The period is the modulus of the value shown — sixteen
 * for a clock, so that bands can be counted and a tick's worth of texture is still visible.
 * <p>
 * <b>Collisions</b> — two states at one pixel of one slice — follow the project's rule for
 * visualisations: show one of the values, and mark the collision by a change orthogonal to every
 * channel that carries data. Data is hue alone, so a collision is drawn at half saturation. The
 * value shown is the first state's in index order, which is the lowest heading.
 * <p>
 * A pixel of the region with no state in the slice is dark; a pixel outside it is black.
 */
public final class TauSlices {
    private TauSlices() {}

    /**
     * @param map     the navmap, for dimensions and headings
     * @param states  the states to draw
     * @param value   per state (indexed by state), the scalar; NaN is not drawn
     * @param period  the modulus the value is shown under
     * @param region  per pixel, whether it is in the region drawn dark where no state falls
     * @param box     {@code {x0, y0, x1, y1}} of the crop, exclusive at the far corner
     * @param scale   pixels per map pixel
     */
    public static void draw(NavMap map, int[] states, double[] value, double period, boolean[] region,
                            int[] box, int scale, Path out) throws IOException {
        int w = map.width(), turns = Params.TURNS, fold = 16;
        int bw = box[2] - box[0], bh = box[3] - box[1];
        int gap = 6, label = 14;
        // Per slice and pixel: the value shown, and whether more than one state fell there.
        double[][] shown = new double[fold][bw * bh];
        boolean[][] collided = new boolean[fold][bw * bh];
        for (double[] row : shown) Arrays.fill(row, Double.NaN);
        int collisions = 0;
        for (int s : states) {
            if (Double.isNaN(value[s])) continue;
            int d = s % turns, cell = s / turns, x = cell % w - box[0], y = cell / w - box[1];
            if (x < 0 || y < 0 || x >= bw || y >= bh) continue;
            int slice = d % fold, i = x + y * bw;
            if (Double.isNaN(shown[slice][i])) shown[slice][i] = value[s];
            else if (!collided[slice][i]) { collided[slice][i] = true; collisions++; }
        }
        BufferedImage img = new BufferedImage(4 * (bw * scale + gap) - gap, 4 * (bh * scale + gap + label) - gap,
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        g2.setColor(new java.awt.Color(0x0A0B0D));
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        for (int slice = 0; slice < fold; slice++) {
            int ox = (slice % 4) * (bw * scale + gap), oy = (slice / 4) * (bh * scale + gap + label) + label;
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    int mx = x + box[0], my = y + box[1], i = x + y * bw;
                    int rgb;
                    if (!Double.isNaN(shown[slice][i])) {
                        double t = shown[slice][i] / period;
                        t -= Math.floor(t);
                        rgb = hsv(t, collided[slice][i] ? 0.5 : 1.0, 1.0);
                    } else if (region[mx + my * w]) {
                        rgb = 0x30343C;
                    } else {
                        rgb = map.oob(mx, my) ? 0x000000 : 0x14161A;
                    }
                    for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) img.setRGB(ox + x * scale + sx, oy + y * scale + sy, rgb);
                }
            }
            g2.setColor(java.awt.Color.WHITE);
            StringBuilder heads = new StringBuilder();
            for (int d = slice; d < turns; d += fold) heads.append(heads.length() == 0 ? "" : ",").append(d);
            g2.drawString("d = " + heads + "   value mod " + fmt(period), ox + 2, oy - 3);
        }
        g2.dispose();
        javax.imageio.ImageIO.write(img, "png", out.toFile());
        System.out.printf("   slices: %d states drawn over 16 slices, %d pixel collisions (shown at half saturation) -> %s%n",
                states.length, collisions, out.getFileName());
    }

    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    /** Hue, saturation and value each in {@code [0, 1]}, to packed RGB. */
    static int hsv(double h, double s, double v) {
        double r = Math.max(0, Math.min(1, Math.abs(6 * h - 3) - 1));
        double g = Math.max(0, Math.min(1, 2 - Math.abs(6 * h - 2)));
        double b = Math.max(0, Math.min(1, 2 - Math.abs(6 * h - 4)));
        r = v * (1 - s + s * r);
        g = v * (1 - s + s * g);
        b = v * (1 - s + s * b);
        return ((int) (255 * r) << 16) | ((int) (255 * g) << 8) | (int) (255 * b);
    }
}
