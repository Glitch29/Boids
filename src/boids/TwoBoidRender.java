package boids;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Two-boid arrangements drawn in clock coordinates rather than on the map.
 * <p>
 * Fixing which edge each boid is on leaves two numbers — how far along its own edge each one
 * is — and those two numbers are what a distance is actually read from. Plotted against each
 * other they show the shape of the constraint directly: a diagonal band means the two are
 * locked at a fixed separation, a vertical stripe means one is pinned wherever the other is,
 * and blank means the pair cannot happen at all.
 * <p>
 * One panel per edge the other boid can be on, because the pair {@code (edge, tick)} is the
 * whole coordinate and a tick means nothing without the edge it belongs to. Panels are drawn
 * to a shared scale so that they can be laid against one another; lengths add, so a panel's
 * right-hand neighbour in a route continues where it left off.
 * <p>
 * Ticks are rounded to whole numbers. That is a projection and it loses something — two
 * arrangements a third of a tick apart land in one cell — but the clock is only read to about
 * a tenth of a tick anyway, and a plot at the resolution of the underlying state space would
 * be mostly empty.
 */
public final class TwoBoidRender {
    private TwoBoidRender() {}

    /** Distinct enough to tell nine of them apart, and legible on a dark ground. */
    private static final int[] EDGE_COLOUR = {
            0xE6194B, 0x3CB44B, 0x4363D8, 0xF58231, 0x911EB4,
            0x42D4F4, 0xF032E6, 0xBFEF45, 0xFFE119};

    private static final int GROUND = 0x14171C;
    private static final int PANEL = 0x1E2129;
    private static final int RULE = 0x39404D;
    private static final int TEXT = 0xB9C1CE;

    /** Where an edge's ticks run to, rounded outward to whole ticks. */
    private record Span(int lo, int hi) {
        int size() { return hi - lo + 1; }
    }

    /**
     * A sheet of panels for one boid edge against every edge the psyboid can occupy, plus each
     * panel on its own so they can be moved about.
     *
     * @param boidEdge which edge the boid is held on
     * @param scale    pixels per tick
     */
    public static void write(int[] live, int liveCount, int[] edge, EdgeMetric.Metric m,
                             TwoBoid.Reachable r, int boidEdge, int scale, Path dir)
            throws IOException {
        Files.createDirectories(dir);
        int edges = m.length().length;

        Span[] span = new Span[edges];
        for (int e = 0; e < edges; e++) {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int i = 0; i < liveCount; i++) {
                if (edge[live[i]] != e) continue;
                int t = (int) Math.round(m.tick()[live[i]]);
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
            span[e] = lo > hi ? new Span(0, 0) : new Span(lo, hi);
        }

        // The boid's states listed once, so the scan touches only the slice being drawn
        // rather than every pair on the map.
        int[] onEdge = new int[liveCount];
        int[] boidTick = new int[liveCount];
        int count = 0;
        for (int j = 0; j < liveCount; j++) {
            if (edge[live[j]] != boidEdge) continue;
            boidTick[count] = (int) Math.round(m.tick()[live[j]]) - span[boidEdge].lo();
            onEdge[count++] = j;
        }

        int width = span[boidEdge].size();
        boolean[][] cells = new boolean[edges][];
        long[] filled = new long[edges];
        for (int e = 0; e < edges; e++) cells[e] = new boolean[width * span[e].size()];

        for (int i = 0; i < liveCount; i++) {
            int pe = edge[live[i]];
            if (pe < 0) continue;
            int py = (int) Math.round(m.tick()[live[i]]) - span[pe].lo();
            long base = (long) i * liveCount;
            for (int k = 0; k < count; k++) {
                long at = base + onEdge[k];
                if ((r.bits()[(int) (at >>> 6)] & (1L << (at & 63))) == 0) continue;
                int cell = py * width + boidTick[k];
                if (!cells[pe][cell]) { cells[pe][cell] = true; filled[pe]++; }
            }
        }

        System.out.printf("%n  boid on edge %d: tick %d..%d, %d states%n",
                boidEdge, span[boidEdge].lo(), span[boidEdge].hi(), count);
        System.out.printf("  %-8s %10s %10s %12s %9s%n",
                "psyboid", "tick lo", "tick hi", "cells filled", "of panel");
        for (int e = 0; e < edges; e++) {
            long area = (long) width * span[e].size();
            System.out.printf("  %-8d %10d %10d %12d %8.2f%%%n", e, span[e].lo(), span[e].hi(),
                    filled[e], 100.0 * filled[e] / area);
            ImageIO.write(panel(cells[e], width, span[e], span[boidEdge], boidEdge, e, scale,
                    m.length()), "png",
                    dir.resolve(String.format("edge%d_x_edge%d.png", boidEdge, e)).toFile());
        }
        sheet(cells, width, span, boidEdge, scale, m.length(), edges,
                dir.resolve(String.format("edge%d_sheet.png", boidEdge)));
    }

    /** Joining edges along a route is {@link TwoBoidRouteSheet}; this draws them one at a time. */

    private static final int PAD = 46;

    private static BufferedImage panel(boolean[] cells, int width, Span psy, Span boid,
                                       int boidEdge, int psyEdge, int scale, double[] length) {
        int height = psy.size();
        BufferedImage img = new BufferedImage(width * scale + PAD + 12,
                height * scale + PAD + 12, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        draw(g, cells, width, height, psy, boid, boidEdge, psyEdge, scale, length, PAD, 6);
        g.dispose();
        return img;
    }

    /**
     * Everything on one sheet, laid out in a grid at a shared scale.
     * <p>
     * Panels are the same size per tick throughout, so a piece from one panel can be compared
     * against a piece from another without rescaling — which is the point, since fitting them
     * together is how a route across several edges gets read.
     */
    private static void sheet(boolean[][] cells, int width, Span[] span, int boidEdge, int scale,
                              double[] length, int edges, Path out) throws IOException {
        int cols = (int) Math.ceil(Math.sqrt(edges));
        int rows = (edges + cols - 1) / cols;
        int cellW = width * scale + PAD + 24;
        int tallest = 0;
        for (Span s : span) tallest = Math.max(tallest, s.size());
        int cellH = tallest * scale + PAD + 34;

        BufferedImage img = new BufferedImage(cols * cellW, rows * cellH + 30,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString(String.format(
                "boid on edge %d (horizontal) against psyboid edge (vertical), clock ticks",
                boidEdge), 12, 20);

        for (int e = 0; e < edges; e++) {
            int cx = (e % cols) * cellW, cy = 30 + (e / cols) * cellH;
            g.translate(cx, cy);
            draw(g, cells[e], width, span[e].size(), span[e], span[boidEdge], boidEdge, e,
                    scale, length, PAD, 22);
            g.translate(-cx, -cy);
        }
        g.dispose();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
        System.out.printf("  wrote %s%n", out);
    }

    private static void draw(Graphics2D g, boolean[] cells, int width, int height, Span psy,
                             Span boid, int boidEdge, int psyEdge, int scale, double[] length,
                             int left, int top) {
        g.setColor(new Color(PANEL));
        g.fillRect(left, top, width * scale, height * scale);

        // Where each edge's own zero and its far end fall, because fitting two panels together
        // is done in those coordinates and not in pixels.
        g.setColor(new Color(RULE));
        rule(g, left, top, width, height, scale, -boid.lo(), true);
        rule(g, left, top, width, height, scale, (int) Math.round(length[boidEdge]) - boid.lo(), true);
        rule(g, left, top, width, height, scale, -psy.lo(), false);
        rule(g, left, top, width, height, scale, (int) Math.round(length[psyEdge]) - psy.lo(), false);

        int rgb = EDGE_COLOUR[psyEdge % EDGE_COLOUR.length];
        g.setColor(new Color(rgb));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!cells[y * width + x]) continue;
                // Vertical flipped, so the psyboid's clock reads upward like an ordinary plot.
                g.fillRect(left + x * scale, top + (height - 1 - y) * scale, scale, scale);
            }
        }

        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString(String.format("psyboid edge %d", psyEdge), left, top - 6);
        g.drawString(String.valueOf(psy.hi()), left - 34, top + 10);
        g.drawString(String.valueOf(psy.lo()), left - 34, top + height * scale);
        g.drawString(String.valueOf(boid.lo()), left, top + height * scale + 14);
        String hi = String.valueOf(boid.hi());
        g.drawString(hi, left + width * scale - 7 * hi.length(), top + height * scale + 14);
    }

    private static void rule(Graphics2D g, int left, int top, int width, int height, int scale,
                             int at, boolean vertical) {
        if (vertical) {
            if (at < 0 || at >= width) return;
            g.fillRect(left + at * scale, top, 1, height * scale);
        } else {
            if (at < 0 || at >= height) return;
            g.fillRect(left, top + (height - 1 - at) * scale, width * scale, 1);
        }
    }
}
