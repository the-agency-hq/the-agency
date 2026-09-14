/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.github;

import module dev.theagencyhq.agency;
import module java.base;
import module java.net.http;

import dev.theagencyhq.agency.model.github.internal.*;

/**
 * The real {@link GitHubClient}: two OAuth calls against {@code github.com}, and the reads against
 * {@code api.github.com} made with a user-to-server token.
 *
 * <p>Every API call carries the {@code X-GitHub-Api-Version} header pinning the REST contract, so a future default
 * version on GitHub's side cannot change a response shape underneath a running Agency.
 */
public class GitHubHTTPClient extends HTTPRepositoryClient implements GitHubClient {
  public static final String API_URL = "https://api.github.com";
  public static final String API_VERSION = "2022-11-28";
  public static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
  public static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
  private final String clientId;
  private final String clientSecret;

  public GitHubHTTPClient(String clientId, String clientSecret) {
    super("GitHub", API_URL, TOKEN_URL);
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  /**
   * Builds the URL that starts the GitHub authorization. No {@code scope} parameter: a GitHub App's permissions are
   * declared on the App and chosen at installation time, so a scope here would be ignored at best.
   *
   * @param clientId    The GitHub App's client id.
   * @param redirectURI Where GitHub returns the browser. Must match the App's configured callback.
   * @param state       The opaque value the callback checks against its cookie.
   * @return The absolute URL to redirect the browser to.
   */
  public static String authorizeURL(String clientId, String redirectURI, String state) {
    return AUTHORIZE_URL
        + "?client_id=" + encode(clientId)
        + "&redirect_uri=" + encode(redirectURI)
        + "&state=" + encode(state);
  }

  private static String repositoryPath(String fullName) {
    return "/repos/" + repositorySegments(fullName);
  }

  @Override
  public OAuthTokens exchangeCode(String code, String redirectURI) {
    return token(form("client_id", clientId, "client_secret", clientSecret, "code", code, "redirect_uri", redirectURI));
  }

  @Override
  public String head(String accessToken, String fullName, String ref) {
    // The `sha` media type makes the whole response body the commit SHA, rather than the full commit object with
    // its author, committer, message, tree, parents and stats -- none of which the poller reads.
    var response = send(request(accessToken, repositoryPath(fullName) + "/commits/" + encode(ref))
        .setHeader("Accept", "application/vnd.github.sha").GET().build(), HttpResponse.BodyHandlers.ofString());
    if (absent(response, "resolving", fullName + "@" + ref)) {
      return null;
    }

    var sha = response.body().trim();
    return sha.isEmpty() ? null : sha;
  }

  @Override
  public String login(String accessToken) {
    var response = send(request(accessToken, "/user").GET().build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401) {
      return null;
    }

    require(response, "reading", "the current user");
    return parse(response.body(), GitHubUserJSON::fromJSON, "/user").login();
  }

  @Override
  public byte[] readFile(String accessToken, String fullName, String ref, String path) {
    // The `raw` media type returns the file's bytes rather than a JSON object with base64 in it, which keeps this
    // honest for a binary file and saves a decode for a text one.
    var response = send(request(accessToken, repositoryPath(fullName) + "/contents/" + encodePath(path) + "?ref="
            + encode(ref)).setHeader("Accept", "application/vnd.github.raw").GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return absent(response, "reading [" + path + "] in", fullName + "@" + ref) ? null : response.body();
  }

  @Override
  public OAuthTokens refresh(String refreshToken) {
    return token(form("client_id", clientId, "client_secret", clientSecret, "grant_type", "refresh_token",
        "refresh_token", refreshToken));
  }

  /**
   * Every repository the operator can offer, flattened across every installation of the Agency's GitHub App they
   * can reach. Two levels rather than one because that is how a GitHub App grants access: the App is installed on
   * an account, and each installation covers the repositories that account chose to give it.
   */
  @Override
  public List<RepositorySummary> repositories(String accessToken) {
    var all = new ArrayList<RepositorySummary>();
    for (var installation : installations(accessToken)) {
      for (var repository : repositories(accessToken, installation.id())) {
        all.add(new RepositorySummary(repository.fullName(), repository.defaultBranch()));
      }
    }

    return all;
  }

  /**
   * The zipball, which GitHub answers with a 302 to a signed codeload URL that {@link #download} follows.
   */
  @Override
  protected byte[] archive(String accessToken, String fullName, String commit) {
    return download(request(accessToken, repositoryPath(fullName) + "/zipball/" + encode(commit)).GET().build(),
        fullName + "@" + commit);
  }

  /**
   * GitHub's own media type, and the header that pins the REST contract's version.
   */
  @Override
  protected HttpRequest.Builder request(String accessToken, URI uri) {
    return super.request(accessToken, uri)
                .setHeader("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION);
  }

  @Override
  protected Map<String, String> tree(String accessToken, String fullName, String commit) {
    var response = send(request(accessToken, repositoryPath(fullName) + "/git/trees/" + encode(commit)
        + "?recursive=1").GET().build(), HttpResponse.BodyHandlers.ofString());
    unauthorized(response.statusCode(), fullName + "@" + commit);
    require(response, "reading the tree of", fullName + "@" + commit);

    var body = parse(response.body(), TreeResponseJSON::fromJSON, "/repos/{owner}/{repo}/git/trees/{sha}");
    if (body.truncated()) {
      throw new RepositoryException("The tree of [" + fullName + "@" + commit + "] is too large for GitHub to "
          + "return in full, so the file modes cannot be read. A Brief source repository must be small enough to "
          + "list in one request");
    }

    var modes = new HashMap<String, String>();
    for (var entry : body.tree() == null ? List.<TreeEntry>of() : body.tree()) {
      if (entry.path() != null && entry.mode() != null) {
        modes.put(entry.path(), entry.mode());
      }
    }

    return modes;
  }

  private List<GitHubInstallation> installations(String accessToken) {
    var all = new ArrayList<GitHubInstallation>();
    for (var page = 1; page <= MAX_PAGES; page++) {
      var response = send(request(accessToken, "/user/installations?per_page=" + PAGE_SIZE + "&page=" + page).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "the current user");
      require(response, "listing the installations of", "the current user");

      var body = parse(response.body(), InstallationsResponseJSON::fromJSON, "/user/installations");
      if (body.installations() == null || body.installations().isEmpty()) {
        break;
      }

      all.addAll(body.installations());
      if (all.size() >= body.totalCount()) {
        break;
      }
    }

    return all;
  }

  private List<GitHubRepository> repositories(String accessToken, long installationId) {
    var all = new ArrayList<GitHubRepository>();
    for (var page = 1; page <= MAX_PAGES; page++) {
      var response = send(request(accessToken, "/user/installations/" + installationId + "/repositories?per_page="
          + PAGE_SIZE + "&page=" + page).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "installation [" + installationId + "]");
      require(response, "listing the repositories of", "installation [" + installationId + "]");

      var body = parse(response.body(), RepositoriesResponseJSON::fromJSON, "/user/installations/{id}/repositories");
      if (body.repositories() == null || body.repositories().isEmpty()) {
        break;
      }

      all.addAll(body.repositories());
      if (all.size() >= body.totalCount()) {
        break;
      }
    }

    return all;
  }
}
