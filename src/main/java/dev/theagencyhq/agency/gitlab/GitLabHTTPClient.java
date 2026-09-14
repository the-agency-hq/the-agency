/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.gitlab;

import module dev.theagencyhq.agency;
import module java.base;
import module java.net.http;

import dev.theagencyhq.agency.model.gitlab.internal.*;

/**
 * The real {@link GitLabClient}: two OAuth calls against the GitLab instance and the reads against its
 * {@code /api/v4}, all made with the token the operator's authorization produced.
 *
 * <p>Everything is relative to one base URL, {@code https://gitlab.com} unless {@code gitlab.baseURL} says otherwise,
 * because GitLab is routinely self-managed and a self-managed instance is the same API at a different origin. The
 * OAuth application is registered on that instance, with the {@code read_api} scope, which covers every read this
 * client makes — the account, the projects, a commit, a file, the tree, the archive.
 *
 * <p>GitLab's list endpoints return bare JSON arrays and page through {@code Link} headers, where GitHub wraps its
 * lists in an object with a count. Both are handled here and in the two {@code items} records the codec parses the
 * arrays into, so nothing above this class sees the difference.
 */
public class GitLabHTTPClient extends HTTPRepositoryClient implements GitLabClient {
  public static final String API_PATH = "/api/v4";
  public static final String SCOPE = "read_api";
  private final String clientId;
  private final String clientSecret;

  /**
   * @param baseURL      The GitLab instance's origin, with or without a trailing slash.
   * @param clientId     The OAuth application's id.
   * @param clientSecret The OAuth application's secret.
   */
  public GitLabHTTPClient(String baseURL, String clientId, String clientSecret) {
    super("GitLab", strip(baseURL) + API_PATH, strip(baseURL) + "/oauth/token");
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  /**
   * Builds the URL that starts the GitLab authorization.
   *
   * @param baseURL     The GitLab instance's origin.
   * @param clientId    The OAuth application's id.
   * @param redirectURI Where GitLab returns the browser. Must match one of the application's configured redirect
   *                    URIs exactly.
   * @param state       The opaque value the callback checks against its cookie.
   * @return The absolute URL to redirect the browser to.
   */
  public static String authorizeURL(String baseURL, String clientId, String redirectURI, String state) {
    return baseURL + "/oauth/authorize"
        + "?client_id=" + encode(clientId)
        + "&redirect_uri=" + encode(redirectURI)
        + "&response_type=code"
        + "&scope=" + encode(SCOPE)
        + "&state=" + encode(state);
  }

  /**
   * @return The URL the {@code Link} header names as {@code rel="next"}, or {@code null} on the last page. GitLab
   *     sends the header for offset and keyset pagination alike, so one reader walks both.
   */
  private static String next(HttpResponse<?> response) {
    var link = response.headers().firstValue("Link").orElse(null);
    if (link == null) {
      return null;
    }

    for (var part : link.split(",")) {
      var pieces = part.split(";");
      if (pieces.length >= 2 && pieces[1].trim().equals("rel=\"next\"")) {
        var url = pieces[0].trim();
        if (url.startsWith("<") && url.endsWith(">")) {
          return url.substring(1, url.length() - 1);
        }
      }
    }

    return null;
  }

  // GitLab addresses a project by its URL-encoded path, so `group/project` travels as `group%2Fproject`, which is
  // exactly what encode produces.
  private static String projectPath(String fullName) {
    return "/projects/" + encode(fullName);
  }

  private static String strip(String baseURL) {
    return baseURL.endsWith("/") ? baseURL.substring(0, baseURL.length() - 1) : baseURL;
  }

  // The body wrapped as one object, because GitLab's list endpoints return a bare array and the codec parses
  // documents whose root is an object. The body is JSON, so the wrapping is JSON.
  private static String wrap(String array) {
    return "{\"items\":" + array + "}";
  }

  @Override
  public OAuthTokens exchangeCode(String code, String redirectURI) {
    return token(form("client_id", clientId, "client_secret", clientSecret, "code", code,
        "grant_type", "authorization_code", "redirect_uri", redirectURI));
  }

  @Override
  public String head(String accessToken, String fullName, String ref) {
    var response = send(request(accessToken, projectPath(fullName) + "/repository/commits/" + encode(ref)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (absent(response, "resolving", fullName + "@" + ref)) {
      return null;
    }

    var id = parse(response.body(), GitLabCommitJSON::fromJSON, "/projects/{id}/repository/commits/{ref}").id();
    return id == null || id.isEmpty() ? null : id;
  }

  @Override
  public String login(String accessToken) {
    var response = send(request(accessToken, "/user").GET().build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401) {
      return null;
    }

    require(response, "reading", "the current user");
    return parse(response.body(), GitLabUserJSON::fromJSON, "/user").username();
  }

  @Override
  public byte[] readFile(String accessToken, String fullName, String ref, String path) {
    // The file's path is one URL-encoded value, as the project's is: `rules/a.md` travels as `rules%2Fa.md`.
    var response = send(request(accessToken, projectPath(fullName) + "/repository/files/" + encode(path) + "/raw?ref="
        + encode(ref)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    return absent(response, "reading [" + path + "] in", fullName + "@" + ref) ? null : response.body();
  }

  @Override
  public OAuthTokens refresh(String refreshToken) {
    return token(form("client_id", clientId, "client_secret", clientSecret, "grant_type", "refresh_token",
        "refresh_token", refreshToken));
  }

  /**
   * Every project the account is a member of, directly or through a group. One level, unlike GitHub's
   * installations: a GitLab OAuth application reads whatever the authorizing account can, so membership is the
   * whole of the grant.
   */
  @Override
  public List<RepositorySummary> repositories(String accessToken) {
    var all = new ArrayList<RepositorySummary>();
    var url = apiURL + "/projects?membership=true&simple=true&order_by=path&sort=asc&per_page=" + PAGE_SIZE;
    for (var page = 1; url != null; page++) {
      if (page > MAX_PAGES) {
        break;
      }

      var response = send(request(accessToken, URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "the current user");
      require(response, "listing the projects of", "the current user");

      var body = parse(wrap(response.body()), GitLabProjectsJSON::fromJSON, "/projects");
      for (var project : body.items() == null ? List.<GitLabProject>of() : body.items()) {
        if (project.pathWithNamespace() != null) {
          all.add(new RepositorySummary(project.pathWithNamespace(), project.defaultBranch()));
        }
      }

      url = next(response);
    }

    return all;
  }

  /**
   * The archive, which GitLab serves directly — though an instance backed by object storage may redirect to a signed
   * URL, which {@link #download} follows.
   */
  @Override
  protected byte[] archive(String accessToken, String fullName, String commit) {
    return download(request(accessToken, projectPath(fullName) + "/repository/archive.zip?sha=" + encode(commit))
        .GET().build(), fullName + "@" + commit);
  }

  /**
   * The recursive tree, walked page by page with keyset pagination, which is the form GitLab recommends for trees
   * and the only one that stays fast on a large one. Paginated rather than truncated, as GitHub's is: a large tree is
   * many pages, and past {@link #MAX_TREE_PAGES} of them the build fails rather than publishing files whose modes it
   * could not read.
   */
  @Override
  protected Map<String, String> tree(String accessToken, String fullName, String commit) {
    var modes = new HashMap<String, String>();
    var url = apiURL + projectPath(fullName) + "/repository/tree?recursive=true&pagination=keyset&per_page="
        + PAGE_SIZE + "&ref=" + encode(commit);
    for (var page = 1; url != null; page++) {
      if (page > MAX_TREE_PAGES) {
        throw new RepositoryException("The tree of [" + fullName + "@" + commit + "] has more than ["
            + MAX_TREE_PAGES * PAGE_SIZE + "] entries, which is larger than a Brief source repository may be");
      }

      var response = send(request(accessToken, URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), fullName + "@" + commit);
      require(response, "reading the tree of", fullName + "@" + commit);

      var body = parse(wrap(response.body()), GitLabTreeJSON::fromJSON, "/projects/{id}/repository/tree");
      for (var entry : body.items() == null ? List.<TreeEntry>of() : body.items()) {
        if (entry.path() != null && entry.mode() != null) {
          modes.put(entry.path(), entry.mode());
        }
      }

      url = next(response);
    }

    return modes;
  }
}
