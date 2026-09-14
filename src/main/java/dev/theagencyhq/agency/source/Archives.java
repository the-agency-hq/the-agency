/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.source;

import module java.base;

/**
 * Unpacks the ZIP archive a repository host returns for one commit, in memory, into repository-relative paths. Every
 * host lays the archive out the same way — every entry under one root directory named for the repository and the
 * commit — so one reader serves them all.
 */
public final class Archives {
  /**
   * The ceiling on an unpacked repository, and the reason a Brief source may not be an ordinary application repository.
   * A ZIP is decompressed into memory here, so without a limit a repository with a large asset — or a deliberately
   * crafted one — is an out-of-memory failure that takes the whole Agency down rather than one Organization's build.
   * Generous by two orders of magnitude for a tree of prose and configuration.
   */
  public static final long MAX_CONTENT_BYTES = 64L * 1024 * 1024;

  private Archives() {
  }

  /**
   * Reads every file out of an archive. The root directory the host wraps the tree in is stripped. Directory entries
   * and anything that escapes the archive root are dropped rather than rejected: the ZIP is the host's own output,
   * so a traversal entry means something upstream is wrong and the honest response is to not have the file.
   *
   * @param archive The ZIP bytes.
   * @param host    The host's name, for the failure messages.
   * @return The files by repository-relative path.
   * @throws RepositoryException If the archive cannot be read or expands past {@link #MAX_CONTENT_BYTES}.
   */
  public static Map<String, byte[]> unzip(byte[] archive, String host) {
    var files = new HashMap<String, byte[]>();
    var total = 0L;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.isDirectory()) {
          continue;
        }

        var name = entry.getName();
        var slash = name.indexOf('/');
        if (slash < 0) {
          continue;
        }

        var path = name.substring(slash + 1);
        if (path.isEmpty() || path.startsWith("/") || path.equals("..") || path.startsWith("../")
            || path.contains("/../") || path.endsWith("/..")) {
          continue;
        }

        var bytes = zip.readAllBytes();
        total += bytes.length;
        if (total > MAX_CONTENT_BYTES) {
          throw new RepositoryException("The repository archive expands to more than [" + MAX_CONTENT_BYTES
              + "] bytes, which is larger than a Brief source repository may be");
        }

        files.put(path, bytes);
      }
    } catch (IOException e) {
      throw new RepositoryException("Unable to read the repository archive " + host + " returned", e);
    }

    return files;
  }
}
