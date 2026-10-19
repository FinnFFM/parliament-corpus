# Corpus database contract — version 1

The interchange format is a SQLite database with `PRAGMA user_version = 1`. The explorer rejects other versions. Dates are ISO `YYYY-MM-DD`; text is Unicode.

## Tables

`sessions`: `id` (text primary key, e.g. `WP20-1`), `term` (integer), `number` (integer), `date` (text), `duration_minutes` (integer), `source` (source basename, not an absolute machine path).

`speeches`: `id` (text primary key), `session_id` (foreign key), `position` (one-based session order), `speaker_id`, `speaker`, `faction`, `agenda`, `text`, `comments` (JSON array encoded as text), `word_count` (integer).

`speech_search`: FTS5 virtual table with `id` unindexed, plus indexed `speaker` and `text`. The tokenizer is `unicode61 remove_diacritics 2`. Insert and delete triggers maintain the index; the importer replaces complete sessions instead of updating individual speech rows. Direct manual updates are not supported.

All fields are required in importer-created databases. Missing faction labels are `Not specified`; missing speakers are `Unknown speaker`. Unknown speaker IDs are speech-specific to avoid merging different unidentified people.

## Consistency

Replacing a session deletes its old speeches through the foreign key and inserts the new set in the same transaction. Any conflict or SQL error rolls back the replacement. Imports may partially succeed across multiple files; the CLI reports failures and returns a nonzero exit status.

Do not run imports while browsing: list counts, results and statistics are separate reads, so a concurrent import could make a page internally inconsistent. No schema migration is currently implemented.

## JSONL export

Every line contains one speech row plus `date`, `term` and `number`. The `comments` field is a native JSON array. Rows are ordered by date, session ID and speech position. JSONL is an analysis export; the explorer reads the SQLite database directly.
