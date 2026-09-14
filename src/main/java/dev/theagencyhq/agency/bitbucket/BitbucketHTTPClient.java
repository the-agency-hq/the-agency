/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.bitbucket;

import module dev.theagencyhq.agency;
import module java.base;
import module java.net.http;

import dev.theagencyhq.agency.model.bitbucket.internal.*;

/**
 * The real {@link BitbucketClient}: two OAuth calls against {@code bitbucket.org} and the reads against
 * {@code api.bitbucket.org/2.0}, all made with the token the operator's authorization produced.
 *
 * <p>Bitbucket Cloud only, so every URL is fixed: Bitbucket Data Center is a different product with a different API,
 * and is not a kind of source the Agency offers. The OAuth consumer is registered on a workspace with the
 * {@code account} and {@code repository} (read) permissions, which cover every read this client makes; Bitbucket
 * takes no scope on the authorize request, so none is sent, and it wants the consumer's credentials as HTTP Basic
 * authentication on the token endpoint rather than in the form.
 *
 * <p>Three things differ from the other hosts under the contract. Bitbucket's lists page through a {@code next} URL
 * in the body rather than a {@code Link} header. The repository listing is two levels — the account's workspaces,
 * then each workspace's repositories — because Bitbucket removed the cross-workspace listing on April 14, 2026
 * (changelog CHANGE-2770). And Bitbucket has no Git tree endpoint: the modes come from the {@code src} directory
 * listing walked recursively, whose file entries carry {@code attributes} in place of a mode, which
 * {@code BitbucketTreeEntry} translates back into one. The archive itself is served by the website rather than the
 * API, and takes the same bearer token.
 */
public class BitbucketHTTPClient extends HTTPRepositoryClient implements BitbucketClient {
  public static final String API_URL = "https://api.bitbucket.org/2.0";
  public static final String AUTHORIZE_URL = BitbucketConfig.WEB_URL + "/site/oauth2/authorize";
  /**
   * How deep the {@code src} listing recurses. Bitbucket walks the tree breadth-first to this depth and no further,
   * so a file below it would be in the archive and absent from the listing — which {@link #contents} refuses to
   * publish, since the file's mode would quietly revert to the default. Generous by an order of magnitude for a tree
   * of prose and configuration.
   */
  public static final int MAX_DEPTH = 100;
  public static final String TOKEN_URL = BitbucketConfig.WEB_URL + "/site/oauth2/access_token";
  private final String consumerAuthorization;

  /**
   * @param clientId     The OAuth consumer's key.
   * @param clientSecret The OAuth consumer's secret.
   */
  public BitbucketHTTPClient(String clientId, String clientSecret) {
    super("Bitbucket", API_URL, TOKEN_URL);
    // Bitbucket authenticates the consumer on the token endpoint with HTTP Basic, key as the user and secret as the
    // password, rather than with client_id and client_secret in the form as GitHub and GitLab do.
    var credentials = (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8);
    this.consumerAuthorization = "Basic " + Base64.getEncoder().encodeToString(credentials);
  }

  /**
   * Builds the URL that starts the Bitbucket authorization. No {@code scope} parameter: Bitbucket's permissions are
   * declared on the consumer, and it does not take a scope on individual requests. The redirect URI is sent — it must
   * be the consumer's configured callback URL, which is exactly what it is.
   *
   * @param clientId    The OAuth consumer's key.
   * @param redirectURI Where Bitbucket returns the browser.
   * @param state       The opaque value the callback checks against its cookie.
   * @return The absolute URL to redirect the browser to.
   */
  public static String authorizeURL(String clientId, String redirectURI, String state) {
    return AUTHORIZE_URL
        + "?client_id=" + encode(clientId)
        + "&redirect_uri=" + encode(redirectURI)
        + "&response_type=code"
        + "&state=" + encode(state);
  }

  private static String repositoryPath(String fullName) {
    return "/repositories/" + repositorySegments(fullName);
  }

  /**
   * The contents with one check more than the other hosts need: Bitbucket lists to a depth rather than returning
   * the whole tree, so a file the archive holds and the listing does not is one whose mode would silently revert to
   * the default. A submodule is the other way round — listed, and absent from the archive — which is fine.
   */
  @Override
  public RepositoryContents contents(String accessToken, String fullName, String commit) {
    var contents = super.contents(accessToken, fullName, commit);
    for (var path : contents.files().keySet()) {
      if (!contents.modes().containsKey(path)) {
        throw new RepositoryException("The listing of [" + fullName + "@" + commit + "] has no entry for [" + path
            + "], so its file mode cannot be read. A Brief source repository must nest fewer than [" + MAX_DEPTH
            + "] directories deep");
      }
    }

    return contents;
  }

  @Override
  public OAuthTokens exchangeCode(String code, String redirectURI) {
    return token(form("grant_type", "authorization_code", "code", code, "redirect_uri", redirectURI));
  }

  @Override
  public String head(String accessToken, String fullName, String ref) {
    // The commits reachable from the ref, newest first, one to a page: the first is the head. Asked this way rather
    // than through the single-commit endpoint because this one is documented to take a ref name as well as a SHA.
    var response = send(request(accessToken, repositoryPath(fullName) + "/commits/" + encodePath(ref)
        + "?pagelen=1&fields=values.hash").GET().build(), HttpResponse.BodyHandlers.ofString());
    if (absent(response, "resolving", fullName + "@" + ref)) {
      return null;
    }

    var body = parse(response.body(), BitbucketCommitsJSON::fromJSON,
        "/repositories/{workspace}/{repo_slug}/commits/{revision}");
    if (body.values() == null || body.values().isEmpty()) {
      return null;
    }

    var hash = body.values().getFirst().hash();
    return hash == null || hash.isEmpty() ? null : hash;
  }

  @Override
  public String login(String accessToken) {
    var response = send(request(accessToken, "/user").GET().build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401) {
      return null;
    }

    require(response, "reading", "the current user");
    return parse(response.body(), BitbucketUserJSON::fromJSON, "/user").login();
  }

  @Override
  public byte[] readFile(String accessToken, String fullName, String ref, String path) {
    // The ref is resolved to its commit first, because `src/{ref}/{path}` cannot tell where a ref with a slash in
    // it -- `release/2.x` -- ends and the path begins, and answers 404. One extra request, made at registration.
    var commit = head(accessToken, fullName, ref);
    if (commit == null) {
      return null;
    }

    var response = send(request(accessToken, repositoryPath(fullName) + "/src/" + encode(commit) + "/" + encodePath(path))
        .setHeader("Accept", "*/*").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    return absent(response, "reading [" + path + "] in", fullName + "@" + ref) ? null : response.body();
  }

  @Override
  public OAuthTokens refresh(String refreshToken) {
    return token(form("grant_type", "refresh_token", "refresh_token", refreshToken));
  }

  /**
   * Every repository the account can read, one workspace at a time: the account's workspaces first, then each
   * workspace's repositories. Bitbucket removed the cross-workspace listing ({@code GET /repositories?role=member})
   * on April 14, 2026 (changelog CHANGE-2770) and answers it with HTTP 410; the per-workspace listing is what it
   * offers instead. It still reads whatever the authorizing account can, so the account's own access is the whole
   * of the grant, as on GitLab.
   */
  @Override
  public List<RepositorySummary> repositories(String accessToken) {
    var all = new ArrayList<RepositorySummary>();
    for (var workspace : workspaces(accessToken)) {
      // Only the members the picker reads, which turns a response of dozens of members and links per repository
      // into two -- Bitbucket's partial responses are the documented way to ask.
      var url = apiURL + "/repositories/" + encode(workspace) + "?pagelen=" + PAGE_SIZE
          + "&fields=next,values.full_name,values.mainbranch.name";
      for (var page = 1; url != null; page++) {
        if (page > MAX_PAGES) {
          break;
        }

        var response = send(request(accessToken, URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        unauthorized(response.statusCode(), workspace);
        require(response, "listing the repositories of", workspace);

        var body = parse(response.body(), BitbucketRepositoriesJSON::fromJSON, "/repositories/{workspace}");
        for (var repository : body.values() == null ? List.<BitbucketRepository>of() : body.values()) {
          if (repository.fullName() != null) {
            all.add(new RepositorySummary(repository.fullName(),
                repository.mainbranch() == null ? null : repository.mainbranch().name()));
          }
        }

        url = body.next();
      }
    }

    return all;
  }

  /**
   * The website's download, not the API's: Bitbucket's API has no archive endpoint. It takes the same bearer token,
   * and answers the archive directly. No {@code Accept} header, since this is not an API call.
   */
  @Override
  protected byte[] archive(String accessToken, String fullName, String commit) {
    var request = HttpRequest.newBuilder(URI.create(BitbucketConfig.WEB_URL + "/" + encodePath(fullName) + "/get/"
                                 + encode(commit) + ".zip"))
                             .timeout(TIMEOUT)
                             .header("Authorization", "Bearer " + accessToken)
                             .GET()
                             .build();
    return download(request, fullName + "@" + commit);
  }

  /**
   * The website answers a request it will not serve with a page rather than a status — a sign-in page, say — and a
   * page is not an archive. Said here, plainly, rather than left for the unzip to fail on.
   */
  @Override
  protected void checkArchive(HttpResponse<byte[]> response, String detail) {
    var contentType = response.headers().firstValue("Content-Type").orElse("");
    if (!contentType.startsWith("application/zip") && !contentType.startsWith("application/octet-stream")) {
      throw new RepositoryException("Bitbucket answered the archive of [" + detail + "] with [" + contentType
          + "] rather than a ZIP archive");
    }
  }

  @Override
  protected HttpRequest.Builder tokenRequest(String body) {
    return super.tokenRequest(body).header("Authorization", consumerAuthorization);
  }

  /**
   * The modes, from the {@code src} listing walked recursively and page by page. Only file entries are asked for:
   * a directory carries no attributes and contributes nothing to the modes.
   */
  @Override
  protected Map<String, String> tree(String accessToken, String fullName, String commit) {
    var modes = new HashMap<String, String>();
    // The trailing slash on the root is required by Bitbucket for a listing.
    var url = apiURL + repositoryPath(fullName) + "/src/" + encode(commit) + "/?max_depth=" + MAX_DEPTH
        + "&pagelen=" + PAGE_SIZE + "&q=" + encode("type=\"" + BitbucketTreeEntry.TYPE_FILE + "\"")
        + "&fields=next,values.path,values.type,values.attributes";
    for (var page = 1; url != null; page++) {
      if (page > MAX_TREE_PAGES) {
        throw new RepositoryException("The tree of [" + fullName + "@" + commit + "] has more than ["
            + MAX_TREE_PAGES * PAGE_SIZE + "] entries, which is larger than a Brief source repository may be");
      }

      var response = send(request(accessToken, URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), fullName + "@" + commit);
      require(response, "listing the tree of", fullName + "@" + commit);

      var body = parse(response.body(), BitbucketTreeJSON::fromJSON, "/repositories/{workspace}/{repo_slug}/src/{commit}/");
      for (var entry : body.values() == null ? List.<BitbucketTreeEntry>of() : body.values()) {
        if (entry.path() != null && BitbucketTreeEntry.TYPE_FILE.equals(entry.type())) {
          modes.put(entry.path(), entry.mode());
        }
      }

      url = body.next();
    }

    return modes;
  }

  /**
   * The slugs of the workspaces the account belongs to: the only workspaces whose repositories it can list.
   */
  private List<String> workspaces(String accessToken) {
    var slugs = new ArrayList<String>();
    var url = apiURL + "/user/workspaces?pagelen=" + PAGE_SIZE + "&fields=next,values.workspace.slug";
    for (var page = 1; url != null; page++) {
      if (page > MAX_PAGES) {
        break;
      }

      var response = send(request(accessToken, URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
      unauthorized(response.statusCode(), "the current user");
      require(response, "listing the workspaces of", "the current user");

      var body = parse(response.body(), BitbucketWorkspacesJSON::fromJSON, "/user/workspaces");
      for (var access : body.values() == null ? List.<BitbucketWorkspaceAccess>of() : body.values()) {
        if (access.workspace() != null && access.workspace().slug() != null) {
          slugs.add(access.workspace().slug());
        }
      }

      url = body.next();
    }

    return slugs;
  }
}
