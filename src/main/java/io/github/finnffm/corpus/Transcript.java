package io.github.finnffm.corpus;

import java.util.List;

/** A normalized session with speeches in their original order. */
public record Transcript(
    String id,
    int term,
    int number,
    String date,
    int durationMinutes,
    String source,
    List<Speech> speeches) {
  public record Speech(
      String id,
      String speakerId,
      String speaker,
      String faction,
      String agenda,
      String text,
      List<String> comments) {}
}
