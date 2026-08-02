/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * Resolves the Mission Types that apply to a source file, per the design's §8.3. First match wins: a sibling
 * {@code <file>.mission-types}, then the nearest ancestor directory's {@code .mission-types}, then the empty list
 * (which means "applies to every Mission Type").
 *
 * <p>Original case is preserved. Both the Agency and the Handler lowercase before comparing, so matching is
 * case-insensitive by construction without this class having to normalize.
 */
public class MissionTypeResolver {
  /**
   * The one name this class looks for, in both of the roles it plays: a directory's own {@code .mission-types}
   * file, and the {@code .mission-types} suffix appended to a sibling file's full name. Deliberately a single
   * constant rather than two holding the same string — two names invite a caller to test for both, where the
   * suffix test always subsumes the exact-name test and the redundancy is invisible at the call site.
   */
  public static final String FILE_NAME = ".mission-types";
  private final Map<Path, List<String>> directoryCache = new HashMap<>();
  private final Path sourceRoot;

  public MissionTypeResolver(Path sourceRoot) {
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
  }

  public List<String> resolve(Path file) throws IOException {
    var absolute = file.toAbsolutePath().normalize();

    var sibling = absolute.resolveSibling(absolute.getFileName().toString() + FILE_NAME);
    if (isMissionTypesFile(sibling)) {
      return read(sibling);
    }

    for (var directory = absolute.getParent();
         directory != null && directory.startsWith(sourceRoot);
         directory = directory.getParent()) {
      var types = cachedDirectoryTypes(directory);
      if (types != null) {
        return types;
      }
    }

    return List.of();
  }

  private List<String> cachedDirectoryTypes(Path directory) throws IOException {
    if (directoryCache.containsKey(directory)) {
      return directoryCache.get(directory);
    }

    var file = directory.resolve(FILE_NAME);
    var types = isMissionTypesFile(file) ? read(file) : null;
    directoryCache.put(directory, types);
    return types;
  }

  // NOFOLLOW, and a symbolic link is a hard failure rather than a silent skip. BriefBuilder rejects a link at every
  // depth of the source tree, but both of the files this class reads are reached before that walk can ever see
  // them: <sourceRoot>/.mission-types lies outside the five mapped top-level directories collect() walks at all, and
  // a sibling <file>.mission-types is read by resolve() while addFile() is handling <file> -- strictly before the
  // sorted directory listing reaches it, because `a.md` sorts ahead of `a.md.mission-types`. Following either would
  // read an arbitrary local file and publish its lines as Mission Types to every machine in the fleet. Throwing
  // rather than ignoring matches how BriefBuilder treats every other link, so this fails the build loudly instead
  // of quietly changing which files each Handler installs.
  private boolean isMissionTypesFile(Path file) {
    if (Files.isSymbolicLink(file)) {
      var reported = file.startsWith(sourceRoot) ? sourceRoot.relativize(file) : file;
      throw new BriefBuildException("The source tree contains a symbolic link [" + reported + "]. Links are not "
                                    + "supported because they can resolve outside the tree.");
    }

    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
  }

  private List<String> read(Path file) throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8)
                .stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .distinct()
                .toList();
  }
}
