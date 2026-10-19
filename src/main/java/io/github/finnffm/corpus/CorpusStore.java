package io.github.finnffm.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** SQLite schema v1, shared with parliament-explorer. One transaction per session. */
public final class CorpusStore implements AutoCloseable {
  private final Connection db;
  private static final ObjectMapper JSON = new ObjectMapper();

  public CorpusStore(Path file) throws SQLException {
    db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    try (var s = db.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=5000");
      int version;
      try (var r = s.executeQuery("PRAGMA user_version")) {
        r.next();
        version = r.getInt(1);
        if (version != 0 && version != 1)
          throw new SQLException("Unsupported corpus schema version");
      }
      if (version == 0) {
        try (var r =
            s.executeQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
          if (r.next() && r.getInt(1) > 0)
            throw new SQLException("Refusing to modify an unrelated database");
        }
      }
      s.execute(
          "CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, term INTEGER NOT NULL, number INTEGER NOT NULL, date TEXT NOT NULL, duration_minutes INTEGER NOT NULL, source TEXT NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS speeches(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, position INTEGER NOT NULL, speaker_id TEXT NOT NULL, speaker TEXT NOT NULL, faction TEXT NOT NULL, agenda TEXT NOT NULL, text TEXT NOT NULL, comments TEXT NOT NULL, word_count INTEGER NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS speech_session ON speeches(session_id)");
      s.execute("CREATE INDEX IF NOT EXISTS speech_faction ON speeches(faction)");
      s.execute("CREATE INDEX IF NOT EXISTS session_date ON sessions(date)");
      s.execute(
          "CREATE VIRTUAL TABLE IF NOT EXISTS speech_search USING fts5(id UNINDEXED, speaker, text, tokenize='unicode61 remove_diacritics 2')");
      s.execute(
          "CREATE TRIGGER IF NOT EXISTS speech_insert AFTER INSERT ON speeches BEGIN INSERT INTO speech_search(rowid,id,speaker,text) VALUES(new.rowid,new.id,new.speaker,new.text); END");
      s.execute(
          "CREATE TRIGGER IF NOT EXISTS speech_delete AFTER DELETE ON speeches BEGIN DELETE FROM speech_search WHERE rowid=old.rowid; END");
      s.execute("PRAGMA user_version=1");
    } catch (SQLException error) {
      db.close();
      throw error;
    }
  }

  public void replace(Transcript t) throws Exception {
    db.setAutoCommit(false);
    try {
      try (var p = db.prepareStatement("DELETE FROM sessions WHERE id=?")) {
        p.setString(1, t.id());
        p.executeUpdate();
      }
      try (var p = db.prepareStatement("INSERT INTO sessions VALUES(?,?,?,?,?,?)")) {
        p.setString(1, t.id());
        p.setInt(2, t.term());
        p.setInt(3, t.number());
        p.setString(4, t.date());
        p.setInt(5, t.durationMinutes());
        p.setString(6, t.source());
        p.executeUpdate();
      }
      try (var p = db.prepareStatement("INSERT INTO speeches VALUES(?,?,?,?,?,?,?,?,?,?)")) {
        int position = 0;
        for (var speech : t.speeches()) {
          p.setString(1, speech.id());
          p.setString(2, t.id());
          p.setInt(3, ++position);
          p.setString(4, speech.speakerId());
          p.setString(5, speech.speaker());
          p.setString(6, speech.faction());
          p.setString(7, speech.agenda());
          p.setString(8, speech.text());
          p.setString(9, JSON.writeValueAsString(speech.comments()));
          p.setInt(10, words(speech.text()));
          p.addBatch();
        }
        p.executeBatch();
      }
      db.commit();
    } catch (Exception ex) {
      db.rollback();
      throw ex;
    } finally {
      db.setAutoCommit(true);
    }
  }

  static int words(String text) {
    var matcher =
        java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+(?:[-’'][\\p{L}\\p{N}]+)*").matcher(text);
    int n = 0;
    while (matcher.find()) n++;
    return n;
  }

  public List<Map<String, Object>> stats() throws SQLException {
    List<Map<String, Object>> result = new ArrayList<>();
    try (var s = db.createStatement();
        var r =
            s.executeQuery(
                "SELECT faction, COUNT(*) AS speeches, SUM(word_count) AS words, ROUND(AVG(word_count),1) AS average_words FROM speeches GROUP BY faction ORDER BY speeches DESC, faction")) {
      while (r.next()) result.add(row(r));
    }
    return result;
  }

  public void export(java.io.Writer out) throws Exception {
    try (var s = db.createStatement();
        var rows =
            s.executeQuery(
                "SELECT s.*, t.date, t.term, t.number FROM speeches s JOIN sessions t ON s.session_id=t.id ORDER BY t.date,t.id,s.position")) {
      while (rows.next()) {
        var data = row(rows);
        data.put("comments", JSON.readTree((String) data.get("comments")));
        out.write(JSON.writeValueAsString(data));
        out.write('\n');
      }
    }
  }

  private static Map<String, Object> row(ResultSet r) throws SQLException {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
      result.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
    return result;
  }

  @Override
  public void close() throws SQLException {
    db.close();
  }
}
