package io.github.finnffm.corpus;

import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Parses one Bundestag XML session. External DTDs are never fetched. */
public final class TranscriptParser {
  public Transcript parse(Path path) throws Exception {
    var f = DocumentBuilderFactory.newInstance();
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    var builder = f.newDocumentBuilder();
    builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
    builder.setErrorHandler(
        new DefaultHandler() {
          @Override
          public void error(org.xml.sax.SAXParseException e) throws SAXException {
            throw e;
          }

          @Override
          public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
            throw e;
          }
        });
    Document doc;
    try (var input = java.nio.file.Files.newInputStream(path)) {
      doc = builder.parse(input);
    }
    if (doc.getDoctype() != null && doc.getDoctype().getEntities().getLength() > 0)
      throw new IllegalArgumentException("Custom XML entities are not supported");
    Element root = doc.getDocumentElement();
    if (!root.getTagName().equals("dbtplenarprotokoll"))
      throw new IllegalArgumentException("Expected dbtplenarprotokoll root");
    int term = positive(root.getAttribute("wahlperiode"));
    int number = positive(root.getAttribute("sitzung-nr"));
    String id = "WP" + term + "-" + number;
    String date =
        LocalDate.parse(
                root.getAttribute("sitzung-datum"),
                DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT))
            .toString();
    Element proceedings = first(root, "sitzungsverlauf");
    if (proceedings == null) throw new IllegalArgumentException("Missing sitzungsverlauf");
    List<Transcript.Speech> speeches = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    NodeList nodes = proceedings.getElementsByTagName("rede");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element el = (Element) nodes.item(i);
      String speechId = el.getAttribute("id").strip();
      if (speechId.isBlank()) speechId = id + "-speech-" + (i + 1);
      if (!ids.add(speechId))
        throw new IllegalArgumentException("Duplicate speech ID: " + speechId);
      Element speaker = first(el, "redner");
      Element name = speaker == null ? null : first(speaker, "name");
      String fullName =
          name == null
              ? "Unknown speaker"
              : clean(
                  String.join(
                      " ",
                      childText(name, "titel"),
                      childText(name, "vorname"),
                      childText(name, "nachname")));
      if (fullName.isBlank()) fullName = "Unknown speaker";
      String faction = name == null ? "" : childText(name, "fraktion");
      String speakerId = speaker == null ? "" : speaker.getAttribute("id");
      if (speakerId.isBlank()) speakerId = "unknown:" + speechId;
      String agenda = "General proceedings";
      for (Node parent = el.getParentNode();
          parent instanceof Element p;
          parent = parent.getParentNode()) {
        if (p.getTagName().equals("tagesordnungspunkt")) {
          String title = childText(p, "top-titel");
          agenda = clean(p.getAttribute("top-id") + (title.isBlank() ? "" : ": " + title));
          break;
        }
      }
      List<String> paragraphs = new ArrayList<>();
      NodeList ps = el.getElementsByTagName("p");
      for (int j = 0; j < ps.getLength(); j++) {
        Element p = (Element) ps.item(j);
        if (p.getAttribute("klasse").equalsIgnoreCase("redner") || inside(p, "kommentar", el))
          continue;
        String text = clean(prose(p));
        if (!text.isBlank()) paragraphs.add(text);
      }
      List<String> comments = new ArrayList<>();
      NodeList cs = el.getElementsByTagName("kommentar");
      for (int j = 0; j < cs.getLength(); j++) {
        String comment = clean(cs.item(j).getTextContent());
        if (!comment.isBlank()) comments.add(comment);
      }
      speeches.add(
          new Transcript.Speech(
              speechId,
              speakerId,
              fullName,
              faction.isBlank() ? "Not specified" : faction,
              agenda,
              String.join("\n\n", paragraphs),
              List.copyOf(comments)));
    }
    return new Transcript(
        id,
        term,
        number,
        date,
        duration(root),
        path.getFileName().toString(),
        List.copyOf(speeches));
  }

  private static boolean inside(Node node, String tag, Node stop) {
    for (Node p = node.getParentNode(); p != null && p != stop; p = p.getParentNode())
      if (p instanceof Element el && el.getTagName().equals(tag)) return true;
    return false;
  }

  private static String prose(Node node) {
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
      return node.getNodeValue();
    if (node instanceof Element e
        && Set.of("redner", "kommentar", "seite").contains(e.getTagName())) return " ";
    StringBuilder text = new StringBuilder();
    for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) text.append(prose(n));
    return text.toString();
  }

  static String clean(String s) {
    return s.replaceAll("[\\s\\p{Z}]+", " ").strip();
  }

  private static Element first(Element parent, String tag) {
    var nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static String childText(Element parent, String tag) {
    Element child = first(parent, tag);
    return child == null ? "" : clean(child.getTextContent());
  }

  private static int positive(String s) {
    int n = Integer.parseInt(s);
    if (n < 1) throw new IllegalArgumentException("Session and term must be positive");
    return n;
  }

  private static int duration(Element root) {
    String start = root.getAttribute("sitzung-start-uhrzeit");
    String end = root.getAttribute("sitzung-ende-uhrzeit");
    if (start.isBlank() || end.isBlank()) return 0;
    var fmt = DateTimeFormatter.ofPattern("H:mm");
    int minutes =
        (int) Duration.between(LocalTime.parse(start, fmt), LocalTime.parse(end, fmt)).toMinutes();
    return minutes < 0 ? minutes + 1440 : minutes;
  }
}
