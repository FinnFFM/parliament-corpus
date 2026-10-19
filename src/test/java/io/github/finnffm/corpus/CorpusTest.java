package io.github.finnffm.corpus;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusTest {
  @Test
  void doesNotModifyAnUnrelatedDatabase() throws Exception {
    Path file = temp.resolve("unrelated.db");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
        var s = db.createStatement()) {
      s.execute("CREATE TABLE unrelated(value TEXT)");
    }
    assertThrows(SQLException.class, () -> new CorpusStore(file));
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
        var s = db.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
      assertTrue(r.next());
      assertEquals(1, r.getInt(1));
    }
  }

  @TempDir Path temp;

  private Path xml(String contents) throws Exception {
    return Files.writeString(temp.resolve("input.xml"), contents);
  }

  private static String session(String speech) {
    return "<!DOCTYPE dbtplenarprotokoll SYSTEM 'missing.dtd'><dbtplenarprotokoll wahlperiode='20' sitzung-nr='1' sitzung-datum='1.2.2025' sitzung-start-uhrzeit='23:30' sitzung-ende-uhrzeit='00:15'><sitzungsverlauf><tagesordnungspunkt top-id='TOP 1'>"
        + speech
        + "</tagesordnungspunkt></sitzungsverlauf></dbtplenarprotokoll>";
  }

  private static String speech(String id, String text) {
    return "<rede id='"
        + id
        + "'><p klasse='redner'><redner id='person-1'><name><vorname>Anna</vorname><nachname>Müller</nachname><fraktion>Beispiel</fraktion></name></redner></p><p>"
        + text
        + "</p></rede>";
  }

  @Test
  void parsesWithoutDtdAndSeparatesComments() throws Exception {
    var t =
        new TranscriptParser()
            .parse(
                xml(
                    session(
                        speech(
                            "s1",
                            "Gute <b>Bildung</b> für alle.<kommentar>(Beifall)</kommentar> Danke!"))));
    assertEquals("2025-02-01", t.date());
    assertEquals(45, t.durationMinutes());
    assertEquals("WP20-1", t.id());
    var s = t.speeches().getFirst();
    assertEquals("Anna Müller", s.speaker());
    assertEquals("person-1", s.speakerId());
    assertEquals("Gute Bildung für alle. Danke!", s.text());
    assertEquals(List.of("(Beifall)"), s.comments());
  }

  @Test
  void rejectsEntitiesAndMalformedInput() throws Exception {
    String entity =
        "<!DOCTYPE dbtplenarprotokoll [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><dbtplenarprotokoll>&secret;</dbtplenarprotokoll>";
    assertThrows(Exception.class, () -> new TranscriptParser().parse(xml(entity)));
    assertThrows(Exception.class, () -> new TranscriptParser().parse(xml("<broken>")));
    assertThrows(
        Exception.class,
        () ->
            new TranscriptParser()
                .parse(xml(session(speech("s1", "ok")).replace("1.2.2025", "31.2.2025"))));
  }

  @Test
  void missingSpeakersAndIdsHaveStableFallbacks() throws Exception {
    var p = xml(session("<rede><p>Hello.</p></rede>"));
    var parser = new TranscriptParser();
    assertEquals(parser.parse(p), parser.parse(p));
    assertEquals("Unknown speaker", parser.parse(p).speeches().getFirst().speaker());
  }

  @Test
  void duplicateSpeechIdsAreRejected() throws Exception {
    assertThrows(
        Exception.class,
        () ->
            new TranscriptParser()
                .parse(xml(session(speech("same", "One") + speech("same", "Two")))));
  }

  @Test
  void replacingSessionRemovesOldSearchEntriesAndNeverDuplicates() throws Exception {
    Path file = temp.resolve("corpus.db");
    var parser = new TranscriptParser();
    var original =
        parser.parse(xml(session(speech("s1", "Mobilität für alle") + speech("s2", "Alte Rede"))));
    try (var store = new CorpusStore(file)) {
      store.replace(original);
      store.replace(original);
      assertEquals(2, ((Number) store.stats().getFirst().get("speeches")).intValue());
      store.replace(parser.parse(xml(session(speech("s1", "Neue Bildung")))));
      var out = new java.io.StringWriter();
      store.export(out);
      assertEquals(1, out.toString().lines().count());
      assertTrue(out.toString().contains("Neue Bildung"));
      assertTrue(out.toString().contains("\"comments\":[]"));
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
        var s = db.createStatement();
        var r =
            s.executeQuery(
                "SELECT count(*) FROM speech_search WHERE speech_search MATCH 'Mobilität'")) {
      assertTrue(r.next());
      assertEquals(0, r.getInt(1));
    }
  }

  @Test
  void conflictingSessionRollsBackEntireReplacement() throws Exception {
    Path file = temp.resolve("corpus.db");
    var parser = new TranscriptParser();
    var first = parser.parse(xml(session(speech("s1", "Original"))));
    var second =
        parser.parse(
            xml(session(speech("s2", "Second")).replace("sitzung-nr='1'", "sitzung-nr='2'")));
    try (var store = new CorpusStore(file)) {
      store.replace(first);
      store.replace(second);
      var bad =
          new Transcript(
              first.id(),
              20,
              1,
              first.date(),
              45,
              "bad.xml",
              List.of(second.speeches().getFirst()));
      assertThrows(Exception.class, () -> store.replace(bad));
      var out = new java.io.StringWriter();
      store.export(out);
      assertTrue(out.toString().contains("Original"));
      assertEquals(2, out.toString().lines().count());
    }
  }

  @Test
  void cliRejectsMissingInputAndExistingExport() throws Exception {
    assertThrows(
        Exception.class,
        () -> Main.run(new String[] {"stats", temp.resolve("absent.db").toString()}));
    var input = xml(session(speech("s1", "Hello")));
    String db = temp.resolve("data.db").toString();
    Main.run(new String[] {"import", input.toString(), db});
    Path output = Files.writeString(temp.resolve("existing.jsonl"), "keep");
    assertThrows(Exception.class, () -> Main.run(new String[] {"export", db, output.toString()}));
    assertEquals("keep", Files.readString(output));
  }
}
