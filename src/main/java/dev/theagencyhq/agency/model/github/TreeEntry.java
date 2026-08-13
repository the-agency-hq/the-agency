/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module org.lattejava.json;

/**
 * One entry in a recursive Git tree, from {@code GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1}.
 *
 * <p>This exists for one member: {@code mode}. The zipball the Agency downloads the content from carries Unix
 * permissions in each entry's external attributes, and {@code java.util.zip} exposes no way to read them, so the
 * executable bit — which the Brief must carry, because the Handler writes these files out with it — has to come
 * from somewhere else. The tree is that somewhere, and it costs exactly one extra request per build.
 *
 * @param path The repository-relative path, with {@code /} separators and no leading slash.
 * @param mode The Git file mode: {@code 100644} regular, {@code 100755} executable, {@code 040000} directory,
 *             {@code 120000} symbolic link, {@code 160000} submodule.
 * @param type {@code blob}, {@code tree}, or {@code commit}.
 */
@JSON
public record TreeEntry(String path, String mode, String type) {
  public static final String MODE_EXECUTABLE = "100755";
  public static final String MODE_SUBMODULE = "160000";
  public static final String MODE_SYMLINK = "120000";
}
