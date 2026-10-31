package gr.gousiosg.javacg.cli;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Writes command output to {@code --output <file>} or to stdout. */
final class Output {

    private Output() {
    }

    static void write(String file, PrintStream stdout, Consumer<Writer> body) throws IOException {
        if (file == null) {
            Writer w = new OutputStreamWriter(stdout, StandardCharsets.UTF_8);
            body.accept(w);
            w.flush();
            return;
        }
        Path p = Path.of(file);
        if (p.toAbsolutePath().getParent() != null) {
            Files.createDirectories(p.toAbsolutePath().getParent());
        }
        try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            body.accept(w);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
