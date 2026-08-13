/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.github;

import module java.base;

import dev.theagencyhq.agency.github.*;
import dev.theagencyhq.agency.model.github.*;
import dev.theagencyhq.agency.util.Checksums;

/**
 * An in-memory GitHub, standing in for {@link GitHubClient} throughout the suite.
 *
 * <p>The one thing worth explaining is the commit. A repository here has no history — it is a mutable map of paths
 * to bytes — and its head is the SHA-256 of that map's contents. That gives the poller exactly the property it
 * depends on for real: the head moves if and only if something in the repository changed, and it moves for a change
 * to <em>any</em> file, including one the Brief does not map. So {@code putFile("README.md", ...)} reproduces the
 * scenario that matters most here — a new commit that produces no new Brief version — without simulating Git.
 *
 * <p>Every failure mode the Agency has a branch for can be provoked: {@link #revokeAll} for an authorization that
 * has lapsed, {@link #failExchange} and {@link #failRefresh} for the two OAuth grants, {@link #fail} and
 * {@link #failContents} for GitHub being unreachable, and simply not registering a repository for one that is not
 * visible.
 */
public class FakeGitHubClient implements GitHubClient {
  public static final long INSTALLATION_ID = 42L;
  public static final String USER_LOGIN = "agency-test";
  private final Map<String, Repository> repositories = new ConcurrentHashMap<>();
  private final Set<String> revoked = ConcurrentHashMap.newKeySet();
  private final AtomicInteger tokenCounter = new AtomicInteger();
  private volatile RuntimeException contentsFailure;
  private volatile RuntimeException failure;
  private volatile boolean failExchange;
  private volatile boolean failRefresh;
  private volatile Duration tokenLifetime = Duration.ofHours(8);

  private static String key(String owner, String repository) {
    return (owner + "/" + repository).toLowerCase(Locale.ROOT);
  }

  /**
   * @return A fresh repository, registered and ready to have files put in it. It already carries the settings
   *     marker, because a repository without one is not a Brief source and every test that wants that case says so
   *     explicitly by removing it.
   */
  public Repository add(String owner, String name) {
    var repository = new Repository(owner, name);
    repository.putFile("the-agency-hq-settings.json", "{\"version\":\"1.0.0\"}");
    repositories.put(key(owner, name), repository);
    return repository;
  }

  @Override
  public RepositoryContents contents(String accessToken, String owner, String repository, String commit) {
    throwIfFailing();
    var contentsOnly = contentsFailure;
    if (contentsOnly != null) {
      throw contentsOnly;
    }

    requireValid(accessToken);
    var found = find(accessToken, owner, repository);
    if (found == null) {
      throw new GitHubException("No repository [" + owner + "/" + repository + "]");
    }

    return new RepositoryContents(commit, found.files(), found.modes());
  }

  @Override
  public GitHubTokens exchangeCode(String code, String redirectURI) {
    throwIfFailing();
    return failExchange ? null : issue();
  }

  /**
   * Makes every API call throw, as an unreachable GitHub does.
   *
   * @param failure The exception to throw, or {@code null} to stop failing.
   */
  public void fail(RuntimeException failure) {
    this.failure = failure;
  }

  /**
   * Makes only the archive download throw, leaving the commit lookup working. The pair is what lets a test prove
   * a cycle did not download anything: with both failing, an unchanged source and a downloading one would report
   * the same status and the assertion would prove nothing.
   *
   * @param failure The exception to throw, or {@code null} to stop failing.
   */
  public void failContents(RuntimeException failure) {
    this.contentsFailure = failure;
  }

  public void failExchange(boolean fail) {
    this.failExchange = fail;
  }

  public void failRefresh(boolean fail) {
    this.failRefresh = fail;
  }

  @Override
  public String head(String accessToken, String owner, String repository, String ref) {
    throwIfFailing();
    requireValid(accessToken);
    var found = find(accessToken, owner, repository);
    return found == null || !found.branches.contains(ref) ? null : found.commit();
  }

  @Override
  public List<GitHubInstallation> installations(String accessToken) {
    throwIfFailing();
    requireValid(accessToken);
    return List.of(new GitHubInstallation(INSTALLATION_ID));
  }

  @Override
  public byte[] readFile(String accessToken, String owner, String repository, String ref, String path) {
    throwIfFailing();
    requireValid(accessToken);
    var found = find(accessToken, owner, repository);
    return found == null || !found.branches.contains(ref) ? null : found.files.get(path);
  }

  @Override
  public GitHubTokens refresh(String refreshToken) {
    throwIfFailing();
    return failRefresh || revoked.contains(refreshToken) ? null : issue();
  }

  @Override
  public List<GitHubRepository> repositories(String accessToken, long installationId) {
    throwIfFailing();
    requireValid(accessToken);
    return repositories.values()
                       .stream()
                       .map(r -> new GitHubRepository(r.owner + "/" + r.name, r.defaultBranch))
                       .sorted(Comparator.comparing(GitHubRepository::fullName))
                       .toList();
  }

  /**
   * Empties the fake between tests, so a repository one method registered cannot be visible to the next.
   */
  public void reset() {
    repositories.clear();
    revoked.clear();
    contentsFailure = null;
    failure = null;
    failExchange = false;
    failRefresh = false;
    tokenLifetime = Duration.ofHours(8);
  }

  /**
   * Makes every token and refresh token issued so far stop working, as revoking the App's authorization on GitHub
   * does.
   */
  public void revokeAll() {
    for (var i = 0; i <= tokenCounter.get(); i++) {
      revoked.add("access-" + i);
      revoked.add("refresh-" + i);
    }
  }

  /**
   * @param lifetime How long a newly issued access token lasts. Tests set this to something already in the past to
   *                 force the refresh path on the very next use.
   */
  public void tokenLifetime(Duration lifetime) {
    this.tokenLifetime = lifetime;
  }

  @Override
  public GitHubUser user(String accessToken) {
    throwIfFailing();
    return valid(accessToken) ? new GitHubUser(USER_LOGIN) : null;
  }

  private Repository find(String accessToken, String owner, String repository) {
    return valid(accessToken) ? repositories.get(key(owner, repository)) : null;
  }

  // Mirrors the real client's 401 handling, which is the distinction the poller branches on: a rejected credential
  // is not the same answer as a repository that is not there, and a fake that reported both as absence would let
  // the NOT_CONNECTED path go untested.
  private void requireValid(String accessToken) {
    if (!valid(accessToken)) {
      throw new GitHubUnauthorizedException("The token [" + accessToken + "] is not valid");
    }
  }

  private GitHubTokens issue() {
    var n = tokenCounter.incrementAndGet();
    return new GitHubTokens("access-" + n, Instant.now().plus(tokenLifetime), "refresh-" + n,
        Instant.now().plus(Duration.ofDays(180)));
  }

  private void throwIfFailing() {
    var current = failure;
    if (current != null) {
      throw current;
    }
  }

  private boolean valid(String accessToken) {
    return accessToken != null && !revoked.contains(accessToken);
  }

  /**
   * One repository: a mutable set of files, the branches that exist, and the modes Git would record for them.
   */
  @SuppressWarnings("UnusedReturnValue")
  public static class Repository {
    private final Set<String> branches = ConcurrentHashMap.newKeySet();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> modes = new ConcurrentHashMap<>();
    private final String name;
    private final String owner;
    private volatile String defaultBranch = "main";

    Repository(String owner, String name) {
      this.name = name;
      this.owner = owner;
      branches.add(defaultBranch);
    }

    public Repository addBranch(String branch) {
      branches.add(branch);
      return this;
    }

    /**
     * @return The head commit: the SHA-256 over every path and every byte in the repository, so it moves exactly
     *     when the content does.
     */
    public String commit() {
      var digest = new StringBuilder();
      files.keySet().stream().sorted().forEach(path ->
          digest.append(path).append(' ')
                .append(modes.getOrDefault(path, "100644")).append(' ')
                .append(Checksums.sha256Hex(files.get(path))).append('\n'));
      return Checksums.sha256Hex(digest.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Repository defaultBranch(String branch) {
      this.defaultBranch = branch;
      branches.add(branch);
      return this;
    }

    public Map<String, byte[]> files() {
      return Map.copyOf(files);
    }

    public Repository executable(String path, String content) {
      putFile(path, content);
      modes.put(path, TreeEntry.MODE_EXECUTABLE);
      return this;
    }

    public Map<String, String> modes() {
      return Map.copyOf(modes);
    }

    public Repository putBytes(String path, byte[] content) {
      files.put(path, content);
      modes.putIfAbsent(path, "100644");
      return this;
    }

    public Repository putFile(String path, String content) {
      return putBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public Repository removeBranch(String branch) {
      branches.remove(branch);
      return this;
    }

    public Repository removeFile(String path) {
      files.remove(path);
      modes.remove(path);
      return this;
    }

    public Repository symlink(String path, String target) {
      putFile(path, target);
      modes.put(path, TreeEntry.MODE_SYMLINK);
      return this;
    }
  }
}
