package boids;

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
 * State sets projected onto the play area, several to a sheet.
 * <p>
 * <b>A renderer, which is the only thing allowed to want {@code (x, y)}.</b> A set lives in
 * {@code (x, y, d)} and nothing may be concluded from its shadow — most live pixels carry states
 * on several edges running different directions — but the shadow is what the eye can read, and
 * the heading range at a pixel is usually inferable from where on the map it is.
 * <p>
 * So each pixel is coloured by <b>how many of its 64 headings are in the set</b>, on a ramp from
 * the map's own grey to hot. One heading is a single trajectory passing through; a large count is
 * a place a boid can be pointing many ways, which on a dab-like map means a junction or a wide
 * stretch. Comparing panels then shows growth as both spread and deepening.
 */
public final class StateSetRender {
    private StateSetRender() {}

    private static final int GROUND = 0x14171C;
    private static final int CARD = 0x1B1F26;
    private static final int TEXT = 0xE8ECF3;
    private static final int FAINT = 0x8B94A2;

    /** One panel: a set and what to call it. */
    public record Panel(String title, String detail, StateSet set) {}

    /**
     * @param background the play area to draw over; the display copy, since this is rendering
     * @param columns    panels per row
     * @param scale      pixels per map pixel
     */
    public static void write(Path background, Path out, List<Panel> panels, int columns,
                             int scale, String heading) throws IOException {
        BufferedImage map = javax.imageio.ImageIO.read(background.toFile());
        int w = map.getWidth(), h = map.getHeight();

        int cap = 40, gap = 10, head = 52;
        int pw = w * scale, ph = h * scale + cap;
        int rows = (panels.size() + columns - 1) / columns;
        BufferedImage img = new BufferedImage(gap + columns * (pw + gap),
                head + gap + rows * (ph + gap), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString(heading, gap + 4, 24);
        g.setColor(new Color(FAINT));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("each pixel coloured by how many of its 64 headings are in the set: "
                + "dim red = 1, through orange, to white = the whole fan", gap + 4, 42);

        int turns = Params.TURNS;
        for (int i = 0; i < panels.size(); i++) {
            Panel p = panels.get(i);
            int[] count = new int[w * h];
            for (int s : p.set().toArray()) count[s / turns]++;

            BufferedImage tile = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
            Graphics2D t = tile.createGraphics();
            t.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            t.setColor(new Color(CARD));
            t.fillRect(0, 0, pw, ph);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int n = count[x + y * w];
                    int rgb;
                    if (n == 0) {
                        int base = map.getRGB(x, y) & 0xFFFFFF;
                        rgb = ((base >> 16 & 255) * 30 / 100) << 16
                                | ((base >> 8 & 255) * 30 / 100) << 8 | (base & 255) * 30 / 100;
                    } else {
                        rgb = ramp(n, turns);
                    }
                    t.setColor(new Color(rgb));
                    t.fillRect(x * scale, y * scale + cap, scale, scale);
                }
            }
            t.setColor(new Color(TEXT));
            t.setFont(new Font("SansSerif", Font.BOLD, 14));
            t.drawString(p.title(), 8, 18);
            t.setColor(new Color(FAINT));
            t.setFont(new Font("SansSerif", Font.PLAIN, 11));
            t.drawString(p.detail(), 8, 33);
            t.dispose();
            g.drawImage(tile, gap + (i % columns) * (pw + gap),
                    head + gap + (i / columns) * (ph + gap), null);
        }
        g.dispose();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());
        System.out.printf("wrote %s (%dx%d)%n", out, img.getWidth(), img.getHeight());
    }

    /**
     * Heading count to colour.
     * <p>
     * Compressed at the bottom, because the interesting difference is between one heading and a
     * handful — a set that has gained a second heading at a pixel has gained a genuinely
     * different trajectory — while the difference between forty and fifty is noise.
     */
    private static int ramp(int n, int turns) {
        double t = Math.min(1, Math.log(n) / Math.log(turns / 2.0));
        int r = (int) (170 + 85 * t);
        int g = (int) (40 + 215 * t * t);
        int b = (int) (50 + 205 * t * t * t);
        return Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }
}
