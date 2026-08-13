/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.model.github.RepositoryContents;

/**
 * Resolves the Mission Types that apply to a source file, per the design's §8.3. First match wins: a sibling
 * {@code <file>.mission-types}, then the nearest ancestor directory's {@code .mission-types}, then the empty list
 * (which means "applies to every Mission Type").
 *
 * <p>Original case is preserved. Both the Agency and the Handler lowercase before comparing, so matching is
 * case-insensitive by construction without this class having to normalize.
 *
 * <p>Paths here are repository-relative, {@code /}-separated strings straight out of {@link RepositoryContents} —
 * never {@link java.nio.file.Path}. That is what keeps the rules the same on every machine: {@code Path} carries
 * the host's separator and, on a case-insensitive filesystem, its case folding, so the same repository could
 * otherwise resolve different Mission Types depending on where the Agency happened to be running.
 */
public class MissionTypeResolver {
  /**
   * The one name this class looks for, in both of the roles it plays: a directory's own {@code .mission-types}
   * file, and the {@code .mission-types} suffix appended to a sibling file's full name. Deliberately a single
   * constant rather than two holding the same string — two names invite a caller to test for both, where the
   * suffix test always subsumes the exact-name test and the redundancy is invisible at the call site.
   */
  public static final String FILE_NAME = ".mission-types";
  private final RepositoryContents contents;
  private final Map<String, List<String>> directoryCache = new HashMap<>();

  public MissionTypeResolver(RepositoryContents contents) {
    this.contents = contents;
  }

  /**
   * @param path The repository-relative path of the source file.
   * @return The Mission Types that apply to it, in file order, or the empty list if none are declared anywhere
   *     above it.
   */
  public List<String> resolve(String path) {
    var sibling = path + FILE_NAME;
    if (present(sibling)) {
      return read(sibling);
    }

    // The empty string is the repository root, whose own .mission-types applies to everything beneath it. The loop
    // always reaches it: parent() strips one segment at a time and returns "" for a path with no separator left,
    // then null for "" itself, so the root is visited exactly once and the walk terminates there.
    for (var directory = parent(path); directory != null; directory = parent(directory)) {
      var types = cachedDirectoryTypes(directory);
      if (types != null) {
        return types;
      }
    }

    return List.of();
  }

  private static String parent(String path) {
    if (path.isEmpty()) {
      return null;
    }

    var slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private List<String> cachedDirectoryTypes(String directory) {
    if (directoryCache.containsKey(directory)) {
      return directoryCache.get(directory);
    }

    var file = directory.isEmpty() ? FILE_NAME : directory + "/" + FILE_NAME;
    var types = present(file) ? read(file) : null;
    directoryCache.put(directory, types);
    return types;
  }

  // A symbolic link is a hard failure rather than a silent skip, exactly as it is for every other file in the
  // tree. Both of the files this class reads are reached before BriefBuilder's own walk can ever see them --
  // <root>/.mission-types lies outside the mapped top-level directories the walk visits at all, and a sibling
  // <file>.mission-types is read while <file> is being handled. Publishing a link's target path as a list of
  // Mission Types would silently change which files every Handler in the fleet installs.
  private boolean present(String path) {
    if (contents.symlink(path)) {
      throw new BriefBuildException("The source tree contains a symbolic link [" + path + "]. Links are not "
                                    + "supported because they can resolve outside the tree.");
    }

    return contents.file(path) != null;
  }

  private List<String> read(String path) {
    return new String(contents.file(path), StandardCharsets.UTF_8)
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .distinct()
        .toList();
  }
}
