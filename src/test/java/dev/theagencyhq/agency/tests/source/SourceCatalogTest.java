/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.source;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.web;
import module org.testng;

// Single-type imports where TestNG exports a class of the same simple name.
import java.nio.file.Files;
import org.lattejava.web.Configuration;

import static org.testng.Assert.*;

/**
 * {@code SourceCatalog} against configurations written to disk: which kinds each one offers, and the host URLs it
 * builds from them. A unit test — the catalog reads configuration and builds strings, and a server is pure cost
 * for that — so it does not extend {@code BaseTest}.
 */
@Test
public class SourceCatalogTest {
  private final FakeRepositoryClient github = new FakeRepositoryClient("octocat");
  private final FakeRepositoryClient gitlab = new FakeRepositoryClient("tanuki");

  @Test
  public void aBlankCredentialIsNotConfigured() throws IOException {
    var catalog = catalog("github.clientId=abc", "github.clientSecret=  ", "github.appName=the-agency",
        "gitlab.clientId=", "gitlab.clientSecret=xyz");

    assertEquals(catalog.available(), List.of());
  }

  @Test
  public void aConfiguredGitHubRequiresTheAppSlug() throws IOException {
    var e = expectThrows(IllegalStateException.class, () -> catalog("github.clientId=abc", "github.clientSecret=def"));

    assertTrue(e.getMessage().contains("[github.appName]"), e.getMessage());
  }

  @Test
  public void buildsTheHostURLs() throws IOException {
    var catalog = catalog("github.clientId=gh id", "github.clientSecret=s", "github.appName=the-agency",
        "gitlab.clientId=gl-id", "gitlab.clientSecret=s", "gitlab.baseURL=https://git.example.com/");

    assertEquals(catalog.authorizeURL(BriefSourceType.GITHUB, "http://localhost:8080/app/oauth/github/callback", "n once"),
        "https://github.com/login/oauth/authorize?client_id=gh+id"
            + "&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapp%2Foauth%2Fgithub%2Fcallback&state=n+once");
    assertEquals(catalog.authorizeURL(BriefSourceType.GITLAB, "http://localhost:8080/app/oauth/gitlab/callback", "nonce"),
        "https://git.example.com/oauth/authorize?client_id=gl-id"
            + "&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapp%2Foauth%2Fgitlab%2Fcallback"
            + "&response_type=code&scope=read_api&state=nonce");
  }

  /**
   * A fresh source carries what its kind needs beyond the credential: nothing for GitHub, the instance's origin for
   * GitLab -- with the trailing slash of the configured base URL dropped, so the URLs it later builds do not double
   * it up.
   */
  @Test
  public void createsAFreshSourceForEachKind() throws IOException {
    var catalog = catalog("github.clientId=a", "github.clientSecret=b", "github.appName=c", "gitlab.clientId=a",
        "gitlab.clientSecret=b", "gitlab.baseURL=https://git.example.com/");
    var connection = new OAuthConnection("octocat", new OAuthTokens("access", null, null, null));

    assertEquals(catalog.unregistered(BriefSourceType.GITHUB, connection), new GitHubConfig(connection, null, null, null));
    assertEquals(catalog.unregistered(BriefSourceType.GITLAB, connection),
        new GitLabConfig(connection, "https://git.example.com", null, null));

    var registered = catalog.unregistered(BriefSourceType.GITLAB, connection).withRepository("acme/platform/briefs", "main");
    assertEquals(registered.url(), "https://git.example.com/acme/platform/briefs");
    assertEquals(registered.details(), List.of(
        new SourceDetail("Project", "acme/platform/briefs", "https://git.example.com/acme/platform/briefs"),
        new SourceDetail("Branch", "main", null)));
    assertEquals(catalog.unregistered(BriefSourceType.GITLAB, connection).details(), List.of());
  }

  @Test
  public void gitLabDefaultsToGitLabDotCom() throws IOException {
    var catalog = catalog("gitlab.clientId=gl", "gitlab.clientSecret=s");

    assertEquals(catalog.available(), List.of(BriefSourceType.GITLAB));
    assertEquals(catalog.unregistered(BriefSourceType.GITLAB, null), new GitLabConfig(null, "https://gitlab.com", null, null));
    assertTrue(catalog.authorizeURL(BriefSourceType.GITLAB, "http://localhost/cb", "n")
                      .startsWith("https://gitlab.com/oauth/authorize?client_id=gl&"));
  }

  @Test
  public void offersEveryKindWithCredentials() throws IOException {
    assertEquals(catalog().available(), List.of());
    assertEquals(catalog("github.clientId=a", "github.clientSecret=b", "github.appName=c").available(),
        List.of(BriefSourceType.GITHUB));
    assertEquals(catalog("gitlab.clientId=a", "gitlab.clientSecret=b").available(), List.of(BriefSourceType.GITLAB));

    var both = catalog("github.clientId=a", "github.clientSecret=b", "github.appName=c", "gitlab.clientId=a",
        "gitlab.clientSecret=b");
    assertEquals(both.available(), List.of(BriefSourceType.GITHUB, BriefSourceType.GITLAB));
    assertTrue(both.configured(BriefSourceType.GITHUB));
    assertTrue(both.configured(BriefSourceType.GITLAB));
    assertSame(both.client(BriefSourceType.GITHUB), github);
    assertSame(both.client(BriefSourceType.GITLAB), gitlab);
  }

  /**
   * The unconfigured kind's client still resolves -- the poller asks for a source's client before it checks the
   * kind is configured -- and is simply never asked anything.
   */
  @Test
  public void anUnconfiguredKindStillHasAClient() throws IOException {
    var catalog = catalog("gitlab.clientId=a", "gitlab.clientSecret=b");

    assertFalse(catalog.configured(BriefSourceType.GITHUB));
    assertSame(catalog.client(BriefSourceType.GITHUB), github);
  }

  /**
   * @param lines The properties, one {@code name=value} per line, as a configuration file would carry them.
   * @return A catalog over exactly those settings and nothing else.
   */
  private SourceCatalog catalog(String... lines) throws IOException {
    var file = Files.createTempFile("source-catalog-", ".properties");
    try {
      Files.writeString(file, String.join("\n", lines) + "\n");
      return new SourceCatalog(new Configuration(List.of(), file), github, gitlab);
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
