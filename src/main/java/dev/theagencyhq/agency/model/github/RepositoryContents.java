/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module java.base;

/**
 * A whole repository at one commit, held in memory: every blob's bytes and every blob's Git mode, keyed by
 * repository-relative path.
 *
 * <p>In memory rather than unpacked into a temporary directory, and that is the point of the type existing at all.
 * A Brief source tree is prose and configuration — rules, skills, agent files — so the whole of one is smaller than
 * the JSON document the Agency already builds out of it and holds in a single string. Writing it to disk first would
 * buy nothing and cost the two things the filesystem always costs: a temporary directory to clean up on every
 * failure path, and a build whose outcome depends on the umask, the case-sensitivity, and the free space of whatever
 * machine the Agency happens to be running on.
 *
 * <p>A submodule contributes no blob at all — GitHub leaves gitlinks out of the archive — so one never appears in
 * {@link #files}. A symbolic link does appear, as a small file whose content is the path it points at, which is why
 * {@link #symlink} exists for the build to reject one with.
 * <p>
 * TODO: Ensure that a bad repository (large, junk, etc) doesn't crash the service.
 */
public record RepositoryContents(String commit, Map<String, byte[]> files, Map<String, String> modes) {
  public RepositoryContents {
    files = Map.copyOf(files);
    modes = Map.copyOf(modes);
  }

  /**
   * @param path A repository-relative path.
   * @return The file's bytes, or {@code null} if the repository has no such file.
   */
  public byte[] file(String path) {
    return files.get(path);
  }

  /**
   * @param path A repository-relative path.
   * @return True if Git records the file as executable. False for a path with no mode, which is the honest answer:
   *     the mode is advisory metadata and the default is the safe one.
   */
  public boolean executable(String path) {
    return TreeEntry.MODE_EXECUTABLE.equals(modes.get(path));
  }

  /**
   * @return Every file path, sorted, so a build walks the tree in one order regardless of how the ZIP was laid out.
   */
  public List<String> paths() {
    return files.keySet().stream().sorted().toList();
  }

  /**
   * @param path A repository-relative path.
   * @return True if Git records the file as a symbolic link.
   */
  public boolean symlink(String path) {
    return TreeEntry.MODE_SYMLINK.equals(modes.get(path));
  }
}
