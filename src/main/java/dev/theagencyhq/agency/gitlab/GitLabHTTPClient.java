/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.gitlab;

import module dev.theagencyhq.agency;
import module java.base;
import module java.net.http;

import dev.theagencyhq.agency.model.gitlab.internal.*;
import dev.theagencyhq.agency.model.internal.*;

import java.lang.System.Logger.*;

/**
 * The real {@link GitLabClient}, over {@code java.net.http}: two OAuth calls against the GitLab instance and the
 * reads against its {@code /api/v4}, all made with the token the operator's authorization produced.
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
public class GitLabHTTPClient implements GitLabClient {
  public static final String API_PATH = "/api/v4";
  /**
   * How many pages of projects to walk before giving up. GitLab pages at 100, so this covers an account that is a
   * member of a thousand projects and still terminates if a {@code Link} header ever lies.
   */
  public static final int MAX_PAGES = 10;
  /**
   * How many pages of tree entries to walk before declaring the repository too large, which is GitLab's equivalent
   * of GitHub truncating a tree: ten thousand entries is two orders of magnitude more than a tree of prose and
   * configuration has, and a build must fail rather than publish files that quietly reverted to the default mode.
   */
  public static final int MAX_TREE_PAGES = 100;
  public static final int PAGE_SIZE = 100;
  public static final String SCOPE = "read_api";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final System.Logger logger = System.getLogger(GitLabHTTPClient.class.getName());
  private final String baseURL;
  private final String clientId;
  private final String clientSecret;
  private final HttpClient httpClient;

  /**
   * @param baseURL      The GitLab instance's origin, with no trailing slash.
   * @param clientId     The OAuth application's id.
   * @param clientSecret The OAuth application's secret.
   */
  public GitLabHTTPClient(String baseURL, String clientId, String clientSecret) {
    this.baseURL = baseURL.endsWith("/") ? baseURL.substring(0, baseURL.length() - 1) : baseURL;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    // NEVER, as the GitHub client does: an archive served from object storage arrives as a redirect to a signed
    // URL, which is followed by hand below without the Authorization header, so the token is never sent to a host
    // that did not ask for it.
    this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
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

  // Whole values, separators included: GitLab addresses a project and a file by their URL-encoded paths, so
  // `group/project` travels as `group%2Fproject` and `rules/a.md` as `rules%2Fa.md`, and both are exactly what
  // URLEncoder produces.
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String form(String... nameValues) {
    var builder = new StringBuilder();
    for (var i = 0; i + 1 < nameValues.length; i += 2) {
      if (!builder.isEmpty()) {
        builder.append('&');
      }
      builder.append(encode(nameValues[i])).append('=').append(encode(nameValues[i + 1]));
    }
    return builder.toString();
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

  // 401 and only 401. GitLab answers 403 for a token whose scope or role does not reach a project and 404 for one it
  // cannot see at all -- and 404 deliberately, so a private project cannot be probed for existence -- which is a
  // different problem with a different fix, and is reported as an absence rather than as a rejected credential.
  private static void unauthorized(int status, String detail) {
    if (status == 401) {
      throw new RepositoryUnauthorizedException("GitLab rejected the access token for [" + detail + "]");
    }
  }

  // The body wrapped as one object, because GitLab's list endpoints return a bare array and the codec parses
  // documents whose root is an object. The body is JSON, so the wrapping is JSON.
  private static String wrap(String array) {
    return "{\"items\":" + array + "}";
  }

  @Override
  public RepositoryContents contents(String accessToken, String fullName, String commit) {
    // The tree first, and against the same commit SHA the archive is fetched with, so the two halves of the
    // download describe one state of the repository even if somebody pushes between the two requests.
    var tree = tree(accessToken, fullName, commit);
    var zip = archive(accessToken, fullName, commit);
    return new RepositoryContents(commit, Archives.unzip(zip, "GitLab"), tree);
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
    unauthorized(response.statusCode(), fullName + "@" + ref);
    if (response.statusCode() == 403 || response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitLab returned HTTP [" + response.statusCode() + "] resolving ["
          + fullName + "@" + ref + "]: [" + response.body() + "]");
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
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitLab returned HTTP [" + response.statusCode() + "] reading the current user: ["
          + response.body() + "]");
    }

    return parse(response.body(), GitLabUserJSON::fromJSON, "/user").username();
  }

  @Override
  public byte[] readFile(String accessToken, String fullName, String ref, String path) {
    var response = send(request(accessToken, projectPath(fullName) + "/repository/files/" + encode(path) + "/raw?ref="
        + encode(ref)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    unauthorized(response.statusCode(), fullName + "@" + ref);
    if (response.statusCode() == 403 || response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitLab returned HTTP [" + response.statusCode() + "] reading [" + path + "] in ["
          + fullName + "@" + ref + "]");
    }

    return response.body();
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
    var url = baseURL + API_PATH + "/projects?membership=true&simple=true&order_by=path&sort=asc&per_page=" + PAGE_SIZE;
    for (var page = 1; url != null; page++) {
      if (page > MAX_PAGES) {
        break;
      }

      var response = send(request(accessToken, URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "the current user");
      if (response.statusCode() / 100 != 2) {
        throw new RepositoryException("GitLab returned HTTP [" + response.statusCode() + "] listing projects: ["
            + response.body() + "]");
      }

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

  private byte[] archive(String accessToken, String fullName, String commit) {
    var response = send(request(accessToken, projectPath(fullName) + "/repository/archive.zip?sha=" + encode(commit))
        .GET().build(), HttpResponse.BodyHandlers.ofByteArray());

    // GitLab serves the archive directly, but an instance backed by object storage may redirect to a signed URL.
    // Followed by hand and with no Authorization header, because the JDK's own redirect handling would carry the
    // header to a different host.
    if (response.statusCode() == 301 || response.statusCode() == 302 || response.statusCode() == 307) {
      var location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new RepositoryException("GitLab redirected the archive of [" + fullName + "] with no [Location] header");
      }

      response = send(HttpRequest.newBuilder(URI.create(location)).timeout(TIMEOUT).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }

    unauthorized(response.statusCode(), fullName + "@" + commit);
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitLab returned HTTP [" + response.statusCode() + "] downloading the archive of ["
          + fullName + "@" + commit + "]");
    }
    if (response.body().length > Archives.MAX_CONTENT_BYTES) {
      throw new RepositoryException("The archive of [" + fullName + "@" + commit + "] is ["
          + response.body().length + "] bytes, which is larger than a Brief source repository may be");
    }

    return response.body();
  }

  private <T> T parse(String body, Function<String, T> parser, String operation) {
    try {
      return parser.apply(body);
    } catch (RuntimeException e) {
      throw new RepositoryException("Unable to parse the GitLab response for [" + operation + "]", e);
    }
  }

  private String projectPath(String fullName) {
    return "/projects/" + encode(fullName);
  }

  private HttpRequest.Builder request(String accessToken, String path) {
    return request(accessToken, URI.create(baseURL + API_PATH + path));
  }

  private HttpRequest.Builder request(String accessToken, URI uri) {
    return HttpRequest.newBuilder(uri)
                      .timeout(TIMEOUT)
                      .header("Accept", "application/json")
                      .header("Authorization", "Bearer " + accessToken);
  }

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    try {
      return httpClient.send(request, handler);
    } catch (IOException e) {
      throw new RepositoryException("Unable to call GitLab [" + request.method() + " " + request.uri() + "]", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RepositoryException("Interrupted calling GitLab [" + request.method() + " " + request.uri() + "]", e);
    }
  }

  // Both OAuth grants have the same shape. Unlike GitHub, GitLab answers a rejected grant -- a used code, a revoked
  // or already-rotated refresh token -- with a 400 or 401 and an error body, which is the "no credential" outcome
  // the caller expects as null; anything else is a failure to complete the call at all.
  private OAuthTokens token(String body) {
    var request = HttpRequest.newBuilder(URI.create(baseURL + "/oauth/token"))
                             .timeout(TIMEOUT)
                             .header("Accept", "application/json")
                             .header("Content-Type", "application/x-www-form-urlencoded")
                             .POST(HttpRequest.BodyPublishers.ofString(body))
                             .build();
    var response = send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 400 || response.statusCode() == 401) {
      // The body names the reason (an already-used code, a revoked refresh token) and carries no credential.
      logger.log(Level.WARNING, "GitLab rejected the grant with HTTP [{0}]: [{1}]", response.statusCode(), response.body());
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitLab returned HTTP [" + response.statusCode() + "] from the token endpoint");
    }

    // Instant.now() is read after the response arrives rather than before the request, so a slow round trip
    // shortens the recorded lifetime instead of overstating it.
    return OAuthTokens.from(parse(response.body(), OAuthTokenResponseJSON::fromJSON, "the token endpoint"), Instant.now());
  }

  /**
   * The recursive tree, walked page by page with keyset pagination, which is the form GitLab recommends for trees
   * and the only one that stays fast on a large one.
   */
  private Map<String, String> tree(String accessToken, String fullName, String commit) {
    var modes = new HashMap<String, String>();
    var url = baseURL + API_PATH + projectPath(fullName) + "/repository/tree?recursive=true&pagination=keyset&per_page="
        + PAGE_SIZE + "&ref=" + encode(commit);
    for (var page = 1; url != null; page++) {
      if (page > MAX_TREE_PAGES) {
        throw new RepositoryException("The tree of [" + fullName + "@" + commit + "] has more than ["
            + MAX_TREE_PAGES * PAGE_SIZE + "] entries, which is larger than a Brief source repository may be");
      }

      var response = send(request(accessToken, URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), fullName + "@" + commit);
      if (response.statusCode() / 100 != 2) {
        throw new RepositoryException("GitLab returned HTTP [" + response.statusCode() + "] reading the tree of ["
            + fullName + "@" + commit + "]: [" + response.body() + "]");
      }

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
