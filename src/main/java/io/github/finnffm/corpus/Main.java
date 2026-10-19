package io.github.finnffm.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

public final class Main {
  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
    }
  }

  static void run(String[] args) throws Exception {
    if (args.length == 0 || args[0].equals("--help")) {
      System.out.println(
          "parliament-corpus\n  import <file.xml|directory> <corpus.db>\n  stats <corpus.db>\n  export <corpus.db> <speeches.jsonl>\nDirectories are scanned for XML files recursively. Each session replaces its previous version atomically.");
      return;
    }
    String command = args[0];
    if (!Set.of("import", "stats", "export").contains(command)
        || args.length != (command.equals("stats") ? 2 : 3))
      throw new IllegalArgumentException("Invalid arguments; use --help");
    Path dbPath = Path.of(args[command.equals("import") ? 2 : 1]);
    if (!command.equals("import") && !Files.isRegularFile(dbPath))
      throw new IllegalArgumentException("Database not found: " + dbPath);
    try (var store = new CorpusStore(dbPath)) {
      switch (command) {
        case "import" -> {
          Path input = Path.of(args[1]);
          List<Path> files;
          if (Files.isDirectory(input)) {
            try (var paths = Files.walk(input)) {
              files =
                  paths
                      .filter(Files::isRegularFile)
                      .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                      .sorted()
                      .toList();
            }
          } else if (Files.isRegularFile(input)) files = List.of(input);
          else throw new IllegalArgumentException("Input does not exist: " + input);
          if (files.isEmpty()) throw new IllegalArgumentException("No XML files found");
          int failed = 0, speeches = 0;
          Set<String> importedSessions = new HashSet<>();
          var parser = new TranscriptParser();
          for (var file : files) {
            try {
              var t = parser.parse(file);
              store.replace(t);
              if (!importedSessions.add(t.id())) {
                System.err.println(
                    "Repeated session " + t.id() + ": replaced by " + file.getFileName());
              }
              speeches += t.speeches().size();
              System.out.printf("Imported %s: %d speeches%n", t.id(), t.speeches().size());
            } catch (Exception e) {
              failed++;
              System.err.println(file.getFileName() + ": " + e.getMessage());
            }
          }
          System.out.printf(
              "%d files processed, %d speech records processed, %d failures%n",
              files.size(), speeches, failed);
          if (failed > 0)
            throw new IllegalStateException("Some files failed; successful sessions were kept");
        }
        case "stats" ->
            System.out.println(
                new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(store.stats()));
        case "export" -> {
          Path output = Path.of(args[2]);
          if (Files.exists(output))
            throw new IllegalArgumentException("Output already exists: " + output);
          try (var writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW)) {
            store.export(writer);
          }
          System.out.println("Exported " + output);
        }
      }
    }
  }
}
