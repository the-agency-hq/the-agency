/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.source;

import module dev.theagencyhq.agency;
import module java.base;
import module java.net.http;

import dev.theagencyhq.agency.model.internal.*;

import java.lang.System.Logger.*;

/**
 * What every real {@link RepositoryClient} has in common, over {@code java.net.http}: one HTTP client, the
 * bearer-token request, the three-way reading of a status code the contract asks for, the OAuth token grant, and the
 * archive download. A host's client extends this and writes only what its API does differently — its URLs and wire
 * shapes, how its lists page, and where its file modes come from.
 *
 * <p>The HTTP client never follows a redirect on its own. The archive download is the one place a host redirects —
 * GitHub always, to codeload; GitLab from object storage — and {@link #download} follows it by hand without the
 * {@code Authorization} header, so the token is never sent to a host that did not ask for it. The redirect target is a
 * signed URL that needs no credential anyway, and sending one to a host that did not ask for it is how a token leaks.
 */
public abstract class HTTPRepositoryClient implements RepositoryClient {
  /**
   * How many pages of a repository listing to walk before giving up. Every host pages at {@link #PAGE_SIZE}, so this
   * covers an account with a thousand repositories and still terminates if a next link ever lies.
   */
  public static final int MAX_PAGES = 10;
  /**
   * How many pages of tree entries to walk before declaring the repository too large: ten thousand entries is two
   * orders of magnitude more than a tree of prose and configuration has, and a build must fail rather than publish
   * files whose modes it could not read.
   */
  public static final int MAX_TREE_PAGES = 100;
  public static final int PAGE_SIZE = 100;
  protected static final Duration TIMEOUT = Duration.ofSeconds(30);
  protected final String apiURL;
  protected final String host;
  protected final HttpClient httpClient;
  protected final System.Logger logger = System.getLogger(getClass().getName());
  protected final String tokenURL;

  /**
   * @param host     The host's name, for log and failure messages.
   * @param apiURL   What the API's paths are relative to, with no trailing slash.
   * @param tokenURL The OAuth token endpoint.
   */
  protected HTTPRepositoryClient(String host, String apiURL, String tokenURL) {
    this.apiURL = apiURL;
    this.host = host;
    this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
    this.tokenURL = tokenURL;
  }

  /**
   * @param value A query value, or a whole path value.
   * @return The value URL-encoded as one piece, separators included: {@code group/project} becomes
   *     {@code group%2Fproject}, which is how GitLab addresses a project and how every host reads a query parameter.
   */
  protected static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * @param path A repository-relative path, or a ref name.
   * @return The path with each segment URL-encoded separately, so the separators survive: {@code rules/a.md} stays
   *     a path of two segments rather than one segment with a slash in its name, which GitHub and Bitbucket would
   *     answer 404 for.
   */
  protected static String encodePath(String path) {
    return Arrays.stream(path.split("/", -1)).map(HTTPRepositoryClient::encode).collect(Collectors.joining("/"));
  }

  /**
   * @param nameValues Names and values, alternating.
   * @return The pairs as an {@code application/x-www-form-urlencoded} body.
   */
  protected static String form(String... nameValues) {
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
   * The two path segments GitHub and Bitbucket address a repository by, where everything above the client names it
   * as one string. Neither a GitHub owner nor a Bitbucket workspace can contain a slash, so the first one is the
   * separator.
   *
   * @param fullName {@code owner/repository}.
   * @return The two halves, each encoded, joined by the slash.
   */
  protected static String repositorySegments(String fullName) {
    var slash = fullName.indexOf('/');
    return slash < 0 ? encode(fullName) + "/"
        : encode(fullName.substring(0, slash)) + "/" + encode(fullName.substring(slash + 1));
  }

  @Override
  public RepositoryContents contents(String accessToken, String fullName, String commit) {
    // The tree first, and against the same commit SHA the archive is fetched with, so the two halves of the
    // download describe one state of the repository even if somebody pushes between the two requests.
    var modes = tree(accessToken, fullName, commit);
    var files = Archives.unzip(archive(accessToken, fullName, commit), host);
    return new RepositoryContents(commit, files, modes);
  }

  /**
   * Reads a status code the way the contract asks. A 401 is the credential's fault and throws. A 403 or 404 means
   * the thing asked about is not visible to this token — and 404 deliberately, on every host, so a private
   * repository cannot be probed for existence — which is an absence rather than a fault, with a different fix.
   * Anything else outside 2xx is a failure to complete the call at all.
   *
   * @param response  The response.
   * @param operation What the request was doing, as a participle: "resolving", "reading the tree of".
   * @param detail    What it was doing it to: {@code fullName@ref}.
   * @return True if there is nothing there, which the caller returns as {@code null}.
   * @throws RepositoryUnauthorizedException On a 401.
   * @throws RepositoryException             On any other status outside 2xx, 403 and 404.
   */
  protected boolean absent(HttpResponse<?> response, String operation, String detail) {
    unauthorized(response.statusCode(), detail);
    if (response.statusCode() == 403 || response.statusCode() == 404) {
      return true;
    }

    require(response, operation, detail);
    return false;
  }

  /**
   * Downloads the repository's archive at one commit, as the host serves it — through {@link #download}.
   *
   * @param accessToken The token.
   * @param fullName    The repository.
   * @param commit      The commit SHA.
   * @return The ZIP bytes.
   */
  protected abstract byte[] archive(String accessToken, String fullName, String commit);

  /**
   * A hook for a host whose archive needs checking beyond its status code, run before the size check. Nothing by
   * default.
   *
   * @param response The 2xx response, after any redirect.
   * @param detail   {@code fullName@commit}, for the failure message.
   */
  protected void checkArchive(HttpResponse<byte[]> response, String detail) {
  }

  /**
   * Runs an archive request: follows the one redirect a host may answer with, reads the status the contract's way,
   * and refuses an archive larger than a Brief source repository may be.
   *
   * @param request The request, carrying the token.
   * @param detail  {@code fullName@commit}, for the failure messages.
   * @return The ZIP bytes.
   */
  protected byte[] download(HttpRequest request, String detail) {
    var response = send(request, HttpResponse.BodyHandlers.ofByteArray());

    // Followed by hand and with no Authorization header, because the JDK's own redirect handling would carry the
    // header to a different host.
    if (response.statusCode() == 301 || response.statusCode() == 302 || response.statusCode() == 307) {
      var location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new RepositoryException(host + " redirected the archive of [" + detail + "] with no [Location] header");
      }

      response = send(HttpRequest.newBuilder(URI.create(location)).timeout(TIMEOUT).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }

    unauthorized(response.statusCode(), detail);
    require(response, "downloading the archive of", detail);
    checkArchive(response, detail);
    if (response.body().length > Archives.MAX_CONTENT_BYTES) {
      throw new RepositoryException("The archive of [" + detail + "] is [" + response.body().length
          + "] bytes, which is larger than a Brief source repository may be");
    }

    return response.body();
  }

  /**
   * @param body      A JSON body.
   * @param parser    The codec's reader for the shape expected.
   * @param operation The endpoint, for the failure message.
   * @param <T>       The shape.
   * @return The parsed body.
   * @throws RepositoryException If the body is not that shape.
   */
  protected <T> T parse(String body, Function<String, T> parser, String operation) {
    try {
      return parser.apply(body);
    } catch (RuntimeException e) {
      throw new RepositoryException("Unable to parse the " + host + " response for [" + operation + "]", e);
    }
  }

  /**
   * @param accessToken The token.
   * @param path        A path relative to the API's URL, query string included.
   * @return A bearer-token request for it.
   */
  protected HttpRequest.Builder request(String accessToken, String path) {
    return request(accessToken, URI.create(apiURL + path));
  }

  /**
   * A bearer-token request for the API's JSON. A host that wants other headers overrides this.
   *
   * @param accessToken The token.
   * @param uri         The absolute URI.
   * @return The request, without a method yet.
   */
  protected HttpRequest.Builder request(String accessToken, URI uri) {
    return HttpRequest.newBuilder(uri)
                      .timeout(TIMEOUT)
                      .header("Accept", "application/json")
                      .header("Authorization", "Bearer " + accessToken);
  }

  /**
   * @param response  The response.
   * @param operation What the request was doing, as a participle.
   * @param detail    What it was doing it to.
   * @throws RepositoryException If the status is outside 2xx, quoting the body when there is a readable one.
   */
  protected void require(HttpResponse<?> response, String operation, String detail) {
    if (response.statusCode() / 100 != 2) {
      var body = response.body() instanceof String text && !text.isEmpty() ? ": [" + text + "]" : "";
      throw new RepositoryException(host + " returned HTTP [" + response.statusCode() + "] " + operation + " ["
          + detail + "]" + body);
    }
  }

  /**
   * @throws RepositoryException If the request cannot be completed at all: a transport failure or an interruption.
   */
  protected <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    try {
      return httpClient.send(request, handler);
    } catch (IOException e) {
      throw new RepositoryException("Unable to call " + host + " [" + request.method() + " " + request.uri() + "]", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RepositoryException("Interrupted calling " + host + " [" + request.method() + " " + request.uri() + "]",
          e);
    }
  }

  /**
   * Runs one OAuth grant — the code exchange or the refresh — and reads the credential out of the answer.
   *
   * <p>The hosts disagree on what a rejected grant looks like: GitHub answers 200 with an {@code error} member and no
   * token, GitLab and Bitbucket answer 400 or 401 with the same members. Both are the "no credential" outcome the
   * caller expects as {@code null}, so both are read here, and neither is inferred from the status alone; anything
   * else is a failure to complete the call at all.
   *
   * @param body The form body of the grant.
   * @return The credential, or {@code null} if the host rejected the grant.
   */
  protected OAuthTokens token(String body) {
    var response = send(tokenRequest(body).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 400 || response.statusCode() == 401) {
      // The body names the reason (an already-used code, a revoked refresh token) and carries no credential.
      logger.log(Level.WARNING, "{0} rejected the grant with HTTP [{1}]: [{2}]", host, response.statusCode(), response.body());
      return null;
    }
    require(response, "answering", "the token endpoint");

    var parsed = parse(response.body(), OAuthTokenResponseJSON::fromJSON, "the token endpoint");
    if (parsed.error() != null) {
      logger.log(Level.WARNING, "{0} rejected the grant: [{1}] [{2}]", host, parsed.error(), parsed.errorDescription());
    }

    // Instant.now() is read after the response arrives rather than before the request, so a slow round trip
    // shortens the recorded lifetime instead of overstating it.
    return OAuthTokens.from(parsed, Instant.now());
  }

  /**
   * The form POST to the token endpoint. A host that authenticates the application some other way than in the form
   * — Bitbucket, with HTTP Basic — adds to this.
   *
   * @param body The form body.
   * @return The request.
   */
  protected HttpRequest.Builder tokenRequest(String body) {
    return HttpRequest.newBuilder(URI.create(tokenURL))
                      .timeout(TIMEOUT)
                      .header("Accept", "application/json")
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .POST(HttpRequest.BodyPublishers.ofString(body));
  }

  /**
   * Reads the file modes at one commit, which the archive cannot carry.
   *
   * @param accessToken The token.
   * @param fullName    The repository.
   * @param commit      The commit SHA.
   * @return The Git mode of every file, by repository-relative path.
   */
  protected abstract Map<String, String> tree(String accessToken, String fullName, String commit);

  /**
   * @param status The status code.
   * @param detail What the request was about, for the message.
   * @throws RepositoryUnauthorizedException On a 401 and only a 401. See {@link #absent} for why not 403 or 404.
   */
  protected void unauthorized(int status, String detail) {
    if (status == 401) {
      throw new RepositoryUnauthorizedException(host + " rejected the access token for [" + detail + "]");
    }
  }
}
