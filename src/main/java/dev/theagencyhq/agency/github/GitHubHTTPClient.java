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
 * The real {@link GitHubClient}, over {@code java.net.http}.
 *
 * <p>Every API call carries the {@code X-GitHub-Api-Version} header pinning the REST contract, so a future default
 * version on GitHub's side cannot change a response shape underneath a running Agency.
 */
public class GitHubHTTPClient implements GitHubClient {
  public static final String API_URL = "https://api.github.com";
  public static final String API_VERSION = "2022-11-28";
  public static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
  /**
   * The ceiling on an unpacked repository, and the reason a Brief source may not be an ordinary application repository.
   * A ZIP is decompressed into memory here, so without a limit a repository with a large asset — or a deliberately
   * crafted one — is an out-of-memory failure that takes the whole Agency down rather than one Organization's build.
   * Generous by two orders of magnitude for a tree of prose and configuration.
   */
  public static final long MAX_CONTENT_BYTES = 64L * 1024 * 1024;
  /**
   * How many pages of installations or repositories to walk before giving up. GitHub pages at 100, so this covers an
   * operator with a thousand repositories and still terminates if a {@code Link} header ever lies.
   */
  public static final int MAX_PAGES = 10;
  public static final int PAGE_SIZE = 100;
  public static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
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
        + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        + "&redirect_uri=" + URLEncoder.encode(redirectURI, StandardCharsets.UTF_8)
        + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
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

  // 401 and only 401. GitHub answers 403 or 404 for a repository a perfectly good token simply cannot see -- and
  // 404 deliberately, so a private repository cannot be probed for existence -- which is a different problem with a
  // different fix, and is reported as an absence rather than as a rejected credential.
  private static void unauthorized(int status, String detail) {
    if (status == 401) {
      throw new GitHubUnauthorizedException("GitHub rejected the access token for [" + detail + "]");
    }
  }

  @Override
  public RepositoryContents contents(String accessToken, String owner, String repository, String commit) {
    // The tree first, and against the same commit SHA the zipball is fetched with, so the two halves of the
    // download describe one state of the repository even if somebody pushes between the two requests.
    var tree = tree(accessToken, owner, repository, commit);
    var zip = zipball(accessToken, owner, repository, commit);
    return new RepositoryContents(commit, unzip(zip), tree);
  }

  @Override
  public GitHubTokens exchangeCode(String code, String redirectURI) {
    return token(form("client_id", clientId, "client_secret", clientSecret, "code", code, "redirect_uri", redirectURI));
  }

  @Override
  public String head(String accessToken, String owner, String repository, String ref) {
    // The `sha` media type makes the whole response body the commit SHA, rather than the full commit object with
    // its author, committer, message, tree, parents and stats -- none of which the poller reads.
    var response = send(request(accessToken, "/repos/" + encode(owner) + "/" + encode(repository) + "/commits/"
        + encode(ref)).header("Accept", "application/vnd.github.sha").GET().build(), HttpResponse.BodyHandlers.ofString());
    unauthorized(response.statusCode(), owner + "/" + repository + "@" + ref);
    if (response.statusCode() == 403 || response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] resolving ["
          + owner + "/" + repository + "@" + ref + "]: [" + response.body() + "]");
    }

    var sha = response.body().trim();
    return sha.isEmpty() ? null : sha;
  }

  @Override
  public List<GitHubInstallation> installations(String accessToken) {
    var all = new ArrayList<GitHubInstallation>();
    for (var page = 1; page <= MAX_PAGES; page++) {
      var response = send(request(accessToken, "/user/installations?per_page=" + PAGE_SIZE + "&page=" + page).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "the current user");
      if (response.statusCode() / 100 != 2) {
        throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] listing installations: ["
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

  @Override
  public byte[] readFile(String accessToken, String owner, String repository, String ref, String path) {
    // The `raw` media type returns the file's bytes rather than a JSON object with base64 in it, which keeps this
    // honest for a binary file and saves a decode for a text one.
    var response = send(request(accessToken, "/repos/" + encode(owner) + "/" + encode(repository) + "/contents/"
            + encodePath(path) + "?ref=" + encode(ref)).header("Accept", "application/vnd.github.raw").GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    unauthorized(response.statusCode(), owner + "/" + repository + "@" + ref);
    if (response.statusCode() == 403 || response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] reading [" + path + "] in ["
          + owner + "/" + repository + "@" + ref + "]");
    }

    return response.body();
  }

  @Override
  public GitHubTokens refresh(String refreshToken) {
    return token(form("client_id", clientId, "client_secret", clientSecret, "grant_type", "refresh_token",
        "refresh_token", refreshToken));
  }

  @Override
  public List<GitHubRepository> repositories(String accessToken, long installationId) {
    var all = new ArrayList<GitHubRepository>();
    for (var page = 1; page <= MAX_PAGES; page++) {
      var response = send(request(accessToken, "/user/installations/" + installationId + "/repositories?per_page="
          + PAGE_SIZE + "&page=" + page).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "installation [" + installationId + "]");
      if (response.statusCode() / 100 != 2) {
        throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] listing the repositories of "
            + "installation [" + installationId + "]: [" + response.body() + "]");
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

  @Override
  public GitHubUser user(String accessToken) {
    var response = send(request(accessToken, "/user").GET().build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] reading the current user: ["
          + response.body() + "]");
    }

    var user = parse(response.body(), GitHubUserJSON::fromJSON, "/user");
    return user.login() == null ? null : user;
  }

  private <T> T parse(String body, Function<String, T> parser, String operation) {
    try {
      return parser.apply(body);
    } catch (RuntimeException e) {
      throw new GitHubException("Unable to parse the GitHub response for [" + operation + "]", e);
    }
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
      throw new GitHubException("Unable to call GitHub [" + request.method() + " " + request.uri() + "]", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubException("Interrupted calling GitHub [" + request.method() + " " + request.uri() + "]", e);
    }
  }

  // Both OAuth grants have the same shape: a form POST that answers 200 whether it succeeded or failed, and says
  // which in the body. Nothing here may branch on the status code.
  private GitHubTokens token(String body) {
    var request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                             .timeout(TIMEOUT)
                             .header("Accept", "application/json")
                             .header("Content-Type", "application/x-www-form-urlencoded")
                             .POST(HttpRequest.BodyPublishers.ofString(body))
                             .build();
    var response = send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] from the token endpoint");
    }

    // Instant.now() is read after the response arrives rather than before the request, so a slow round trip
    // shortens the recorded lifetime instead of overstating it.
    return GitHubTokens.from(parse(response.body(), TokenResponseJSON::fromJSON, "the token endpoint"), Instant.now());
  }

  private Map<String, String> tree(String accessToken, String owner, String repository, String commit) {
    var response = send(request(accessToken, "/repos/" + encode(owner) + "/" + encode(repository) + "/git/trees/"
        + encode(commit) + "?recursive=1").GET().build(), HttpResponse.BodyHandlers.ofString());
    unauthorized(response.statusCode(), owner + "/" + repository + "@" + commit);
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] reading the tree of ["
          + owner + "/" + repository + "@" + commit + "]: [" + response.body() + "]");
    }

    var body = parse(response.body(), TreeResponseJSON::fromJSON, "/repos/{owner}/{repo}/git/trees/{sha}");
    if (body.truncated()) {
      throw new GitHubException("The tree of [" + owner + "/" + repository + "@" + commit + "] is too large for "
          + "GitHub to return in full, so the file modes cannot be read. A Brief source repository must be small "
          + "enough to list in one request");
    }

    var modes = new HashMap<String, String>();
    for (var entry : body.tree() == null ? List.<TreeEntry>of() : body.tree()) {
      if (entry.path() != null && entry.mode() != null) {
        modes.put(entry.path(), entry.mode());
      }
    }

    return modes;
  }

  // GitHub names every entry `<owner>-<repo>-<abbreviated sha>/...`, so the first segment is stripped. Directory
  // entries and anything that escapes the archive root are dropped rather than rejected: the ZIP is GitHub's own
  // output, so a traversal entry means something upstream is wrong and the honest response is to not have the file.
  private Map<String, byte[]> unzip(byte[] archive) {
    var files = new HashMap<String, byte[]>();
    var total = 0L;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.isDirectory()) {
          continue;
        }

        var name = entry.getName();
        var slash = name.indexOf('/');
        if (slash < 0) {
          continue;
        }

        var path = name.substring(slash + 1);
        if (path.isEmpty() || path.startsWith("/") || path.equals("..") || path.startsWith("../")
            || path.contains("/../") || path.endsWith("/..")) {
          continue;
        }

        var bytes = zip.readAllBytes();
        total += bytes.length;
        if (total > MAX_CONTENT_BYTES) {
          throw new GitHubException("The repository archive expands to more than [" + MAX_CONTENT_BYTES
              + "] bytes, which is larger than a Brief source repository may be");
        }

        files.put(path, bytes);
      }
    } catch (IOException e) {
      throw new GitHubException("Unable to read the repository archive GitHub returned", e);
    }

    return files;
  }

  private byte[] zipball(String accessToken, String owner, String repository, String commit) {
    var response = send(request(accessToken, "/repos/" + encode(owner) + "/" + encode(repository) + "/zipball/"
        + encode(commit)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

    // GitHub answers this one with a 302 to a signed codeload URL. Followed by hand and with no Authorization
    // header, because the JDK's own redirect handling would carry the header to a different host.
    if (response.statusCode() == 301 || response.statusCode() == 302 || response.statusCode() == 307) {
      var location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new GitHubException("GitHub redirected the archive of [" + owner + "/" + repository + "] with no "
            + "[Location] header");
      }

      response = send(HttpRequest.newBuilder(URI.create(location)).timeout(TIMEOUT).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }

    unauthorized(response.statusCode(), owner + "/" + repository + "@" + commit);
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException("GitHub returned HTTP [" + response.statusCode() + "] downloading the archive of ["
          + owner + "/" + repository + "@" + commit + "]");
    }
    if (response.body().length > MAX_CONTENT_BYTES) {
      throw new GitHubException("The archive of [" + owner + "/" + repository + "@" + commit + "] is ["
          + response.body().length + "] bytes, which is larger than a Brief source repository may be");
    }

    return response.body();
  }
}
