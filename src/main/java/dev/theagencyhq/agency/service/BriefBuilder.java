/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;
import module org.lattejava.version;

import dev.theagencyhq.agency.model.github.RepositoryContents;
import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.internal.*;
import dev.theagencyhq.agency.util.*;

/**
 * Turns a fetched Brief source repository into an unpublished {@link Brief} — one carrying no checksum and no
 * version, because neither is known until it is stored. Pure — it touches no database, no network and no
 * filesystem, and a build is all-or-nothing so a Brief is never inserted partially.
 */
public class BriefBuilder {
  public static final String SETTINGS_FILE = "the-agency-hq-settings.json";
  public static final Version SUPPORTED_LAYOUT_VERSION = new Version("1.0.0");
  private static final System.Logger logger = System.getLogger(BriefBuilder.class.getName());

  /**
   * Computes a Brief's content-addressed checksum. Null members are omitted from the JSON, so a Brief straight out of
   * {@link #build} contributes only its Organization's identity and its files: identical content always produces an
   * identical checksum no matter how many versions came before it. Passing a published Brief would fold its version
   * and checksum into the result and defeat that, which is why nothing does.
   *
   * @param brief The Brief to checksum.
   * @return The SHA-256, hex-encoded lowercase.
   */
  public static String checksum(Brief brief) {
    return Checksums.sha256Hex(BriefJSON.toJSONBytes(brief));
  }

  /**
   * Verifies that the bytes of a {@value #SETTINGS_FILE} declare a layout version this Agency supports. Public and
   * static so a repository can be checked at registration time — with one cheap request for one small file — rather
   * than by downloading the whole repository and running a full build.
   *
   * @param settings The file's bytes, or {@code null} if the repository has no such file.
   * @param source   How to name the repository in a failure message. Shown to whoever is registering it.
   * @throws BriefBuildException if the settings marker is missing, unparseable, or declares an unsupported major
   *                             layout version.
   */
  public static void verifySettings(byte[] settings, String source) {
    // This marker is the only thing distinguishing a Brief source repository from any other repository somebody
    // picked in the admin UI, so pointing the Agency at the wrong one must fail loudly here rather than quietly
    // publishing a Brief full of application source code.
    if (settings == null) {
      throw new BriefBuildException("The source [" + source + "] has no [" + SETTINGS_FILE + "] file, so it is not a "
          + "Brief source repository");
    }

    Version sourceVersion;
    try {
      // @JSONField(asString) builds the Version here, so a malformed SemVer string fails as a parse error with the
      // offending value named in the cause -- appending it keeps that detail on the message the admin form shows,
      // which is otherwise the only place an operator ever sees why their settings file was rejected.
      sourceVersion = SourceSettingsJSON.fromJSON(settings).version();
    } catch (RuntimeException e) {
      throw new BriefBuildException("Unable to parse [" + SETTINGS_FILE + "] in [" + source + "]: " + e.getMessage(), e);
    }

    // Absent rather than malformed: there is no string for the codec to reject, so this is the one shape that
    // reaches here instead of failing above.
    if (sourceVersion == null) {
      throw new BriefBuildException("The [" + SETTINGS_FILE + "] in [" + source + "] declares no [version]");
    }

    // isCompatibleWith rather than a bare major comparison: it is the same check while the supported layout is on a
    // 1.x line, and it stays correct on its own if the layout is ever versioned 0.x, where SemVer makes the minor
    // the compatibility boundary and comparing majors would call every 0.x layout compatible with every other.
    if (!SUPPORTED_LAYOUT_VERSION.isCompatibleWith(sourceVersion)) {
      throw new BriefBuildException("The [" + SETTINGS_FILE + "] version [" + sourceVersion + "] has an unsupported major "
          + "version. This Agency supports major version ["
          + SUPPORTED_LAYOUT_VERSION.major() + "]");
    }
  }

  private static List<String> mappedTopLevelDirectories() {
    var directories = new ArrayList<>(OutputPaths.SHARED_DIRECTORIES);
    OutputPaths.AGENT_TYPES.forEach(a -> directories.add(a.name()));
    return directories;
  }

  /**
   * @param organization The Organization the Brief belongs to.
   * @param contents     The source repository at one commit.
   * @return The Brief, carrying no checksum and no version.
   * @throws BriefBuildException if the repository is not a Brief source, contains a symbolic link under a mapped
   *                             directory, or produces two files at the same Brief path.
   */
  public Brief build(Organization organization, RepositoryContents contents) {
    verifySettings(contents.file(SETTINGS_FILE), organization.name());

    var resolver = new MissionTypeResolver(contents);
    var files = new ArrayList<BriefFile>();
    var seen = new HashSet<String>();
    var mapped = mappedTopLevelDirectories();

    // One pass over the whole repository in sorted order, filtered to the mapped top-level directories, rather
    // than a recursive walk per directory. There is no tree to descend any more -- the contents are a flat map --
    // and sorting the paths is what keeps the walk order identical on every run, which the duplicate-output-path
    // check below depends on to fail the same way every time.
    for (var path : contents.paths()) {
      var slash = path.indexOf('/');
      if (slash < 0 || !mapped.contains(path.substring(0, slash))) {
        continue;
      }

      addFile(contents, path, resolver, files, seen);
    }

    // No checksum and no version: this Brief is the input to both, and the insert assigns them. Brief sorts its own
    // files, so the checksum does not depend on the order they were collected in.
    //
    // Only the Organization's identity, never the rest of its row. The GitHub connection is a live bearer
    // credential and this document is served to every Handler in the fleet, so it must never ride along; the
    // instants must not either, because update_instant moves whenever the organizations row changes -- including
    // every eight-hour token refresh -- and folding it into the document would change this Brief's checksum
    // without a byte of content changing, publishing a new version that every Handler then re-downloads. The
    // nulls are omitted from the JSON, so the document carries id and name.
    return new Brief(null, new Organization(organization.id(), organization.name(), null, null, null), null, files,
        null, null);
  }

  private void addFile(RepositoryContents contents, String path, MissionTypeResolver resolver, List<BriefFile> files,
                       Set<String> seen) {
    // A link is the one construct that turns a valid relative path into something that resolves outside the tree
    // once the Handler writes it out, so it fails the build rather than being silently dropped. Checked before the
    // name filter below so a link named `x.mission-types` is still rejected rather than skipped.
    if (contents.symlink(path)) {
      throw new BriefBuildException("The source tree contains a symbolic link [" + path + "]. Links are not "
                                    + "supported because they can resolve outside the tree.");
    }

    // Covers both roles at once: `.mission-types` itself ends with `.mission-types`, so a separate exact-name test
    // would never be reached.
    if (path.endsWith(MissionTypeResolver.FILE_NAME)) {
      return;
    }

    var outputs = OutputPaths.map(path);
    if (outputs.isEmpty()) {
      logger.log(System.Logger.Level.DEBUG, "Ignoring unmapped source file [{0}]", path);
      return;
    }

    var bytes = contents.file(path);
    var encoded = encode(bytes);
    var checksum = Checksums.sha256Hex(bytes);
    var mode = contents.executable(path) ? BriefFile.EXECUTABLE_MODE : BriefFile.DEFAULT_MODE;
    var missionTypes = resolver.resolve(path);

    for (var output : outputs) {
      OutputPaths.validate(output);
      if (!seen.add(output)) {
        throw new BriefBuildException("Two source files produce the same Brief file path [" + output + "]");
      }

      files.add(new BriefFile(output, encoded.encoding(), mode, encoded.content(), checksum, missionTypes));
    }
  }

  private Encoded encode(byte[] bytes) {
    // Strict decoding is the point: a lenient decoder replaces invalid bytes with U+FFFD, which would silently
    // corrupt every binary asset rather than routing it through base64.
    var decoder = StandardCharsets.UTF_8.newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return new Encoded(BriefFile.DEFAULT_ENCODING, decoder.decode(ByteBuffer.wrap(bytes)).toString());
    } catch (CharacterCodingException e) {
      return new Encoded(BriefFile.ENCODING_BASE64, Base64.getEncoder().encodeToString(bytes));
    }
  }

  private record Encoded(String encoding, String content) {
  }
}
