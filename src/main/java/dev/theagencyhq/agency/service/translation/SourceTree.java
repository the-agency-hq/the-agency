/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The Brief source repository as the Translators see it: a sorted, {@code /}-separated view over
 * {@link RepositoryContents} that resolves Mission Types, rejects symbolic links, hides the {@code .mission-types}
 * metadata files, and builds {@link BriefFile}s so every Translator encodes, checksums and modes a file the same way.
 *
 * <p>It also parses the source concepts the Translators rewrite — {@link #rules()}, {@link #agents()} — once, on
 * first use, and hands every Translator the same parsed objects. The contents never change, so the parse is never
 * repeated; a build runs on one thread, so the caching needs no synchronization.
 */
public final class SourceTree {
  private static final System.Logger logger = System.getLogger(SourceTree.class.getName());
  private final RepositoryContents contents;
  private final MissionTypeResolver resolver;
  private List<AgentDefinition> agents;
  private List<Rule> rules;

  public SourceTree(RepositoryContents contents) {
    this.contents = contents;
    this.resolver = new MissionTypeResolver(contents);
  }

  /**
   * @param path      A source path beneath {@code directory}.
   * @param directory A top-level source directory.
   * @return The path relative to the directory.
   */
  public static String remainder(String path, String directory) {
    return path.substring(directory.length() + 1);
  }

  private static String decode(byte[] bytes) throws CharacterCodingException {
    // Strict decoding is the point: a lenient decoder replaces invalid bytes with U+FFFD, which would silently
    // corrupt every binary asset rather than routing it through base64.
    return StandardCharsets.UTF_8.newDecoder()
                                 .onMalformedInput(CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(CodingErrorAction.REPORT)
                                 .decode(ByteBuffer.wrap(bytes))
                                 .toString();
  }

  /**
   * @return Every Markdown agent under {@code agents/}, in path order. Files that are not {@code .md} are left out.
   * @throws BriefBuildException if an agent file has no frontmatter or no description. Every Agent needs the
   *                             description to decide when to delegate, and some reject an agent file without one at
   *                             load time, so its absence is an authoring error rather than something to paper over
   *                             with an empty string.
   */
  public List<AgentDefinition> agents() {
    if (agents == null) {
      agents = readAgents();
    }

    return agents;
  }

  /**
   * Copies a source file to a Brief path verbatim: the bytes, the executable bit and the Mission Types all carry over
   * unchanged.
   *
   * @param sourcePath The source path.
   * @param outputPath The Brief path.
   * @return The Brief file.
   */
  public BriefFile copy(String sourcePath, String outputPath) {
    var bytes = contents.file(sourcePath);
    var encoded = encode(bytes);
    var mode = contents.executable(sourcePath) ? BriefFile.EXECUTABLE_MODE : BriefFile.DEFAULT_MODE;
    return new BriefFile(outputPath, encoded.encoding(), mode, encoded.content(), Checksums.sha256Hex(bytes),
        resolver.resolve(sourcePath));
  }

  /**
   * Copies every file beneath a source directory to the same relative path beneath an output root.
   *
   * @param directory  A top-level source directory.
   * @param outputRoot The Brief directory to copy it to.
   * @return The Brief files, in path order. Empty when the directory is absent.
   */
  public List<BriefFile> copyTree(String directory, String outputRoot) {
    return under(directory).stream().map(path -> copy(path, outputRoot + "/" + remainder(path, directory))).toList();
  }

  /**
   * Builds a Brief file whose content a Translator wrote itself rather than copied.
   *
   * @param outputPath   The Brief path.
   * @param content      The text.
   * @param missionTypes The Mission Types it applies to; empty means every one.
   * @return The Brief file, read-only and text-encoded.
   */
  public BriefFile generated(String outputPath, String content, List<String> missionTypes) {
    var bytes = content.getBytes(StandardCharsets.UTF_8);
    return new BriefFile(outputPath, BriefFile.DEFAULT_ENCODING, BriefFile.DEFAULT_MODE, content,
        Checksums.sha256Hex(bytes), missionTypes);
  }

  /**
   * @param path A source path.
   * @return The Mission Types that apply to it, in author order, or the empty list for every Mission Type.
   */
  public List<String> missionTypes(String path) {
    return resolver.resolve(path);
  }

  /**
   * @return Every Markdown rule under {@code rules/}, in path order. Files that are not {@code .md} are left out.
   */
  public List<Rule> rules() {
    if (rules == null) {
      rules = readRules();
    }

    return rules;
  }

  /**
   * @param path A source path.
   * @return The file decoded as UTF-8.
   * @throws BriefBuildException if the bytes are not valid UTF-8. A Translator that asks for text is about to rewrite
   *                             it, and rewriting a file it cannot read would silently corrupt it.
   */
  public String text(String path) {
    try {
      return decode(contents.file(path));
    } catch (CharacterCodingException e) {
      throw new BriefBuildException("The source file [" + path + "] is not valid UTF-8 text", e);
    }
  }

  /**
   * @param directory A top-level source directory.
   * @return Every file beneath it, sorted, with the {@code .mission-types} metadata files left out. Empty when the
   *     directory is absent.
   * @throws BriefBuildException if any file beneath it is a symbolic link. A link is the one construct that turns a
   *                             valid relative path into something that resolves outside the tree once the Handler
   *                             writes it out, so it fails the build rather than being silently dropped. Checked before
   *                             the metadata filter so a link named {@code x.mission-types} is still rejected rather
   *                             than skipped.
   */
  public List<String> under(String directory) {
    var prefix = directory + "/";
    var paths = new ArrayList<String>();
    for (var path : contents.paths()) {
      if (!path.startsWith(prefix) || path.length() == prefix.length()) {
        continue;
      }

      if (contents.symlink(path)) {
        throw new BriefBuildException("The source tree contains a symbolic link [" + path + "]. Links are not "
            + "supported because they can resolve outside the tree.");
      }

      if (path.endsWith(MissionTypeResolver.FILE_NAME)) {
        continue;
      }

      paths.add(path);
    }

    return paths;
  }

  private Encoded encode(byte[] bytes) {
    try {
      return new Encoded(BriefFile.DEFAULT_ENCODING, decode(bytes));
    } catch (CharacterCodingException e) {
      return new Encoded(BriefFile.ENCODING_BASE64, Base64.getEncoder().encodeToString(bytes));
    }
  }

  private List<AgentDefinition> readAgents() {
    var result = new ArrayList<AgentDefinition>();
    for (var path : under(AgentDefinition.DIRECTORY)) {
      if (!path.endsWith(".md")) {
        logger.log(System.Logger.Level.DEBUG, "Ignoring non-Markdown agent file [{0}]", path);
        continue;
      }

      var frontmatter = Frontmatter.parse(text(path));
      if (!frontmatter.present()) {
        throw new BriefBuildException("The agent file [" + path + "] has no frontmatter, so it declares no [name] "
            + "or [description]");
      }

      var description = frontmatter.scalar("description");
      if (description == null || description.isBlank()) {
        throw new BriefBuildException("The agent file [" + path + "] declares no [description]");
      }

      var stem = path.substring(path.lastIndexOf('/') + 1, path.length() - ".md".length());
      var name = frontmatter.scalar("name");
      result.add(new AgentDefinition(path, stem, name == null || name.isBlank() ? stem : name.strip(),
          description.strip(), frontmatter.body().strip(), BriefFile.canonicalMissionTypes(missionTypes(path))));
    }

    return List.copyOf(result);
  }

  private List<Rule> readRules() {
    var result = new ArrayList<Rule>();
    for (var path : under(Rule.DIRECTORY)) {
      if (!path.endsWith(".md")) {
        continue;
      }

      var frontmatter = Frontmatter.parse(text(path));
      var relative = remainder(path, Rule.DIRECTORY);
      var name = relative.substring(0, relative.length() - ".md".length()).replace('/', '-');
      result.add(new Rule(path, name, frontmatter.list(Rule.PATHS_KEY),
          BriefFile.canonicalMissionTypes(missionTypes(path)), frontmatter.body().strip()));
    }

    return List.copyOf(result);
  }

  private record Encoded(String encoding, String content) {
  }
}
