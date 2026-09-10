/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.github;

import module dev.theagencyhq.agency;
import module java.base;
import module java.net.http;

import dev.theagencyhq.agency.model.github.internal.*;
import dev.theagencyhq.agency.model.internal.*;

import java.lang.System.Logger.*;

/**
 * The real {@link GitHubClient}, over {@code java.net.http}: two OAuth calls against {@code github.com}, and the
 * reads against {@code api.github.com} made with a user-to-server token.
 *
 * <p>Every API call carries the {@code X-GitHub-Api-Version} header pinning the REST contract, so a future default
 * version on GitHub's side cannot change a response shape underneath a running Agency.
 */
public class GitHubHTTPClient implements GitHubClient {
  public static final String API_URL = "https://api.github.com";
  public static final String API_VERSION = "2022-11-28";
  public static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
  /**
   * How many pages of installations or repositories to walk before giving up. GitHub pages at 100, so this covers an
   * operator with a thousand repositories and still terminates if a {@code Link} header ever lies.
   */
  public static final int MAX_PAGES = 10;
  public static final int PAGE_SIZE = 100;
  public static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final System.Logger logger = System.getLogger(GitHubHTTPClient.class.getName());
  private final String clientId;
  private final String clientSecret;
  private final HttpClient httpClient;

  public GitHubHTTPClient(String clientId, String clientSecret) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    // NEVER, which is also the JDK's default: the zipball's 302 points at codeload.github.com, and this client
    // re-issues that one request by hand precisely so the Authorization header is not carried across to another
    // host. The redirect target is already a signed URL, so it needs no credential -- and sending one to a host
    // that did not ask for it is how a token leaks.
    this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
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

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8);
  }

  // Each segment separately, so the separators survive: URLEncoder would turn `rules/a.md` into `rules%2Fa.md`,
  // which GitHub reads as a single file named with a slash in it and answers 404 for.
  private static String encodePath(String path) {
    return Arrays.stream(path.split("/", -1)).map(GitHubHTTPClient::encode).collect(Collectors.joining("/"));
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

  // The API addresses a repository as two path segments where everything above this client names it as one
  // string. A GitHub owner cannot contain a slash, so the first one is the separator.
  private static String repositoryPath(String fullName) {
    var slash = fullName.indexOf('/');
    return slash < 0 ? "/repos/" + encode(fullName) + "/"
        : "/repos/" + encode(fullName.substring(0, slash)) + "/" + encode(fullName.substring(slash + 1));
  }

  // 401 and only 401. GitHub answers 403 or 404 for a repository a perfectly good token simply cannot see -- and
  // 404 deliberately, so a private repository cannot be probed for existence -- which is a different problem with a
  // different fix, and is reported as an absence rather than as a rejected credential.
  private static void unauthorized(int status, String detail) {
    if (status == 401) {
      throw new RepositoryUnauthorizedException("GitHub rejected the access token for [" + detail + "]");
    }
  }

  @Override
  public RepositoryContents contents(String accessToken, String fullName, String commit) {
    // The tree first, and against the same commit SHA the zipball is fetched with, so the two halves of the
    // download describe one state of the repository even if somebody pushes between the two requests.
    var tree = tree(accessToken, fullName, commit);
    var zip = zipball(accessToken, fullName, commit);
    return new RepositoryContents(commit, Archives.unzip(zip, "GitHub"), tree);
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
        .header("Accept", "application/vnd.github.sha").GET().build(), HttpResponse.BodyHandlers.ofString());
    unauthorized(response.statusCode(), fullName + "@" + ref);
    if (response.statusCode() == 403 || response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] resolving ["
          + fullName + "@" + ref + "]: [" + response.body() + "]");
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
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] reading the current user: ["
          + response.body() + "]");
    }

    return parse(response.body(), GitHubUserJSON::fromJSON, "/user").login();
  }

  @Override
  public byte[] readFile(String accessToken, String fullName, String ref, String path) {
    // The `raw` media type returns the file's bytes rather than a JSON object with base64 in it, which keeps this
    // honest for a binary file and saves a decode for a text one.
    var response = send(request(accessToken, repositoryPath(fullName) + "/contents/" + encodePath(path) + "?ref="
            + encode(ref)).header("Accept", "application/vnd.github.raw").GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    unauthorized(response.statusCode(), fullName + "@" + ref);
    if (response.statusCode() == 403 || response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] reading [" + path + "] in ["
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

  private List<GitHubInstallation> installations(String accessToken) {
    var all = new ArrayList<GitHubInstallation>();
    for (var page = 1; page <= MAX_PAGES; page++) {
      var response = send(request(accessToken, "/user/installations?per_page=" + PAGE_SIZE + "&page=" + page).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "the current user");
      if (response.statusCode() / 100 != 2) {
        throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] listing installations: ["
            + response.body() + "]");
      }

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

  private <T> T parse(String body, Function<String, T> parser, String operation) {
    try {
      return parser.apply(body);
    } catch (RuntimeException e) {
      throw new RepositoryException("Unable to parse the GitHub response for [" + operation + "]", e);
    }
  }

  private List<GitHubRepository> repositories(String accessToken, long installationId) {
    var all = new ArrayList<GitHubRepository>();
    for (var page = 1; page <= MAX_PAGES; page++) {
      var response = send(request(accessToken, "/user/installations/" + installationId + "/repositories?per_page="
          + PAGE_SIZE + "&page=" + page).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "installation [" + installationId + "]");
      if (response.statusCode() / 100 != 2) {
        throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] listing the repositories "
            + "of installation [" + installationId + "]: [" + response.body() + "]");
      }

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

  private HttpRequest.Builder request(String accessToken, String path) {
    return HttpRequest.newBuilder(URI.create(API_URL + path))
                      .timeout(TIMEOUT)
                      .header("Accept", "application/vnd.github+json")
                      .header("Authorization", "Bearer " + accessToken)
                      .header("X-GitHub-Api-Version", API_VERSION);
  }

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    try {
      return httpClient.send(request, handler);
    } catch (IOException e) {
      throw new RepositoryException("Unable to call GitHub [" + request.method() + " " + request.uri() + "]", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RepositoryException("Interrupted calling GitHub [" + request.method() + " " + request.uri() + "]", e);
    }
  }

  // Both OAuth grants have the same shape: a form POST that answers 200 whether it succeeded or failed, and says
  // which in the body. Nothing here may branch on the status code.
  private OAuthTokens token(String body) {
    var request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                             .timeout(TIMEOUT)
                             .header("Accept", "application/json")
                             .header("Content-Type", "application/x-www-form-urlencoded")
                             .POST(HttpRequest.BodyPublishers.ofString(body))
                             .build();
    var response = send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] from the token endpoint");
    }

    var parsed = parse(response.body(), OAuthTokenResponseJSON::fromJSON, "the token endpoint");
    if (parsed.error() != null) {
      // GitHub answers 200 for a rejected grant (an already-used code, a revoked refresh token) and names the reason.
      logger.log(Level.WARNING, "GitHub rejected the grant: [{0}] [{1}]", parsed.error(), parsed.errorDescription());
    }

    // Instant.now() is read after the response arrives rather than before the request, so a slow round trip
    // shortens the recorded lifetime instead of overstating it.
    return OAuthTokens.from(parsed, Instant.now());
  }

  private Map<String, String> tree(String accessToken, String fullName, String commit) {
    var response = send(request(accessToken, repositoryPath(fullName) + "/git/trees/" + encode(commit)
        + "?recursive=1").GET().build(), HttpResponse.BodyHandlers.ofString());
    unauthorized(response.statusCode(), fullName + "@" + commit);
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] reading the tree of ["
          + fullName + "@" + commit + "]: [" + response.body() + "]");
    }

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

  private byte[] zipball(String accessToken, String fullName, String commit) {
    var response = send(request(accessToken, repositoryPath(fullName) + "/zipball/" + encode(commit)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());

    // GitHub answers this one with a 302 to a signed codeload URL. Followed by hand and with no Authorization
    // header, because the JDK's own redirect handling would carry the header to a different host.
    if (response.statusCode() == 301 || response.statusCode() == 302 || response.statusCode() == 307) {
      var location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new RepositoryException("GitHub redirected the archive of [" + fullName + "] with no [Location] header");
      }

      response = send(HttpRequest.newBuilder(URI.create(location)).timeout(TIMEOUT).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }

    unauthorized(response.statusCode(), fullName + "@" + commit);
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryException("GitHub returned HTTP [" + response.statusCode() + "] downloading the archive of ["
          + fullName + "@" + commit + "]");
    }
    if (response.body().length > Archives.MAX_CONTENT_BYTES) {
      throw new RepositoryException("The archive of [" + fullName + "@" + commit + "] is ["
          + response.body().length + "] bytes, which is larger than a Brief source repository may be");
    }

    return response.body();
  }
}
