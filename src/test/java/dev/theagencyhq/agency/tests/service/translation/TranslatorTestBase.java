/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service.translation;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

/**
 * A repository is a map of paths to bytes, which is exactly what a Translator is handed in production. The fixtures
 * are the same across every Translator's test so the outputs can be compared side by side.
 */
public abstract class TranslatorTestBase {
  protected static final String AGENT = "---\nname: code-reviewer\ndescription: Reviews code\nmodel: opus\n"
                                        + "tools: [Read, Grep]\n---\n\nYou review.\n";
  protected static final String PLAIN_RULE = "# Plain\n\nAlways.\n";
  protected static final String SCOPED_RULE = "---\npaths:\n  - \"**/*.java\"\n  - project.latte\n---\n\n# Java\n\n"
                                              + "Two spaces.\n";
  protected static final String PATHS_NOTE = "_The following applies only to files matching `**/*.java`, "
                                             + "`project.latte`._";
  protected Map<String, byte[]> files;

  @BeforeMethod
  public void beforeMethod() {
    files = new HashMap<>();
  }

  protected static BriefFile fileAt(List<BriefFile> out, String path) {
    return out.stream()
              .filter(f -> f.path().equals(path))
              .findFirst()
              .orElseThrow(() -> new AssertionError("No file at [" + path + "] in ["
                                                    + out.stream().map(BriefFile::path).toList() + "]"));
  }

  protected static List<String> paths(List<BriefFile> out) {
    return out.stream().map(BriefFile::path).toList();
  }

  protected List<BriefFile> translate(Translator translator) {
    var modes = new HashMap<String, String>();
    files.keySet().forEach(p -> modes.put(p, "100644"));
    return translator.translate(new SourceTree(new RepositoryContents("commit", files, modes)));
  }

  protected void write(String path, String content) {
    files.put(path, content.getBytes(StandardCharsets.UTF_8));
  }
}
