/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

/**
 * One entry of a Bitbucket directory listing. Bitbucket has no Git tree endpoint and reports no Git mode: a file
 * entry carries an {@code attributes} list instead, naming the properties the mode would have encoded. This record
 * exists to translate it back ({@link #mode()}), so the build above the client sees the same modes from Bitbucket as
 * from every other host.
 *
 * @param path       The repository-relative path, with {@code /} separators and no leading slash.
 * @param type       {@code commit_file} or {@code commit_directory}.
 * @param attributes The file's properties: {@code executable}, {@code link} (a symbolic link whose content is its
 *                   target), {@code subrepository} (a submodule whose content is its commit), {@code binary},
 *                   {@code lfs}. Empty for an ordinary file; {@code null} for a directory.
 */
@JSON
public record BitbucketTreeEntry(String path, String type, List<String> attributes) {
  public static final String TYPE_FILE = "commit_file";

  /**
   * @return The Git mode the attributes describe. A link or a submodule is reported as such before anything else,
   *     because those are the two the build must refuse; an executable bit on either would be meaningless anyway.
   */
  public String mode() {
    if (attributes != null) {
      if (attributes.contains("link")) {
        return TreeEntry.MODE_SYMLINK;
      }
      if (attributes.contains("subrepository")) {
        return TreeEntry.MODE_SUBMODULE;
      }
      if (attributes.contains("executable")) {
        return TreeEntry.MODE_EXECUTABLE;
      }
    }

    return TreeEntry.MODE_REGULAR;
  }
}
