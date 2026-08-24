package boids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Draws a decomposition as the directed graph it is: one node per edge, one arc per
 * transition that leaves one edge for another.
 * <p>
 * The picture the PNG cannot give. {@code decomposition.png} shows where each edge lies,
 * but a pixel can hold up to {@code TURNS} states belonging to different edges, so it has
 * to pick one colour per pixel and the map's actual connectivity is not in it at all.
 * Which edge leads to which is the whole point of a decomposition, and it is a property of
 * the graph rather than of the plane.
 * <p>
 * Reversal is the structure worth seeing. Every edge has an inverse — the same corridor
 * flown the other way — so the graph is symmetric under reversing every arc, and the
 * layout is built to show that: inverse pairs sit mirrored about the centre line and
 * self-inverse edges sit on it. A map whose decomposition is not mirror-symmetric has
 * something asymmetric in it, and that is worth seeing at a glance rather than deducing
 * from a table.
 * <p>
 * Two files, because they answer different questions. The HTML is self-contained and
 * needs nothing installed, which is the whole reason it is hand-rolled rather than handed
 * to a library. The DOT is there for when a real layout engine is worth the install.
 */
public final class EdgeGraphRender {
    private EdgeGraphRender() {}

    /**
     * Where a boid that has just entered an edge ends up while trying to hold one turn.
     *
     * @param to the edge it arrives at, or -1 if the inbound points disagree
     */
    public record Hold(int to, boolean pure) {}

    /**
     * What it costs to leave an edge a particular way.
     *
     * @param minTicks fewest ticks a boid must ask for a turn, over the inbound points;
     *                 -1 where no inbound point can leave this way at all
     */
    public record Exit(int to, int minTicks, int maxTicks, int unreachable) {}

    /**
     * One edge, as the graph view needs it.
     *
     * @param inverse the edge that undoes this one, or -1 where none was found
     * @param purity  the share of this edge's states whose reverse lands in {@code inverse};
     *                below 1 the pairing is a majority verdict rather than a fact
     * @param colour  index into the palette, shared with {@code decomposition.png} so the
     *                two pictures can be read against each other
     * @param inbound points on this edge that something on another edge steps into, which
     *                are the ones {@code left}, {@code straight} and {@code right} answer for
     */
    public record Node(int id, int states, int scoring, int reachesA,
                       int minX, int maxX, int minY, int maxY,
                       int inverse, double purity, int colour,
                       int inbound, Hold left, Hold straight, Hold right, List<Exit> exits,
                       boolean stable, boolean scoringNav) {

        /** Whether the turn a boid takes on arrival changes where it comes out. */
        public boolean decides() {
            return left.to() != straight.to() || right.to() != straight.to();
        }
    }

    /**
     * @param arcs one bitmask per edge: bit {@code f} set means some transition leaves
     *             edge {@code e} for edge {@code f}
     */
    public static void write(String title, List<Node> nodes, long[] arcs, int[] palette,
                             Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("graph.dot"), dot(title, nodes, arcs, palette));
        Path html = dir.resolve("graph.html");
        Files.writeString(html, html(title, nodes, arcs, palette));
        System.out.printf("wrote %s%n", html);
    }

    private static String hex(int[] palette, int colour) {
        return String.format("#%06X", palette[colour % palette.length] & 0xFFFFFF);
    }

    private static String dot(String title, List<Node> nodes, long[] arcs, int[] palette) {
        StringBuilder b = new StringBuilder();
        b.append("digraph edges {\n");
        b.append("  labelloc=\"t\";\n  label=").append(quote(title)).append(";\n");
        b.append("  node [style=filled, shape=circle, fontname=\"Helvetica\"];\n");
        b.append("  edge [arrowsize=0.7];\n\n");
        for (Node n : nodes) {
            // The label's line break is a DOT escape, so it must not go through quote().
            b.append("  ").append(n.id())
                    .append(" [label=\"").append(n.id()).append("\\n").append(n.states())
                    .append(n.scoring() > 0 ? "\\nscoring" : "").append('"')
                    .append(", fillcolor=\"").append(hex(palette, n.colour())).append('"')
                    .append(", width=").append(String.format("%.2f", size(n, nodes)))
                    .append("];\n");
        }
        b.append('\n');
        for (Node n : nodes) {
            for (Node m : nodes) {
                if ((arcs[n.id()] & (1L << m.id())) != 0) {
                    b.append("  ").append(n.id()).append(" -> ").append(m.id()).append(";\n");
                }
            }
        }
        // Inverse pairs are not transitions, so they are drawn as constraint-free hints.
        b.append('\n');
        for (Node n : nodes) {
            if (n.inverse() > n.id()) {
                b.append("  ").append(n.id()).append(" -> ").append(n.inverse())
                        .append(" [dir=none, style=dashed, color=\"#999999\", constraint=false];\n");
            }
        }
        b.append("}\n");
        return b.toString();
    }

    /** Radius in DOT inches, on a log scale so a 600-state edge stays visible beside a 150,000 one. */
    private static double size(Node n, List<Node> nodes) {
        int max = 1;
        for (Node m : nodes) max = Math.max(max, m.states());
        double t = Math.log1p(n.states()) / Math.log1p(max);
        return 0.4 + 0.9 * t;
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String json(String title, List<Node> nodes, long[] arcs, int[] palette) {
        StringBuilder b = new StringBuilder();
        b.append("{\"title\":").append(jsonString(title)).append(",\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(n.id())
                    .append(",\"states\":").append(n.states())
                    .append(",\"inZone\":").append(n.scoring())
                    .append(",\"reachesA\":").append(n.reachesA())
                    .append(",\"x0\":").append(n.minX()).append(",\"x1\":").append(n.maxX())
                    .append(",\"y0\":").append(n.minY()).append(",\"y1\":").append(n.maxY())
                    .append(",\"inv\":").append(n.inverse())
                    .append(",\"purity\":").append(String.format("%.4f", n.purity()))
                    .append(",\"inbound\":").append(n.inbound())
                    .append(",\"L\":").append(hold(n.left()))
                    .append(",\"S\":").append(hold(n.straight()))
                    .append(",\"R\":").append(hold(n.right()))
                    .append(",\"exits\":[");
            for (int k = 0; k < n.exits().size(); k++) {
                Exit x = n.exits().get(k);
                if (k > 0) b.append(',');
                b.append("{\"to\":").append(x.to()).append(",\"lo\":").append(x.minTicks())
                        .append(",\"hi\":").append(x.maxTicks())
                        .append(",\"miss\":").append(x.unreachable()).append('}');
            }
            b.append("],\"stable\":").append(n.stable())
                    .append(",\"scoring\":").append(n.scoringNav())
                    .append(",\"rgb\":\"").append(hex(palette, n.colour())).append("\"}");
        }
        b.append("],\"arcs\":[");
        boolean first = true;
        for (Node n : nodes) {
            for (Node m : nodes) {
                if ((arcs[n.id()] & (1L << m.id())) == 0) continue;
                if (!first) b.append(',');
                first = false;
                b.append('[').append(n.id()).append(',').append(m.id()).append(']');
            }
        }
        b.append("]}");
        return b.toString();
    }

    private static String hold(Hold h) {
        return "{\"to\":" + h.to() + ",\"pure\":" + h.pure() + "}";
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> {
                    if (c < 0x20 || c > 0x7E) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String html(String title, List<Node> nodes, long[] arcs, int[] palette) {
        return TEMPLATE.replace("__DATA__", json(title, nodes, arcs, palette));
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Edge graph</title>
            <style>
              :root {
                --bg: #12141a; --panel: #1a1d26; --line: #2c313d;
                --ink: #e6e9f0; --dim: #939bad; --arc: #6b7488;
              }
              * { box-sizing: border-box; }
              body { margin: 0; background: var(--bg); color: var(--ink);
                     font: 13px/1.5 ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif; }
              header { padding: 14px 18px; border-bottom: 1px solid var(--line); }
              h1 { margin: 0 0 2px; font-size: 15px; font-weight: 600; letter-spacing: .01em; }
              .sub { color: var(--dim); font-size: 12px;
                     font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
              .wrap { display: flex; align-items: stretch; flex-wrap: wrap; }
              .stage { flex: 1 1 560px; position: relative; min-width: 320px;
                       height: calc(100vh - 62px); min-height: 520px; }
              svg { display: block; width: 100%; height: 100%; cursor: grab; }
              svg.drag { cursor: grabbing; }
              aside { flex: 0 0 300px; border-left: 1px solid var(--line); padding: 14px 16px;
                      background: var(--panel); overflow-y: auto;
                      height: calc(100vh - 62px); }
              @media (max-width: 860px) { .stage, aside { height: auto; } }
              .ctl { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 14px; }
              label { display: inline-flex; gap: 6px; align-items: center;
                      color: var(--dim); user-select: none; cursor: pointer; }
              table { width: 100%; border-collapse: collapse; font-size: 12px; }
              th { text-align: left; font-weight: 500; color: var(--dim);
                   border-bottom: 1px solid var(--line); padding: 4px 6px 6px; }
              td { padding: 4px 6px; border-bottom: 1px solid rgba(255,255,255,.05);
                   font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
              tr.hot td { background: rgba(255,255,255,.07); }
              tr { cursor: pointer; }
              .dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%;
                     vertical-align: -1px; margin-right: 6px; }
              .mix { color: #ffb454; }
              .fork { color: #fff; margin-left: 5px; font-size: 9px; vertical-align: 1px; }
              .key { display: flex; flex-wrap: wrap; gap: 4px 12px; margin: -4px 0 14px;
                     color: var(--dim); font-size: 11.5px; }
              .key span { display: inline-flex; gap: 5px; align-items: center; }
              .num { text-align: right; color: var(--dim); }
              .tip { position: absolute; pointer-events: none; background: #05070c;
                     border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px;
                     font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
                     font-size: 12px; opacity: 0; transition: opacity .1s; white-space: pre;
                     box-shadow: 0 6px 22px rgba(0,0,0,.5); z-index: 5; }
              .note { color: var(--dim); font-size: 11.5px; margin-top: 14px;
                      padding-top: 12px; border-top: 1px solid var(--line); }
              text { font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
                     pointer-events: none; }
            </style>
            </head>
            <body>
            <header><h1>Edge graph</h1><div class="sub" id="sub"></div></header>
            <div class="wrap">
              <div class="stage" id="stage">
                <svg id="svg"></svg>
                <div class="tip" id="tip"></div>
              </div>
              <aside>
                <div class="ctl">
                  <label><input type="radio" name="view" id="vnav" checked> navigational</label>
                  <label><input type="radio" name="view" id="vgeo"> geometric</label>
                  <label><input type="checkbox" id="showinv"> inverse links</label>
                </div>
                <div class="key" id="key"></div>
                <table id="tbl"><thead><tr>
                  <th>edge</th><th class="num">states</th><th>inv</th>
                  <th>L</th><th>S</th><th>R</th>
                </tr></thead><tbody></tbody></table>
                <div class="note" id="note"></div>
              </aside>
            </div>
            <script>
            const D = __DATA__;
            const svg = document.getElementById('svg'), stage = document.getElementById('stage');
            const tip = document.getElementById('tip');
            document.getElementById('sub').textContent = D.title;

            const N = D.nodes, byId = new Map(N.map(n => [n.id, n]));
            const maxStates = Math.max(1, ...N.map(n => n.states));
            const radius = n => 13 + 26 * Math.log1p(n.states) / Math.log1p(maxStates);

            // Side: one half of the plane per direction of travel, self-inverse on the axis.
            for (const n of N) {
              n.side = (n.inv < 0 || n.inv === n.id) ? 0 : (n.inv > n.id ? -1 : 1);
              n.x = 0; n.y = 0; n.vx = 0; n.vy = 0;
            }
            let W = 800, H = 560;
            function seed() {
              const per = Math.max(1, N.filter(n => n.side <= 0).length);
              let k = 0;
              for (const n of N) {
                const i = n.side === 1 && byId.has(n.inv) ? byId.get(n.inv).seedIdx : k++;
                n.seedIdx = i;
                const t = (i + 0.5) / per;
                n.x = W / 2 + n.side * W * 0.22 + (n.side === 0 ? 0 : 0);
                n.y = H * (0.15 + 0.7 * t);
              }
            }

            const arcSet = new Set(D.arcs.map(([a, b]) => a + ',' + b));
            const has = (a, b) => arcSet.has(a + ',' + b);

            // Which held turns produce each arc, and what steering it costs to take it.
            const held = new Map();
            for (const n of N) {
              for (const x of n.exits) {
                held.set(n.id + ',' + x.to, { turns: '', lo: x.lo, hi: x.hi, miss: x.miss });
              }
              for (const [k, h] of [['L', n.L], ['S', n.S], ['R', n.R]]) {
                const rec = h.to < 0 ? null : held.get(n.id + ',' + h.to);
                if (rec) rec.turns += k;
              }
            }
            const decides = n => n.L.to !== n.S.to || n.R.to !== n.S.to;
            const price = rec => rec.lo < 0 ? '\\u2205'
              : (rec.lo === rec.hi ? String(rec.lo) : rec.lo + '-' + rec.hi);

            // Navigational colour: what a boid left alone here is doing, not where it is.
            const NAV = { both: '#E0483C', scoring: '#E8892B', stable: '#EDEDED', none: '#727A8C' };
            const navOf = (stable, scoring) =>
              scoring && stable ? NAV.both : scoring ? NAV.scoring : stable ? NAV.stable : NAV.none;
            const nodeColour = n => nav() ? navOf(n.stable, n.scoring) : n.rgb;
            // An arc is stable only if both ends are, and scoring if either end is.
            const arcColour = (a, b) => {
              if (!nav()) return 'var(--arc)';
              const p = byId.get(a), q = byId.get(b);
              return navOf(p.stable && q.stable, p.scoring || q.scoring);
            };
            const straightArc = (a, b) => byId.get(a).S.to === b;
            const soleExit = a => byId.get(a).exits.length === 1;
            const nav = () => document.getElementById('vnav').checked;

            // The unsteered map, both ways, for the layout's straight-through bias.
            const outS = new Map(), inS = new Map();
            for (const n of N) {
              if (n.S.to < 0 || !byId.has(n.S.to)) continue;
              outS.set(n.id, n.S.to);
              if (!inS.has(n.S.to)) inS.set(n.S.to, []);
              inS.get(n.S.to).push(n.id);
            }

            function step() {
              const mirror = !nav();
              for (const n of N) { n.fx = 0; n.fy = 0; }
              for (let i = 0; i < N.length; i++) {
                for (let j = i + 1; j < N.length; j++) {
                  const a = N[i], b = N[j];
                  let dx = b.x - a.x, dy = b.y - a.y;
                  let d2 = dx * dx + dy * dy || 0.01;
                  const want = radius(a) + radius(b) + 46;
                  const rep = 46000 / d2;
                  const d = Math.sqrt(d2);
                  const ux = dx / d, uy = dy / d;
                  a.fx -= ux * rep; a.fy -= uy * rep;
                  b.fx += ux * rep; b.fy += uy * rep;
                  if (d < want) {                       // hard shove out of overlap
                    const push = (want - d) * 0.5;
                    a.fx -= ux * push; a.fy -= uy * push;
                    b.fx += ux * push; b.fy += uy * push;
                  }
                }
              }
              for (const [a, b] of D.arcs) {
                const p = byId.get(a), q = byId.get(b);
                if (!p || !q || p === q) continue;
                const dx = q.x - p.x, dy = q.y - p.y;
                const d = Math.hypot(dx, dy) || 0.01;
                // Unsteered travel is the spine of the picture, so it pulls harder and
                // holds a tighter length; a node with one way out keeps that way short.
                const straight = nav() && straightArc(a, b);
                let rest = radius(p) + radius(q) + 120;
                let k = 0.012;
                if (nav()) {
                  if (straight) { rest -= 26; k = 0.030; }
                  if (soleExit(a)) rest -= 34;
                }
                const f = (d - rest) * k;
                p.fx += dx / d * f; p.fy += dy / d * f;
                q.fx -= dx / d * f; q.fy -= dy / d * f;
              }

              if (nav()) {
                // Straight in and straight out want to be opposite, so a boid coasting
                // through a node goes more or less straight through the picture too. A ring
                // of these cannot all be straight and still close, and what it settles into
                // is a regular polygon.
                for (const n of N) {
                  const q = byId.get(outS.get(n.id)), ins = inS.get(n.id) || [];
                  if (!q || q === n) continue;
                  for (const pid of ins) {
                    const p = byId.get(pid);
                    if (!p || p === n) continue;
                    const a1 = Math.atan2(p.y - n.y, p.x - n.x);
                    const a2 = Math.atan2(q.y - n.y, q.x - n.x);
                    let err = a2 - a1 - Math.PI;
                    while (err > Math.PI) err -= 2 * Math.PI;
                    while (err < -Math.PI) err += 2 * Math.PI;
                    const g = 9 * err / ins.length;
                    const u = Math.atan2(p.y - n.y, p.x - n.x);
                    const v = Math.atan2(q.y - n.y, q.x - n.x);
                    p.fx += -Math.sin(u) * g * 0.5; p.fy += Math.cos(u) * g * 0.5;
                    q.fx += Math.sin(v) * g * 0.5;  q.fy += -Math.cos(v) * g * 0.5;
                  }
                  // Several unsteered routes arriving want to arrive from different sides.
                  for (let i = 0; i < ins.length; i++) {
                    for (let j = i + 1; j < ins.length; j++) {
                      const p = byId.get(ins[i]), r = byId.get(ins[j]);
                      if (!p || !r) continue;
                      const a1 = Math.atan2(p.y - n.y, p.x - n.x);
                      const a2 = Math.atan2(r.y - n.y, r.x - n.x);
                      let gap = a2 - a1;
                      while (gap > Math.PI) gap -= 2 * Math.PI;
                      while (gap < -Math.PI) gap += 2 * Math.PI;
                      const push = 3 * (gap > 0 ? 1 : -1) * Math.max(0, 1 - Math.abs(gap));
                      p.fx += Math.sin(a1) * push; p.fy += -Math.cos(a1) * push;
                      r.fx += -Math.sin(a2) * push; r.fy += Math.cos(a2) * push;
                    }
                  }
                }
              }

              for (const n of N) {
                n.fx += ((nav() ? W / 2 : W / 2 + n.side * W * 0.20) - n.x) * 0.010;
                n.fy += (H / 2 - n.y) * 0.004;
              }
              for (const n of N) {
                if (n.held || n.pinned) continue;
                n.vx = (n.vx + n.fx) * 0.82; n.vy = (n.vy + n.fy) * 0.82;
                n.x += Math.max(-14, Math.min(14, n.vx));
                n.y += Math.max(-14, Math.min(14, n.vy));
                const r = radius(n) + 6;
                n.x = Math.max(r, Math.min(W - r, n.x));
                n.y = Math.max(r, Math.min(H - r, n.y));
              }
              if (mirror) {
                const cx = W / 2, free = z => !z.held && !z.pinned;
                for (const n of N) {
                  if (n.side === 0) { if (free(n)) n.x = cx; continue; }
                  const m = byId.get(n.inv);
                  if (!m || m.id < n.id) continue;
                  const off = ((n.x - cx) - (m.x - cx)) / 2, y = (n.y + m.y) / 2;
                  if (free(n)) { n.x = cx + off; n.y = y; }
                  if (free(m)) { m.x = cx - off; m.y = y; }
                }
              }
            }

            const NS = 'http://www.w3.org/2000/svg';
            function el(name, attrs) {
              const e = document.createElementNS(NS, name);
              for (const k in attrs) e.setAttribute(k, attrs[k]);
              return e;
            }
            let hot = -1;

            function draw() {
              svg.textContent = '';
              const defs = el('defs');
              for (const c of ['arc', 'hot']) {
                const m = el('marker', { id: 'a-' + c, viewBox: '0 0 10 10', refX: 9, refY: 5,
                  markerWidth: 6, markerHeight: 6, orient: 'auto-start-reverse' });
                m.appendChild(el('path', { d: 'M 0 0 L 10 5 L 0 10 z',
                  fill: c === 'hot' ? '#fff' : 'var(--arc)' }));
                defs.appendChild(m);
              }
              svg.appendChild(defs);

              if (document.getElementById('showinv').checked) {
                for (const n of N) {
                  const m = byId.get(n.inv);
                  if (!m || m.id <= n.id) continue;
                  svg.appendChild(el('line', { x1: n.x, y1: n.y, x2: m.x, y2: m.y,
                    stroke: nav() ? '#5a6274' : n.rgb, 'stroke-width': 1.5,
                    'stroke-dasharray': '3 5', opacity: 0.55 }));
                }
              }
              const labels = [];
              for (const [a, b] of D.arcs) {
                const p = byId.get(a), q = byId.get(b);
                if (!p || !q) continue;
                const dx = q.x - p.x, dy = q.y - p.y, d = Math.hypot(dx, dy) || 1;
                const ux = dx / d, uy = dy / d;
                const bow = has(b, a) ? 15 : 0;          // both ways: bow them apart
                const nx = -uy * bow, ny = ux * bow;
                const sx = p.x + ux * radius(p), sy = p.y + uy * radius(p);
                const ex = q.x - ux * (radius(q) + 7), ey = q.y - uy * (radius(q) + 7);
                const mx = (sx + ex) / 2 + nx, my = (sy + ey) / 2 + ny;
                const live = hot === a || hot === b;
                const rec = held.get(a + ',' + b);
                const spine = nav() && straightArc(a, b);
                svg.appendChild(el('path', {
                  d: `M ${sx} ${sy} Q ${mx} ${my} ${ex} ${ey}`,
                  fill: 'none', stroke: live ? '#fff' : arcColour(a, b),
                  'stroke-width': live ? 3.4 : (spine ? 3.0 : rec ? 1.6 : 1.0),
                  opacity: live ? 1 : (spine ? 0.95 : rec ? 0.6 : 0.3),
                  'stroke-dasharray': rec ? '' : '2 4',
                  'marker-end': `url(#a-${live ? 'hot' : 'arc'})` }));
                if (!rec) continue;
                // A third of the way along the curve, not the middle: a long arc's midpoint
                // often lands on whatever node it passes, and this also puts each label
                // nearest the edge it belongs to, so a two-way pair reads as two labels.
                const u1 = 0.35, w0 = (1 - u1) * (1 - u1), w1 = 2 * u1 * (1 - u1), w2 = u1 * u1;
                labels.push({ x: w0 * sx + w1 * mx + w2 * ex,
                              y: w0 * sy + w1 * my + w2 * ey, live,
                              text: (rec.turns ? rec.turns + ' ' : '') + price(rec)
                                    + (rec.miss ? '*' : '') });
              }
              // Every arc is down before any label goes on, or a later arc paints over an
              // earlier label and eats a character out of the middle of it.
              for (const l of labels) {
                const t = el('text', { x: l.x, y: l.y + 4, 'text-anchor': 'middle',
                  'font-size': 12, 'font-weight': 700,
                  fill: l.live ? '#fff' : '#d4dae8', stroke: 'var(--bg)', 'stroke-width': 5.5,
                  'paint-order': 'stroke' });
                t.textContent = l.text;
                svg.appendChild(t);
              }
              for (const n of N) {
                const r = radius(n), fill = nodeColour(n);
                const g = el('g', { style: 'cursor:pointer' });
                g.appendChild(el('circle', { cx: n.x, cy: n.y, r,
                  fill, stroke: hot === n.id ? '#fff' : 'rgba(0,0,0,.55)',
                  'stroke-width': hot === n.id ? 3 : 1.5 }));
                if (n.pinned) {
                  g.appendChild(el('circle', { cx: n.x + r * 0.72, cy: n.y - r * 0.72, r: 3.5,
                    fill: '#fff', stroke: 'var(--bg)', 'stroke-width': 1.5 }));
                }
                if (n.inZone > 0) {
                  g.appendChild(el('circle', { cx: n.x, cy: n.y, r: r - 5, fill: 'none',
                    stroke: 'rgba(0,0,0,.5)', 'stroke-width': 1.5,
                    'stroke-dasharray': '2 3' }));
                }
                if (decides(n)) {                       // the turn on arrival changes the exit
                  // Contrasting against the node, not the background: in the navigational
                  // view a stable node is already white and a white ring vanishes on it.
                  g.appendChild(el('circle', { cx: n.x, cy: n.y, r: r + 5, fill: 'none',
                    stroke: dark(fill) ? '#fff' : '#1b1e26', 'stroke-width': 2, opacity: 0.9 }));
                }
                const t = el('text', { x: n.x, y: n.y + 4, 'text-anchor': 'middle',
                  'font-size': 13, 'font-weight': 600,
                  fill: dark(fill) ? '#fff' : '#000' });
                t.textContent = n.id;
                g.appendChild(t);
                g.addEventListener('pointerenter', () => { hot = n.id; sync(); showTip(n); });
                g.addEventListener('pointerleave', () => { hot = -1; sync(); tip.style.opacity = 0; });
                g.addEventListener('pointerdown', ev => grab(ev, n));
                g.addEventListener('dblclick', () => { n.pinned = false; wake(); });
                svg.appendChild(g);
              }
            }

            function dark(rgb) {
              const v = parseInt(rgb.slice(1), 16);
              const l = 0.299 * (v >> 16 & 255) + 0.587 * (v >> 8 & 255) + 0.114 * (v & 255);
              return l < 150;
            }

            function showTip(n) {
              const pct = n.states ? (100 * n.inZone / n.states).toFixed(1) : '0.0';
              const way = (name, h) => `${name}   ${h.to < 0 ? 'mixed' : '-> ' + h.to}`;
              const exits = n.exits.map(x => {
                const rec = held.get(n.id + ',' + x.to);
                const turns = rec && rec.turns ? ' (' + rec.turns + ')' : '';
                return `   -> ${x.to}${turns}   ${x.lo < 0 ? 'unreachable'
                  : (x.lo === x.hi ? x.lo : x.lo + '-' + x.hi) + ' ticks'}` +
                  (x.miss ? `   ${x.miss} inbound cannot` : '');
              }).join('\\n');
              tip.textContent =
                `edge ${n.id}\\n` +
                `states    ${n.states.toLocaleString()}\\n` +
                `scoring   ${n.inZone.toLocaleString()} (${pct}%)\\n` +
                `reaches A ${n.reachesA.toLocaleString()}\\n` +
                `bounds    x ${n.x0}-${n.x1}  y ${n.y0}-${n.y1}\\n` +
                `inverse   ${n.inv < 0 ? 'none' : n.inv}` +
                (n.inv === n.id ? '  (self)' : '') +
                `  ${(100 * n.purity).toFixed(1)}% pure\\n` +
                `\\ninbound   ${n.inbound.toLocaleString()} points\\n` +
                way('left  ', n.L) + '\\n' + way('ahead ', n.S) + '\\n' + way('right ', n.R) +
                `\\n\\ncost to leave by\\n` + exits +
                `\\n\\nfrom      ${D.arcs.filter(a => a[1] === n.id).map(a => a[0]).join(', ') || '-'}`;
              const r = stage.getBoundingClientRect();
              tip.style.opacity = 1;
              const tw = tip.offsetWidth, th = tip.offsetHeight;
              tip.style.left = Math.max(6, Math.min(r.width - tw - 6, n.x + 22)) + 'px';
              tip.style.top = Math.max(6, Math.min(r.height - th - 6, n.y - th / 2)) + 'px';
            }

            /**
             * Drag to pin, double-click to release.
             * <p>
             * The pointer is captured on the svg rather than on the node, because every
             * frame throws the nodes away and rebuilds them: the element the press landed on
             * is gone a frame later, and the release it was holding capture for went with it.
             * The svg outlives the redraw, so it can be trusted to see the release.
             */
            function grab(ev, n) {
              n.held = true;
              n.pinned = true;
              svg.classList.add('drag');
              svg.setPointerCapture(ev.pointerId);
              wake();
              const move = e => {
                const r = svg.getBoundingClientRect();
                n.x = (e.clientX - r.left) * (W / r.width);
                n.y = (e.clientY - r.top) * (H / r.height);
                wake();
              };
              const up = e => {
                if (e.pointerId !== ev.pointerId) return;
                n.held = false;                      // stays pinned where it was dropped
                svg.classList.remove('drag');
                if (svg.hasPointerCapture(e.pointerId)) svg.releasePointerCapture(e.pointerId);
                svg.removeEventListener('pointermove', move);
                svg.removeEventListener('pointerup', up);
                svg.removeEventListener('pointercancel', up);
                wake();
              };
              svg.addEventListener('pointermove', move);
              svg.addEventListener('pointerup', up);
              svg.addEventListener('pointercancel', up);
              ev.preventDefault();
            }

            const tbody = document.querySelector('#tbl tbody');
            function table() {
              tbody.textContent = '';
              for (const n of N) {
                const tr = document.createElement('tr');
                tr.dataset.id = n.id;
                const cell = h => h.to < 0 ? '<span class="mix">mix</span>' : h.to;
                tr.innerHTML =
                  `<td><span class="dot" style="background:${nodeColour(n)}"></span>${n.id}` +
                  `${decides(n) ? '<span class="fork" title="the turn on arrival decides">' +
                    '&#9670;</span>' : ''}</td>` +
                  `<td class="num">${n.states.toLocaleString()}</td>` +
                  `<td>${n.inv < 0 ? '-' : (n.inv === n.id ? n.inv + '&#8226;' : n.inv)}</td>` +
                  `<td>${cell(n.L)}</td><td>${cell(n.S)}</td><td>${cell(n.R)}</td>`;
                tr.addEventListener('pointerenter', () => { hot = n.id; sync(); showTip(n); });
                tr.addEventListener('pointerleave', () => { hot = -1; sync(); tip.style.opacity = 0; });
                tbody.appendChild(tr);
              }
              const swatch = (c, t) => `<span><i class="dot" style="background:${c}"></i>${t}</span>`;
              document.getElementById('key').innerHTML = nav()
                ? swatch(NAV.stable, 'stable') + swatch(NAV.scoring, 'scoring') +
                  swatch(NAV.both, 'both') + swatch(NAV.none, 'neither')
                : `<span>colour pairs each edge with its inverse</span>`;

              const self = N.filter(n => n.inv === n.id).length;
              const pairs = N.filter(n => n.inv > n.id).length;
              const forks = N.filter(decides);
              document.getElementById('note').innerHTML =
                (nav()
                  ? `<b>Navigational view.</b> Colour is what a boid left alone here is doing: ` +
                    `<b>stable</b> means unsteered travel comes back round without ever ` +
                    `scoring, <b>scoring</b> means it has scored or cannot avoid it. An arc ` +
                    `is stable only if both its ends are, and scoring if either is. Thick ` +
                    `arcs are unsteered travel — the route a boid takes with nothing ` +
                    `steering it.<br><br>`
                  : `<b>Geometric view.</b> Colour pairs each edge with its inverse, and ` +
                    `inverse pairs are laid out mirrored about the centre line.<br><br>`) +
                `${N.length} edges, ${D.arcs.length} arcs. ${pairs} inverse pair` +
                `${pairs === 1 ? '' : 's'}, ${self} self-inverse.<br><br>` +
                `Arc labels are the price of taking that way out: the fewest ticks a boid ` +
                `must ask for a turn rather than straight, counted before the collision veto ` +
                `sees the request. The ticks need not be consecutive or agree in direction. ` +
                `Letters before the number say which single held turn also goes that way — ` +
                `<b>L</b>eft, <b>S</b>traight, <b>R</b>ight. A range covers the easiest and ` +
                `hardest inbound point; <b>*</b> means some inbound point cannot get there ` +
                `at all.<br><br>` +
                (forks.length
                  ? `A white ring marks an edge where the turn on arrival changes the exit: ` +
                    forks.map(n => n.id).join(', ') + `. Everywhere else all three turns ` +
                    `come out the same way, so a boid there has no say in where it goes next.`
                  : `No edge here lets the turn on arrival change the exit — every edge sends ` +
                    `a boid the same way whichever way it tries to turn.`) +
                `<br><br>A dashed ring marks an edge holding scoring states. Node area ` +
                `follows state count on a log scale. Drag a node to pin it — a pinned node ` +
                `carries a white dot; double-click it to let go.`;
            }
            function sync() {
              for (const tr of tbody.children) tr.classList.toggle('hot', +tr.dataset.id === hot);
              draw();
            }

            function resize() {
              const r = svg.getBoundingClientRect();
              if (!r.width || !r.height) return;
              W = Math.max(360, r.width); H = Math.max(360, r.height);
              svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
              wake();
            }
            new ResizeObserver(resize).observe(svg);

            // The layout settles and then stops. Anything that can disturb it wakes it again,
            // so an idle page costs nothing while a dragged node still follows the pointer.
            let idle = 0, running = false;
            function wake() { idle = 0; if (!running) { running = true; requestAnimationFrame(loop); } }
            function loop() {
              const before = N.map(n => [n.x, n.y]);
              step();
              let moved = 0;
              N.forEach((n, i) => { moved += Math.abs(n.x - before[i][0]) + Math.abs(n.y - before[i][1]); });
              draw();
              idle = moved < 0.4 && !N.some(n => n.held) ? idle + 1 : 0;
              if (idle > 30) { running = false; return; }
              requestAnimationFrame(loop);
            }

            for (const id of ['vnav', 'vgeo', 'showinv']) {
              document.getElementById(id).addEventListener('change', () => {
                for (const n of N) { n.pinned = false; n.vx = 0; n.vy = 0; }
                seed(); table(); draw(); wake();
              });
            }
            resize(); seed(); table();
            for (let i = 0; i < 600; i++) step();       // settle before the first paint
            draw();
            wake();
            </script>
            </body>
            </html>
            """;
}
