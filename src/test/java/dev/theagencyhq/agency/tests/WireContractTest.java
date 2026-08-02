/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.model.Brief;
import dev.theagencyhq.agency.model.BriefFile;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.api.BriefingRequest;
import dev.theagencyhq.agency.model.api.CurrentVersion;
import dev.theagencyhq.agency.model.api.internal.BriefingRequestJSON;
import dev.theagencyhq.agency.model.internal.BriefJSON;
import dev.theagencyhq.agency.util.Checksums;
import java.nio.file.Files;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test
public class WireContractTest {
  private static final Organization ORGANIZATION = new Organization(
      UUID.fromString("00000000-0000-4000-8000-000000000042"), "fusionauth",
      Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_000L));

  @Test
  public void briefFileDefaults() {
    var file = new BriefFile(" a.md ", null, null, null, null, null);
    assertEquals(file.path(), "a.md");
    assertEquals(file.encoding(), "text");
    assertEquals(file.mode(), "r--------");
    assertEquals(file.content(), "");
    assertEquals(file.missionTypes(), List.of());
  }

  @Test
  public void briefSerializesInContractKeyOrder() {
    var brief = new Brief("opaque-42-73", ORGANIZATION, 73,
        List.of(new BriefFile(".claude/rules/foo.md", "text", "r--------", "For Claude",
            "7b0464d7d419e8e21902270f71ec5e809ba2d1af68aea0df38f4e4913366a1b8", List.of("Web", "Library"))),
        null, null);

    var json = BriefJSON.toJSON(brief);

    // Pins presence AND order of every top-level key in one assertion. A chain of indexOf(...) < indexOf(...)
    // comparisons cannot detect a missing leading key: indexOf returns -1 when absent, and -1 is less than every
    // real index, so the comparison silently passes. Extracting the actual top-level key sequence closes that hole.
    assertEquals(topLevelKeys(json), List.of("checksum", "organization", "version", "files"), json);
    // Canonical, not as authored: the record lowercases and sorts, so ["Web", "Library"] goes out as
    // ["library", "web"]. The Handler lowercases on parse, so this is the form it would have arrived at anyway.
    assertTrue(json.contains("\"missionTypes\":[\"library\",\"web\"]"), json);

    // The two members the Handler actually reads off the nested Organization. The id is a UUID here and a String
    // there, which agrees because a UUID is written as a JSON string, and it is a single path segment -- which the
    // Handler requires, since it resolves the id against its store root.
    assertTrue(json.contains("\"id\":\"00000000-0000-4000-8000-000000000042\""), json);
    assertTrue(json.contains("\"name\":\"fusionauth\""), json);

    // The timestamps ride along as keys the Handler has no member for. It parses non-strictly (@JSON strict()
    // defaults to false), so they are ignored rather than rejected -- this is what lets one Organization type
    // serve both the database and the wire.
    assertTrue(json.contains("\"insertInstant\""), json);
    assertTrue(json.contains("\"updateInstant\""), json);
  }

  /**
   * The checksum input is a {@code Brief} carrying neither a checksum nor a version, and {@code @JSON} omits null
   * members, so it serializes to exactly the content. This is the whole reason both members are nullable rather
   * than defaulted, and it is what keeps a checksum purely content-addressed: a blank-but-present checksum would
   * put the member inside its own input, and a {@code 0} version would make every rebuild of identical content
   * hash differently once the version moved on.
   */
  @Test
  public void checksumInputOmitsTheChecksumAndTheVersion() {
    var unpublished = new Brief(null, ORGANIZATION, null,
        List.of(new BriefFile(".claude/rules/foo.md", "text", "r--------", "For Claude",
            "7b0464d7d419e8e21902270f71ec5e809ba2d1af68aea0df38f4e4913366a1b8", List.of("Web"))), null, null);

    assertEquals(topLevelKeys(BriefJSON.toJSON(unpublished)), List.of("organization", "files"),
        BriefJSON.toJSON(unpublished));

    // A blank checksum is normalized to null by the compact constructor, so it cannot sneak onto the wire as ""
    var blank = new Brief("  ", ORGANIZATION, null, List.of(), null, null);
    assertEquals(topLevelKeys(BriefJSON.toJSON(blank)), List.of("organization", "files"), BriefJSON.toJSON(blank));
  }

  /**
   * A Brief sorts its own files, so two Briefs built from the same content in different orders are equal and hash
   * identically. The builder used to own this and the record did not, which left every other way of constructing a
   * Brief — parsing one off the wire, copying one to attach a version — able to produce an unsorted one whose
   * checksum disagreed with the builder's for the same content.
   */
  @Test
  public void briefCanonicalizesItsFilesByPath() {
    var a = file(".claude/rules/a.md");
    var b = file(".claude/rules/b.md");
    var c = file(".codex/rules/c.md");

    var scrambled = new Brief(null, ORGANIZATION, null, List.of(c, a, b), null, null);
    assertEquals(scrambled.files().stream().map(BriefFile::path).toList(),
        List.of(".claude/rules/a.md", ".claude/rules/b.md", ".codex/rules/c.md"));

    // The property that actually matters: order in, identical checksum out.
    assertEquals(BriefJSON.toJSON(scrambled), BriefJSON.toJSON(new Brief(null, ORGANIZATION, null, List.of(a, b, c), null, null)));
  }

  /**
   * Everything reachable from a Brief feeds its checksum, so everything reachable from a Brief has to be
   * canonical. These are the two that are not simply "sort the list": Mission Types are compared case-insensitively
   * by the Handler, and the instant columns are {@code BIGINT} epoch millis, so sub-millisecond digits on an
   * in-memory {@code Instant.now()} would not survive a round trip through the database.
   */
  @Test
  public void everythingUnderABriefIsCanonical() {
    var authored = new BriefFile("a.md", "text", "r--------", "x", "abc",
        List.of(" Web ", "library", "WEB", "", "Library"));
    assertEquals(authored.missionTypes(), List.of("library", "web"));

    var fresh = new Organization(UUID.fromString("00000000-0000-4000-8000-000000000042"), "FusionAuth",
        Instant.ofEpochSecond(1_700_000_000L, 123_456_789), Instant.ofEpochSecond(1_700_000_000L, 987_654_321));
    var roundTripped = new Organization(fresh.id(), fresh.name(),
        Instant.ofEpochMilli(fresh.insertInstant().toEpochMilli()),
        Instant.ofEpochMilli(fresh.updateInstant().toEpochMilli()));

    // The property: a Brief built against a freshly constructed Organization and one built against the same
    // Organization as the database hands it back serialize identically, so they cannot hash differently.
    assertEquals(fresh, roundTripped);
    assertEquals(BriefJSON.toJSON(new Brief(null, fresh, null, List.of(authored), null, null)),
        BriefJSON.toJSON(new Brief(null, roundTripped, null, List.of(authored), null, null)));
  }

  @Test
  public void checksumIsLowercaseHex() {
    // Well-known SHA-256 of the empty input
    assertEquals(Checksums.sha256Hex(new byte[0]),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  public void parsesAHandlerRequest() {
    var request = BriefingRequestJSON.fromJSON(
        "{\"currentVersions\":[{\"organizationId\":\"42\",\"version\":73,\"checksum\":\"abc\"}]}"
            .getBytes(StandardCharsets.UTF_8));

    assertEquals(request.currentVersions(), List.of(new CurrentVersion("42", 73, "abc")));
  }

  @Test
  public void parsesTheHandlersFrozenFixture() throws Exception {
    var bytes = Files.readAllBytes(Path.of("src/test/resources/agency/briefing-updated.json"));
    var text = new String(bytes, StandardCharsets.UTF_8);

    // Every key the Agency must emit appears in the frozen fixture
    for (String key : List.of("organizationIds", "briefs", "checksum", "organization", "version", "files",
        "path", "encoding", "mode", "content", "missionTypes")) {
      assertTrue(text.contains("\"" + key + "\""), "Fixture is missing key [" + key + "]");
    }
  }

  private BriefFile file(String path) {
    return new BriefFile(path, "text", "r--------", "content of " + path,
        "7b0464d7d419e8e21902270f71ec5e809ba2d1af68aea0df38f4e4913366a1b8", List.of());
  }

  /**
   * @return The keys of the outermost JSON object, in wire order, ignoring keys nested inside object or array
   *     values (e.g. {@code BriefFile}'s own {@code checksum}, one level down inside {@code files}). Distinguishing
   *     nesting depth is what lets {@link #briefSerializesInContractKeyOrder()} tell a missing or reordered
   *     top-level key apart from an identically-named key that happens to live elsewhere in the document.
   */
  private List<String> topLevelKeys(String json) {
    var keys = new ArrayList<String>();
    var current = new StringBuilder();
    var depth = 0;
    var inString = false;
    var escaped = false;
    for (int i = 0; i < json.length(); i++) {
      var c = json.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
          current.append(c);
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
          if (depth == 1 && i + 1 < json.length() && json.charAt(i + 1) == ':') {
            keys.add(current.toString());
          }
          current.setLength(0);
        } else {
          current.append(c);
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
      }
    }
    return keys;
  }
}
