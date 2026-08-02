/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;
import module org.lattejava.version;

import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.internal.*;
import dev.theagencyhq.agency.util.*;

/**
 * Turns a Brief source working tree into an unpublished {@link Brief} — one carrying no checksum and no version,
 * because neither is known until it is stored. Pure — it touches no database and no network, and a build is
 * all-or-nothing so a Brief is never inserted partially.
 */
public class BriefBuilder {
  public static final String SETTINGS_FILE = "the-agency-hq-settings.json";
  public static final Version SUPPORTED_LAYOUT_VERSION = new Version("1.0.0");
  private static final System.Logger logger = System.getLogger(BriefBuilder.class.getName());

  /**
   * Computes a Brief's content-addressed checksum. Null members are omitted from the JSON, so a Brief straight out of
   * {@link #build} contributes only its organization and its files: identical content always produces an identical
   * checksum no matter how many versions came before it. Passing a published Brief would fold its version and checksum
   * into the result and defeat that, which is why nothing does.
   *
   * @param brief The Brief to checksum.
   * @return The SHA-256, hex-encoded lowercase.
   */
  public static String checksum(Brief brief) {
    return Checksums.sha256Hex(BriefJSON.toJSONBytes(brief));
  }

  /**
   * Verifies that a directory is a Brief source repository whose layout this Agency supports. Public and static so
   * {@code OrganizationValidator} can run the exact check the build itself runs, rather than a weaker approximation of
   * it that would let a repository register cleanly and then fail on every poll cycle forever.
   *
   * @param sourceRoot The source tree's absolute, normalized sourceRoot.
   * @throws BriefBuildException if the settings marker is missing, unparseable, or declares an unsupported major layout
   *                             version.
   */
  public static void verifySettings(Path sourceRoot) {
    // This marker is the only thing distinguishing a Brief source repository from an arbitrary directory somebody
    // typed into the admin form, so pointing the Agency at the wrong repository must fail loudly here rather than
    // quietly publishing a Brief full of application source code.
    var settingsFile = sourceRoot.resolve(SETTINGS_FILE);
    if (!Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new BriefBuildException("The source tree [" + sourceRoot + "] has no [" + SETTINGS_FILE + "] file, so it is "
          + "not a Brief source repository");
    }

    Version sourceVersion;
    try {
      // @JSONField(asString) builds the Version here, so a malformed SemVer string fails as a parse error with the
      // offending value named in the cause -- appending it keeps that detail on the message the admin form shows,
      // which is otherwise the only place an operator ever sees why their settings file was rejected.
      sourceVersion = SourceSettingsJSON.fromJSON(Files.readAllBytes(settingsFile)).version();
    } catch (IOException | RuntimeException e) {
      throw new BriefBuildException("Unable to parse [" + SETTINGS_FILE + "] in [" + sourceRoot + "]: " + e.getMessage(), e);
    }

    // Absent rather than malformed: there is no string for the codec to reject, so this is the one shape that
    // reaches here instead of failing above.
    if (sourceVersion == null) {
      throw new BriefBuildException("The [" + SETTINGS_FILE + "] in [" + sourceRoot + "] declares no [version]");
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

  // NOFOLLOW throughout: the planner validates path strings and cannot know that `docs -> /etc` exists, so a link
  // is the one construct that turns a valid relative path into a write outside the tree. Shared by the top-level
  // loop in build() and by collect()'s recursion so a symlink is rejected the same way at every depth, including a
  // mapped top-level directory (e.g. `rules`) that is itself a link: Files.isDirectory(..., NOFOLLOW_LINKS) simply
  // returns false for that case, which would otherwise be indistinguishable from "the directory does not exist"
  // and silently drop every file underneath it instead of failing the build.
  private static void rejectSymlink(Path root, Path path) {
    if (Files.isSymbolicLink(path)) {
      throw new BriefBuildException("The source tree contains a symbolic link [" + root.relativize(path) + "]. "
          + "Links are not supported because they can resolve outside the tree.");
    }
  }

  public Brief build(Organization organization, Path sourceRoot) {
    var root = sourceRoot.toAbsolutePath().normalize();
    verifySettings(root);

    var resolver = new MissionTypeResolver(root);
    var files = new ArrayList<BriefFile>();
    var seen = new HashSet<String>();

    for (var top : mappedTopLevelDirectories()) {
      var directory = root.resolve(top);
      rejectSymlink(root, directory);

      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }

      collect(root, directory, resolver, files, seen);
    }

    // No checksum and no version: this Brief is the input to both, and the insert assigns them. The files are
    // handed over in whatever order the walk produced them; Brief sorts them, so the checksum does not depend on
    // filesystem iteration order.
    return new Brief(null, organization, null, files, null, null);
  }

  private void addFile(Path root, Path entry, MissionTypeResolver resolver, List<BriefFile> files, Set<String> seen)
      throws IOException {
    var name = entry.getFileName().toString();

    // Covers both roles at once: `.mission-types` itself ends with `.mission-types`, so a separate exact-name test
    // would never be reached.
    if (name.endsWith(MissionTypeResolver.FILE_NAME)) {
      return;
    }

    var relative = root.relativize(entry).toString();
    var outputs = OutputPaths.map(relative);
    if (outputs.isEmpty()) {
      logger.log(System.Logger.Level.DEBUG, "Ignoring unmapped source file [" + relative + "]");
      return;
    }

    var bytes = Files.readAllBytes(entry);
    var encoded = encode(bytes);
    var checksum = Checksums.sha256Hex(bytes);
    var mode = Files.getPosixFilePermissions(entry, LinkOption.NOFOLLOW_LINKS)
                    .contains(PosixFilePermission.OWNER_EXECUTE) ? BriefFile.EXECUTABLE_MODE : BriefFile.DEFAULT_MODE;
    var missionTypes = resolver.resolve(entry);

    for (var output : outputs) {
      OutputPaths.validate(output);
      if (!seen.add(output)) {
        throw new BriefBuildException("Two source files produce the same Brief file path [" + output + "]");
      }

      files.add(new BriefFile(output, encoded.encoding(), mode, encoded.content(), checksum, missionTypes));
    }
  }

  private void collect(Path root, Path directory, MissionTypeResolver resolver, List<BriefFile> files, Set<String> seen) {
    try (var stream = Files.newDirectoryStream(directory)) {
      var entries = new ArrayList<Path>();
      stream.forEach(entries::add);
      entries.sort(Comparator.comparing(p -> p.getFileName().toString()));

      for (var entry : entries) {
        // NOFOLLOW throughout: the planner validates path strings and cannot know that `docs -> /etc` exists, so a
        // link is the one construct that turns a valid relative path into a write outside the tree.
        rejectSymlink(root, entry);

        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
          collect(root, entry, resolver, files, seen);
          continue;
        }

        addFile(root, entry, resolver, files, seen);
      }
    } catch (IOException e) {
      throw new BriefBuildException("Unable to read the source directory [" + directory + "]", e);
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
