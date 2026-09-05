package boids;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Content-addressed map versions.
 * <p>
 * A map PNG is a design document: it gets edited, and every edit silently changes the
 * physics. A label that replays against "areas/dab.png" therefore does not name a map at
 * all, it names whatever is on disk at the moment — which is how {@code outlooped.png}
 * came to invalidate every label referring to it four times in one afternoon without
 * anything failing.
 * <p>
 * So the mutable PNG stops being the thing the simulation reads. Editing happens in
 * {@code areas/<name>/<name>.png}; using a map takes a hash of its pixel content and
 * copies it, once, into {@code ingests/<hash>/}. Everything downstream — the navmap, the
 * renderer, every analysis artifact — reads and writes inside that folder. The hash names
 * a specific play area for as long as the folder exists, and an old hash keeps working
 * after the source moves on.
 * <p>
 * The cost is that a hash is unreadable, so each map folder carries an {@code ingests.txt}
 * history and a {@code latest} directory link into the newest ingest. Those are for
 * finding things by hand; nothing in the code follows them.
 * <p>
 * Every operation here is idempotent. Calling {@link #open} on an unchanged map does
 * nothing but return where its files already are, so it is safe on any path, in any
 * order, as often as you like.
 */
public final class MapStore {
    private MapStore() {}

    public static final Path AREAS = Path.of("areas");
    public static final Path INGESTS = Path.of("ingests");

    /** Hex characters of the digest used as a folder name. 64 bits. */
    private static final int SHORT = 16;

    private static final String LINK = "latest";
    private static final String HISTORY = "ingests.txt";

    private static final Map<String, Ingest> CACHE = new HashMap<>();

    /**
     * The options that change what a map becomes, beyond its pixels.
     * <p>
     * Radius and trap-trimming alter the display image, so the content hash separates their
     * results; they are recorded here so a hash can be explained, not so it can be computed.
     * <p>
     * ⚠ <b>{@link Params#PHYSICS} is in {@link #key} and is NOT in the content hash.</b> It
     * changes neither image, so it cannot reach the folder name that way — the claim that the
     * content hash already separates every build option was true of the others and false of
     * this one, and it is why derived output moved under {@link Derived}, which addresses the
     * physics version explicitly. {@link #key} is the in-process cache key and nothing more.
     */
    public record Build(int radius, NavMapBuilder.Navigability navigability, boolean trimTraps) {

        /** What a map is built as unless something specifically wants otherwise. */
        public static Build of(int radius) {
            return new Build(radius, NavMapBuilder.Navigability.BIDIRECTIONAL, true);
        }

        String key() {
            return radius + "/" + navigability + "/" + trimTraps + "/p" + Params.PHYSICS;
        }
    }

    /**
     * One immutable version of one map.
     *
     * @param name    the map's name, without extension
     * @param hash    short content hash, and the ingest folder's name
     * @param dir     the ingest folder — where analysis output for this version belongs
     * @param png     the frozen map, and the only thing physics may be computed from
     * @param display the same map with dead pixels shown as wall; for rendering only
     * @param source  the mutable PNG this was taken from, which is what a human edits
     */
    public record Ingest(String name, String hash, Path dir, Path png, Path display, Path source) {

        /**
         * A file inside this version's folder, with its parent directories created.
         * <p>
         * <b>Not for derived analysis</b> — see {@link #mapOwnDir}. Addressed by the map's
         * pixels alone, so two runs at different constants collide silently.
         */
        Path mapOwnFile(String... parts) {
            Path p = resolveUnder(parts);
            create(p.getParent());
            return p;
        }

        /**
         * A directory inside this version's folder, created if it is not there.
         * <p>
         * <b>Not for derived analysis.</b> A path under the ingest alone is addressed by the
         * map's pixels and nothing else, so two analyses run at different constants land on the
         * same one and the second silently replaces the first. That is not hypothetical: it is
         * what {@code solver/facts.bin} and {@code psyboid/plans.tsv} did until 2026-09-04.
         * Anything computed from the map belongs under {@link Derived}, which addresses it by
         * everything it is a function of. This is left for the map's own files.
         */
        Path mapOwnDir(String... parts) {
            Path p = resolveUnder(parts);
            create(p);
            return p;
        }

        private Path resolveUnder(String... parts) {
            Path p = dir;
            for (String part : parts) p = p.resolve(part);
            return p;
        }

        private static void create(Path p) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String toString() { return name + "@" + hash; }
    }

    /**
     * The current version of a map, preparing whatever is missing.
     * <p>
     * Adopts a loose {@code areas/<name>.png} into its own folder if that is where the
     * map still lives, ingests the pixel content if this exact content has not been seen,
     * appends to the history if the version is new, and repoints the {@code latest} link.
     * Any of those steps that has already happened is skipped.
     */
    public static synchronized Ingest open(String name, Build build) throws IOException {
        String key = name + "|" + build.key();
        Ingest cached = CACHE.get(key);
        if (cached != null && Files.isDirectory(cached.dir())) return cached;

        Path dir = AREAS.resolve(name);
        Path source = dir.resolve(name + ".png");
        Path loose = AREAS.resolve(name + ".png");

        if (Files.isDirectory(dir)) {
            if (!Files.isRegularFile(source)) {
                throw new NoSuchFileException(source.toString(),
                        null, "map folder exists but holds no " + name + ".png");
            }
            if (Files.isRegularFile(loose)) {
                throw new FileAlreadyExistsException(loose.toString(), null,
                        "two sources for map '" + name + "': " + loose + " and " + source
                                + " — delete or rename one, this tool will not guess");
            }
        } else if (Files.isRegularFile(loose)) {
            Files.createDirectories(dir);
            Files.move(loose, source);
        } else {
            throw new NoSuchFileException(loose.toString(), null,
                    "no map named '" + name + "'");
        }

        BufferedImage img = read(source);
        BufferedImage shown = blankTraps(img, build);
        // Digesting both means the build options need not be hashed separately: they are
        // what turns the first image into the second, so any option that changes anything
        // changes the display map and lands in a different folder.
        byte[] digest = digest(img, shown);
        String hash = hex(digest).substring(0, SHORT);

        Path ingest = INGESTS.resolve(hash);
        Path frozen = ingest.resolve("map.png");
        Path shownPath = ingest.resolve("display.png");
        if (!Files.isRegularFile(frozen)) {
            Files.createDirectories(ingest);
            Files.copy(source, frozen, StandardCopyOption.REPLACE_EXISTING);
            ImageIO.write(shown, "png", shownPath.toFile());
            Files.writeString(ingest.resolve("meta.txt"),
                    meta(name, hash, digest, img, shown, source, build));
        }

        recordHistory(dir.resolve(HISTORY), name, hash, img, shown, build);
        linkLatest(dir.resolve(LINK), ingest);

        Ingest result = new Ingest(name, hash, ingest, frozen, shownPath, source);
        CACHE.put(key, result);
        return result;
    }

    /**
     * Paints pixels no boid can occupy on any heading as wall, for display.
     * <p>
     * The result is <b>not</b> a map. Feeding it back through the builder gives a
     * different and smaller navigable set, because a dead pixel is routinely load bearing:
     * dead pixels form a band hugging every wall — in play, but too late to turn away —
     * and a boid flying along a wall sweeps its four-pixel step through that band. Blank
     * it and the step is blocked. Measured on dab at radius 40, 1,565 dead pixels cost
     * 19,422 live states, a tenth of the kernel; blossom and hamburger behave the same way.
     * <p>
     * So the order is not a precaution, it is the design: navigability is computed from
     * the untouched image, this runs afterwards, and what it produces is only ever
     * rendered. {@code map.png} stays authoritative and {@code display.png} never reaches
     * the physics layer.
     */
    private static BufferedImage blankTraps(BufferedImage img, Build build) {
        if (!build.trimTraps()) return img;

        int w = img.getWidth(), h = img.getHeight();
        boolean[] oob = new boolean[w * h];
        int[] score = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                oob[x + y * w] = rgb == 0x000000;
                score[x + y * w] = rgb == 0xFF7F27 ? 1 : 0;
            }
        }
        NavMap before = NavMapBuilder.build(oob, score, w, h, build.radius(), build.navigability());

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        boolean[] trimmed = new boolean[w * h];
        int traps = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean trap = !oob[x + y * w] && before.liveHeadings(x, y) == 0;
                if (trap) traps++;
                trimmed[x + y * w] = oob[x + y * w] || trap;
                out.setRGB(x, y, trap ? 0x000000 : img.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return traps == 0 ? img : out;
    }

    /**
     * Where the pixels for a named map actually live.
     * <p>
     * A path already inside an ingest is returned unchanged, which is the case that
     * matters: {@link ScenarioParameter#mapPath()} hands one over and this must not
     * second-guess it. Anything else names the mutable source, and is resolved to wherever
     * that now sits — which keeps a hand-written {@code areas/dab.png} working after the
     * file has been adopted into its folder.
     * <p>
     * This deliberately does not build an ingest. What gets ingested depends on the
     * turning radius and the navigability rule, and a bare path carries neither; guessing
     * would silently produce a map trimmed for the wrong radius. Callers that want the
     * frozen map ask {@link #open} for it.
     */
    public static Path resolve(Path mapPath) {
        if (mapPath == null) return null;
        for (Path part : mapPath.toAbsolutePath()) {
            if (part.toString().equals(INGESTS.toString())) return mapPath;
        }
        String file = mapPath.getFileName().toString();
        String name = file.endsWith(".png") ? file.substring(0, file.length() - 4) : file;
        Path inFolder = AREAS.resolve(name).resolve(name + ".png");
        return Files.isRegularFile(inFolder) ? inFolder : mapPath;
    }

    /** The column header a given line format is described by. */
    private static final String COLUMNS =
            "# hash             radius nav    trim phys  width height    oob scoring   open   dead";

    /**
     * Records this build, unless the identical line is already there.
     * <p>
     * Lines carry no timestamp, which is what lets the decision be a plain search for the
     * fully formatted line: same map, same options, same result, nothing to say. When the
     * format changes the old lines are left exactly as they are and a fresh column header
     * is written above the first line in the new shape, so a file can hold several
     * generations — including two differently-shaped lines for one hash — and still be
     * readable top to bottom.
     */
    private static void recordHistory(Path history, String name, String hash,
                                      BufferedImage img, BufferedImage shown, Build build)
            throws IOException {
        int[] c = counts(img);
        String line = "%-16s %6d %-6s %-4s %-5s %5d %6d %6d %7d %6d %6d".formatted(
                hash, build.radius(),
                build.navigability() == NavMapBuilder.Navigability.BIDIRECTIONAL ? "both" : "fwd",
                build.trimTraps() ? "yes" : "no", "p" + Params.PHYSICS,
                img.getWidth(), img.getHeight(), c[0], c[1], c[2],
                counts(shown)[0] - c[0]);

        List<String> lines = Files.isRegularFile(history)
                ? Files.readAllLines(history) : new ArrayList<>();
        String lastColumns = null;
        for (String existing : lines) {
            if (existing.strip().equals(line.strip())) return;
            if (existing.startsWith("# hash")) lastColumns = existing;
        }

        StringBuilder add = new StringBuilder();
        if (lines.isEmpty()) {
            Files.createDirectories(history.getParent());
            add.append("""
                    # Ingest history for %s. Newest last; one line per distinct build.
                    # Folders live in ingests/<hash>/. The options below all change what
                    # gets ingested, so the hash already tells them apart; they are here
                    # to say which is which, not to identify anything.
                    #
                    # 'dead' counts pixels no boid can occupy. They are wall in display.png
                    # and untouched in map.png, which is what physics reads.
                    #
                    """.formatted(name));
        }
        if (!COLUMNS.equals(lastColumns)) add.append(COLUMNS).append(System.lineSeparator());
        add.append(line).append(System.lineSeparator());

        Files.writeString(history, add.toString(), lines.isEmpty()
                ? StandardOpenOption.CREATE : StandardOpenOption.APPEND);
    }

    /**
     * Points {@code latest} at an ingest.
     * <p>
     * A real symbolic link needs administrator rights on Windows, a directory junction
     * does not, and neither exists if the filesystem refuses both — so this degrades to a
     * plain text pointer rather than failing a run over a convenience feature.
     */
    private static void linkLatest(Path link, Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        try {
            if (Files.exists(link) && Files.isSameFile(link, absolute)) return;
        } catch (IOException ignored) {
            // Broken link, or a link to a folder that no longer exists. Replace it.
        }
        Files.deleteIfExists(link);

        try {
            Files.createSymbolicLink(link, absolute);
            return;
        } catch (IOException | UnsupportedOperationException e) {
            // Unprivileged Windows. Fall through to a junction.
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        link.toAbsolutePath().toString(), absolute.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) return;
            } catch (IOException | InterruptedException ignored) {
                // Fall through to the text pointer.
            }
        }
        Files.writeString(link.resolveSibling(LINK + ".txt"), absolute + System.lineSeparator());
    }

    /** {@code oob}, {@code scoring}, {@code open} pixel counts — a legible fingerprint. */
    private static int[] counts(BufferedImage img) {
        int oob = 0, scoring = 0, open = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (rgb == 0x000000) oob++;
                else if (rgb == 0xFF7F27) scoring++;
                else open++;
            }
        }
        return new int[]{oob, scoring, open};
    }

    /**
     * Hashes what the map means, not how the PNG was written.
     * <p>
     * Re-saving a PNG changes its bytes — compression level, chunk order, an embedded
     * timestamp — without changing a single pixel. Digesting the decoded raster instead
     * means an ingest is created when the play area actually changes and not otherwise.
     */
    private static byte[] digest(BufferedImage... images) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
        for (BufferedImage img : images) {
            int w = img.getWidth(), h = img.getHeight();
            md.update(("boids-map-v1 " + w + "x" + h + "\n").getBytes(StandardCharsets.UTF_8));
            byte[] row = new byte[w * 3];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    row[x * 3] = (byte) (rgb >> 16);
                    row[x * 3 + 1] = (byte) (rgb >> 8);
                    row[x * 3 + 2] = (byte) rgb;
                }
                md.update(row);
            }
        }
        return md.digest();
    }

    private static String meta(String name, String hash, byte[] digest, BufferedImage img,
                               BufferedImage shown, Path source, Build build) {
        int[] c = counts(img);
        return """
                map        %s
                hash       %s
                sha256     %s
                size       %dx%d
                oob        %d
                scoring    %d
                open       %d
                dead       %d (shown as wall in display.png)
                radius     %d
                navigable  %s
                physics    %d
                ingested   %s
                source     %s

                map.png is this map. It does not change, and it is the only file here that
                physics may be computed from. display.png is the same map with pixels no
                boid can occupy painted as wall; it is for rendering and reading, and
                rebuilding a navmap from it would give a smaller and wrong answer, because
                dead pixels lie on the swept paths of live ones.

                Anything derived from this version of %s — navmaps, renders, route traces,
                edge maps, analysis — belongs in here, and nothing in here refers to a
                version that is not this one.
                """.formatted(name, hash, hex(digest), img.getWidth(), img.getHeight(),
                c[0], c[1], c[2], counts(shown)[0] - c[0], build.radius(), build.navigability(),
                Params.PHYSICS,
                Instant.now().truncatedTo(ChronoUnit.SECONDS), source, name);
    }

    private static BufferedImage read(Path png) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("not a readable image: " + png);
        return img;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /** Ingests every map a preset names, leaving anything else in {@code areas/} alone. */
    public static void main(String[] args) throws IOException {
        Set<String> before = new HashSet<>();
        if (Files.isDirectory(INGESTS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(INGESTS)) {
                for (Path p : ds) before.add(p.getFileName().toString());
            }
        }
        System.out.printf("%-12s %-16s %-6s %-7s %s%n", "map", "hash", "radius", "state", "ingest");
        for (PresetScenarioParameter preset : PresetScenarioParameter.values()) {
            String file = preset.sourceName();
            String name = file.substring(0, file.length() - 4);
            int radius = Math.round(preset.turningRadius());
            boolean adopted = Files.isRegularFile(AREAS.resolve(file));
            try {
                Ingest ingest = open(name, Build.of(radius));
                boolean fresh = !before.contains(ingest.hash());
                System.out.printf("%-12s %-16s %-6d %-7s %s%n", name, ingest.hash(), radius,
                        adopted ? "moved" : (fresh ? "built" : "ok"), ingest.dir());
            } catch (IOException e) {
                System.out.printf("%-12s %-16s %-6d %-7s %s%n", name, "-", radius, "FAILED",
                        e.getMessage());
            }
        }
    }
}
