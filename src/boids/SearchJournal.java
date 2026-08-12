package boids;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records the canonical line of every search that runs, whether or not anyone asked.
 * <p>
 * A canonical label is a complete record of a timeline: seed plus overrides, a kilobyte or
 * so, replayable bit for bit. A search that produced one and then dropped it has thrown
 * away minutes of compute that cannot be recovered except by paying for it again — and
 * worse, a question thought of afterwards ("what was the psyboid's distance from the flock
 * in that sweep?") becomes unanswerable rather than merely unanswered.
 * <p>
 * Registration happens inside {@link PsyboidSearch}'s constructor and the write happens on
 * a JVM shutdown hook, so there is no call site to forget. Normal exit, an uncaught
 * exception and Ctrl-C all flush. Only {@code Runtime.halt} or a kill signal escape it.
 */
public final class SearchJournal {

    private static final Path FILE = Path.of("data", "searches.tsv");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER = String.join("\t",
            "when", "context", "seed", "psyboid", "shape", "lookahead", "alpha",
            "spacing", "maxDelay", "duration", "segments", "enumerated", "commits", "label");

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static volatile String context = "-";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(SearchJournal::flush, "search-journal"));
    }

    private SearchJournal() {}

    /**
     * Labels what the searches from here on are for. Free text, and purely descriptive:
     * forgetting to set it costs a useful column, never the label itself.
     */
    public static void context(String what) {
        context = what == null || what.isBlank() ? "-" : what.replace('\t', ' ');
    }

    /** Opens an entry. Called by {@link PsyboidSearch}; nothing else should need to. */
    static Entry open(PsyboidSearch.Config config, long seed, String startLabel) {
        Entry e = new Entry(context, config, seed, startLabel);
        ENTRIES.add(e);
        return e;
    }

    /**
     * Appends every entry not yet written. Idempotent, and safe to call early if a long
     * sweep should not wait for exit to become inspectable.
     */
    public static void flush() {
        if (ENTRIES.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int written = 0;
        for (Entry e : ENTRIES) {
            if (!e.written.compareAndSet(false, true)) continue;
            sb.append(e.row()).append('\n');
            written++;
        }
        if (written == 0) return;

        try {
            if (FILE.getParent() != null) Files.createDirectories(FILE.getParent());
            boolean fresh = !Files.exists(FILE);
            try (var out = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) out.write(HEADER + "\n");
                out.write(sb.toString());
            }
            if (FLUSHED.compareAndSet(false, true)) {
                System.err.printf("[journal] %d canonical line(s) -> %s%n", written, FILE);
            }
        } catch (IOException ex) {
            // A journal that kills the run it was meant to protect is worse than no
            // journal, so this reports and gives up rather than propagating.
            System.err.println("[journal] could not write " + FILE + ": " + ex.getMessage());
        }
    }

    /** One search's record. The label is replaced as the search commits; nothing else moves. */
    static final class Entry {
        private final String when = LocalDateTime.now().format(STAMP);
        private final String context;
        private final PsyboidSearch.Config config;
        private final long seed;
        private final AtomicBoolean written = new AtomicBoolean();

        private volatile String label;
        private volatile int commits;

        Entry(String context, PsyboidSearch.Config config, long seed, String label) {
            this.context = context;
            this.config = config;
            this.seed = seed;
            this.label = label;
        }

        void record(String label) {
            this.label = label;
            this.commits++;
        }

        String row() {
            return String.join("\t",
                    when, context, Long.toString(seed), Integer.toString(config.psyboid()),
                    config.describe(), Integer.toString(config.lookahead()),
                    String.format("%.2f", config.alpha()),
                    Integer.toString(config.splitSpacing()),
                    Integer.toString(config.maxDelay()), Integer.toString(config.duration()),
                    Integer.toString(config.segments()),
                    config.plan() == null ? "no" : "yes",
                    Integer.toString(commits), label);
        }
    }
}
