package io.github.rmkrdev.gateway.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code file} sink: one set of files per UTC day under {@code gateway.eval.file.path},
 * either {@code eval-samples-<date>.jsonl} with one {@link EvalSample} per line, or
 * {@code fixtures-<date>/} with harness fixture directories ready for
 * {@code llm-eval --fixtures}. Only one replica should write to a shared directory.
 */
public class FileEvalSink implements EvalSink {

    private static final Pattern DATED = Pattern.compile("(?:eval-samples-|fixtures-)(\\d{4}-\\d{2}-\\d{2})(?:\\.jsonl)?");

    private final Path root;
    private final EvalProperties.FileFormat format;
    private final JsonMapper json;
    private final HarnessFixtures fixtures;
    private final Clock clock;

    public FileEvalSink(EvalProperties.File file, JsonMapper json, HarnessFixtures fixtures, Clock clock) {
        this.root = file.path();
        this.format = file.format();
        this.json = json;
        this.fixtures = fixtures;
        this.clock = clock;
    }

    @Override
    public String name() {
        return EvalProperties.FILE;
    }

    @Override
    public synchronized void write(List<EvalSample> samples) {
        String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        try {
            Files.createDirectories(root);
            if (format == EvalProperties.FileFormat.FIXTURES) {
                fixtures.write(samples, root.resolve("fixtures-" + day));
                return;
            }
            try (Writer out = Files.newBufferedWriter(root.resolve("eval-samples-" + day + ".jsonl"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (EvalSample sample : samples) {
                    out.write(json.writeValueAsString(sample));
                    out.write('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes daily files and fixture directories for days before the cutoff's day. */
    public synchronized int deleteBefore(Instant cutoff) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        LocalDate first = LocalDate.ofInstant(cutoff, ZoneOffset.UTC);
        int deleted = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                Matcher m = DATED.matcher(entry.getFileName().toString());
                if (m.matches() && day(m.group(1)) != null && day(m.group(1)).isBefore(first)) {
                    delete(entry);
                    deleted++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return deleted;
    }

    private static LocalDate day(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void delete(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (Stream<Path> tree = Files.walk(path)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
