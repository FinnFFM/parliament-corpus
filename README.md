# Parliament Corpus

Turn Bundestag plenary XML transcripts into a searchable local corpus. Import sessions, keep speech and speaker metadata together, and export structured data for your own analysis.

**Java 21 · SQLite FTS5 · No database server**

## Quick start

Install a JDK and Maven. On Ubuntu / WSL:

```bash
sudo apt install openjdk-21-jdk-headless maven
```

From this repository:

```bash
mvn verify
java -jar target/parliament-corpus.jar import examples corpus.db
java -jar target/parliament-corpus.jar stats corpus.db
java -jar target/parliament-corpus.jar export corpus.db speeches.jsonl
```

The examples contain **24 fictional speeches in three fictional sessions**. They are demonstration data, not official records. Maven needs internet access on the first build; the packaged application runs offline.

## Import your own transcripts

```bash
java -Xmx512m -jar target/parliament-corpus.jar import /path/to/transcripts corpus.db
```

Pass one XML file or a directory. Directories are scanned recursively in sorted path order. The parser expects the Bundestag `dbtplenarprotokoll` format with a `sitzungsverlauf` section, term, session number and session date. The original XML files are never modified.

Each session is imported in one transaction. Importing the same session again replaces its previous speeches, comments and search entries, rather than creating duplicates. If a session fails, its previous database contents remain intact. Other valid files continue to import, and the command exits with a nonzero status if any file failed. When several input files describe the same session, the last successful one wins.

An external DTD declaration is allowed, but its contents are never loaded. Custom entity declarations are rejected. Invalid dates and duplicate speech IDs within a session are reported instead of silently accepted.

## What is stored?

- Session: term, number, ISO date, duration and source filename.
- Speech: original ID, session order, speaker ID and name, faction, agenda and paragraph text.
- Reactions and interjections, stored separately from the speech text.
- Word counts and a full-text index over speaker names and speech text.

Missing speech IDs use a deterministic session-and-position fallback. Missing speaker IDs remain distinct per speech, avoiding accidental merging of unrelated people. This means speaker counts are conservative when the source omits IDs. Missing session times produce a duration of zero. Sessions crossing midnight are supported.

The importer keeps one parsed XML file in memory at a time. It batches speech inserts inside a transaction and updates the search index incrementally. It does not download transcripts or contact a database service.

## Use the web explorer

[Parliament Explorer](https://github.com/FinnFFM/parliament-explorer) opens the same database directly:

```bash
java -jar /path/to/parliament-explorer.jar --db /path/to/corpus.db
```

Stop the explorer while updating a corpus, then restart it. The database is the complete handoff; neither application depends on the other's source code or Maven artifacts.

## Export and statistics

`stats` prints speech counts, total words and average words by faction. `export` writes one JSON object per speech, including its session metadata and a JSON array of comments. Export refuses to overwrite an existing file. Word counts use Unicode letters and numbers, keeping internal hyphens and apostrophes together; they are not linguistic tokenization.

See [the database contract](docs/schema.md) for fields and versioning.

## Development

```bash
mvn verify
```

Tests cover parsing without a DTD, mixed markup, interjections, overnight sessions, malformed inputs, entity declarations, stable fallback IDs, repeat imports, removal of stale search entries, transaction rollback and CLI error handling. GitHub Actions runs the same build on Java 21.

This is a focused corpus tool. It does not reproduce document layout, infer missing identities, perform sentiment analysis or validate political claims. Check the source transcript before quoting an extracted passage. Official transcripts and other third-party datasets are not bundled.

Maintained by Finn Karstens. See [credits](CREDITS.md).
