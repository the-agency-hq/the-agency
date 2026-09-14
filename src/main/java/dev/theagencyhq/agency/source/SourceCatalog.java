/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.source;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;

import org.lattejava.web.Configuration;

/**
 * Which kinds of Brief source this server offers, and the client for each: the one place that knows a kind's
 * configuration keys, so the Sources page, the routes, and the poller all agree on what "configured" means.
 *
 * <p>A kind is configured when its OAuth application's client id and secret are both set. Nothing here is required
 * configuration: a server with no kind configured starts, serves the Briefs it already holds, and
 * tells an Owner on the Sources page that there is nothing to connect to yet. That is deliberate — the credentials
 * are the one thing a deployment cannot have by default, since a checked-in GitHub App would let anyone who cloned
 * the repository act as it, and hiding the unconfigured kind is more honest than offering a button that fails.
 *
 * <p>The clients exist whether or not their kind is configured, because the scope builds every bean; an
 * unconfigured client is simply never asked anything. The authorization URL is built here, from configuration
 * rather than asked of the client, so it stays real when the clients are faked; and a fresh source of a kind is
 * created here, because what one needs at birth beyond its credential — GitLab's instance origin — is
 * configuration too. How a source describes itself afterwards is the source's own business
 * ({@code BriefSourceConfig#details()}).
 */
@Singleton
public class SourceCatalog {
  public static final String GITLAB_DEFAULT_BASE_URL = "https://gitlab.com";
  private final List<BriefSourceType> available;
  private final String bitbucketClientId;
  private final Map<BriefSourceType, RepositoryClient> clients = new EnumMap<>(BriefSourceType.class);
  private final String gitHubClientId;
  private final String gitLabBaseURL;
  private final String gitLabClientId;

  public SourceCatalog(Configuration config, GitHubClient github, GitLabClient gitlab, BitbucketClient bitbucket) {
    this.clients.put(BriefSourceType.BITBUCKET, bitbucket);
    this.clients.put(BriefSourceType.GITHUB, github);
    this.clients.put(BriefSourceType.GITLAB, gitlab);
    this.available = Arrays.stream(BriefSourceType.values()).filter(type -> configured(config, type)).toList();
    this.bitbucketClientId = config.get("bitbucket.clientId", "");
    this.gitHubClientId = config.get("github.clientId", "");
    this.gitLabBaseURL = baseURL(config.get("gitlab.baseURL", GITLAB_DEFAULT_BASE_URL));
    this.gitLabClientId = config.get("gitlab.clientId", "");

    // The App's slug is not what makes GitHub configured -- the credentials are -- but a configured GitHub without
    // it would render an install link to https://github.com/apps//installations/new, which is a failure better
    // reported at startup than discovered by the first operator to click it.
    if (available.contains(BriefSourceType.GITHUB) && blank(config.get("github.appName"))) {
      throw new IllegalStateException("[github.clientId] and [github.clientSecret] are set, so [github.appName] is "
          + "required too");
    }
  }

  /**
   * @param config The configuration.
   * @param type   A kind of source.
   * @return True if the configuration carries the OAuth credentials that kind needs.
   */
  public static boolean configured(Configuration config, BriefSourceType type) {
    return switch (type) {
      case BITBUCKET -> !blank(config.get("bitbucket.clientId")) && !blank(config.get("bitbucket.clientSecret"));
      case GITHUB -> !blank(config.get("github.clientId")) && !blank(config.get("github.clientSecret"));
      case GITLAB -> !blank(config.get("gitlab.clientId")) && !blank(config.get("gitlab.clientSecret"));
    };
  }

  private static String baseURL(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Builds the URL that starts a host's authorization.
   *
   * @param type        The kind of source.
   * @param redirectURI Where the host returns the browser.
   * @param state       The opaque value the callback checks against its cookie.
   * @return The absolute URL to redirect the browser to.
   */
  public String authorizeURL(BriefSourceType type, String redirectURI, String state) {
    return switch (type) {
      case BITBUCKET -> BitbucketHTTPClient.authorizeURL(bitbucketClientId, redirectURI, state);
      case GITHUB -> GitHubHTTPClient.authorizeURL(gitHubClientId, redirectURI, state);
      case GITLAB -> GitLabHTTPClient.authorizeURL(gitLabBaseURL, gitLabClientId, redirectURI, state);
    };
  }

  /**
   * @return The configured kinds, in declaration order. Empty when this server offers no source at all.
   */
  public List<BriefSourceType> available() {
    return available;
  }

  /**
   * @param type A kind of source.
   * @return Its client — whether or not the kind is configured, so a caller resolving a source's client never gets
   *     {@code null}; the caller checks {@link #configured} before asking it anything.
   */
  public RepositoryClient client(BriefSourceType type) {
    return clients.get(type);
  }

  public boolean configured(BriefSourceType type) {
    return available.contains(type);
  }

  /**
   * @param type       The kind of source.
   * @param connection The authorization just granted.
   * @return A source of that kind holding that credential and not yet told what to poll — the state the OAuth
   *     callback produces.
   */
  public BriefSourceConfig unregistered(BriefSourceType type, OAuthConnection connection) {
    return switch (type) {
      case BITBUCKET -> new BitbucketConfig(connection, null, null);
      case GITHUB -> new GitHubConfig(connection, null, null, null);
      case GITLAB -> new GitLabConfig(connection, gitLabBaseURL, null, null);
    };
  }
}
