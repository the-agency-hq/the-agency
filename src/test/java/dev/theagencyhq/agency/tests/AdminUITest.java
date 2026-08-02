/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.service.Services;
// NOT redundant alongside `import module java.base`: org.testng is an automatic module, so it exports every one of
// its packages, and one of them declares its own Files. Without this single-type import every use below is
// "reference to Files is ambiguous". The same collision is why other test classes here import Configuration,
// Connection and Duration by name.
import java.nio.file.Files;

import static org.testng.Assert.assertEquals;

/**
 * Drives the admin UI entirely through HTTP, posting forms rather than calling {@code OrganizationService} or
 * {@code PollerService} directly, so the controller's own validation and routing are what is under test.
 */
@Test
public class AdminUITest extends BaseTest {
  public StringBodyAsserter string = new StringBodyAsserter();
  private Path root;

  // BaseTest empties the database before every method, so the only thing left to clean up here is the temporary
  // Git work tree on disk. alwaysRun = true: a failure partway through must still remove it.
  @AfterMethod(alwaysRun = true)
  public void afterMethod() throws IOException {
    deleteDirectory(root);
    root = null;
  }

  @Test
  public void binaryFileRendersSizeAndDownloadsRawBytes() throws Exception {
    // 0xFF is never a valid UTF-8 leading byte, so BriefBuilder's strict decoder falls back to base64 -- this
    // exercises the binary branch of the file page (size + download link, no inline preview) and the download
    // response itself, neither of which the text-file scenarios above touch.
    var binary = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02, (byte) 0x80};
    root = createBinarySourceRepository("logo.bin", binary);
    var organizationId = createOrganization("admin-ui-binary-" + UUID.randomUUID(), root.toString());

    rebuild(organizationId);

    test.get("/app/organizations/" + organizationId + "/versions/1")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("base64"))
        .reset();

    test.get("/app/organizations/" + organizationId + "/versions/1/files/0")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("binary and cannot be previewed").contains("?download=true"))
        .reset();

    // The download link reuses the current request's own path plus ?download=true rather than embedding raw bytes
    // in the HTML page, so the raw-bytes round trip is a separate request against that same URL.
    test.get("/app/organizations/" + organizationId + "/versions/1/files/0?download=true")
        .assertStatus(200)
        .assertHeader("Content-Disposition", "attachment; filename=\"logo.bin\"")
        .assertHeader("Content-Type", "application/octet-stream")
        .assertResponse(r -> assertEquals(r.body(), binary))
        .reset();
  }

  @Test
  public void createsAnOrganizationAndShowsItsVersions() throws Exception {
    root = createSourceRepository("first\n");
    var name = "admin-ui-" + UUID.randomUUID();

    test.get("/app/organizations/new")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("action=\"/app/organizations/\"").contains("method=\"post\""))
        .reset();

    var organizationId = createOrganization(name, root.toString());

    rebuild(organizationId);

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("/organizations/" + organizationId + "/versions/1"))
        .reset();

    test.get("/app/organizations/" + organizationId + "/versions/1")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(".claude/rules/a.md").contains("r--------"))
        .reset();

    // Files are sorted by path, and ".claude/..." sorts before ".codex/...", so the claude copy is index 0.
    test.get("/app/organizations/" + organizationId + "/versions/1/files/0")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(".claude/rules/a.md").contains("first"))
        .reset();
  }

  @Test
  public void detailRendersTheOrganizationIdWithACopyButton() throws Exception {
    // The id is the one value on this page a developer has to transcribe by hand -- it goes into a Location's
    // agent-location.json -- so it renders whether or not the Organization has ever built, and it renders next to
    // a copy button carrying the id itself. Asserting data-copy rather than the button's styling ties the test to
    // the contract /static/js/copy.js reads, which is the part that would actually break.
    root = createSourceRepository("first\n");
    var organizationId = createOrganization("admin-ui-id-" + UUID.randomUUID(), root.toString());

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Organization id")
                                    .contains("data-copy=\"" + organizationId + "\""))
        .reset();
  }

  @Test
  public void fileContentIsEscapedWhenRendered() throws Exception {
    // The Brief source repository is authored elsewhere and its content is never sanitized before being stored, so
    // this proves the version and file pages escape it on the way out rather than trusting it -- the one place an
    // $unsafe{...} slip would turn into stored XSS.
    root = createSourceRepository("<script>alert(1)</script>\n");
    var organizationId = createOrganization("admin-ui-xss-" + UUID.randomUUID(), root.toString());

    rebuild(organizationId);

    test.get("/app/organizations/" + organizationId + "/versions/1/files/0")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("&lt;script&gt;").doesNotContain("<script>alert(1)</script>"))
        .reset();
  }

  @Test
  public void listingRendersTheLatestVersionAndSourceErrors() throws Exception {
    // Nothing else in the suite renders the listing page at all -- every other admin-UI test goes straight to a
    // detail, version, or file page -- so this is the only coverage of the query behind it and of the error cells.
    root = createSourceRepository("first\n");
    var organizationId = createOrganization("admin-ui-listing-" + UUID.randomUUID(), root.toString());

    rebuild(organizationId);

    // The version cell is fed by latestBriefVersions(), a different SQL statement from the one the Briefing API
    // uses. This fixture repository also has no remote, so `git pull` always fails and lastPullError is populated
    // -- which the listing never used to show, despite the row carrying it.
    test.get("/app/organizations/")
        .assertStatus(200)
        // The version cell and the pull error, not their markup: every element on this page carries Tailwind
        // classes now, so matching a bare tag would break on any styling change rather than on a behaviour change.
        .assertBodyAs(string, b -> b.contains(">1</td>").contains("Pull error:"))
        .reset();

    // Break the source and re-poll, so lastError is populated too.
    Files.delete(root.resolve("the-agency-hq-settings.json"));
    run(root, "git", "add", "-A");
    run(root, "git", "commit", "-q", "-m", "remove the settings marker");
    rebuild(organizationId);

    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("BUILD_FAILED").contains("Build error:"))
        .reset();
  }

  @Test
  public void malformedOrganizationIdIs404() {
    test.get("/app/organizations/not-a-uuid")
        .assertStatus(404)
        .reset();
    test.post("/app/organizations/not-a-uuid/rebuild")
        .assertStatus(404)
        .reset();
    test.get("/app/organizations/not-a-uuid/versions/1")
        .assertStatus(404)
        .reset();
    test.get("/app/organizations/not-a-uuid/versions/1/files/0")
        .assertStatus(404)
        .reset();
  }

  @Test
  public void malformedVersionAndIndexAreNotFound() throws Exception {
    root = createSourceRepository("first\n");
    var organizationId = createOrganization("admin-ui-404-" + UUID.randomUUID(), root.toString());

    rebuild(organizationId);

    // Non-integer and out-of-range version.
    test.get("/app/organizations/" + organizationId + "/versions/not-a-number")
        .assertStatus(404)
        .reset();
    test.get("/app/organizations/" + organizationId + "/versions/999")
        .assertStatus(404)
        .reset();

    // Non-integer, negative, and out-of-range file index against the real version 1 (which has exactly 2 files).
    test.get("/app/organizations/" + organizationId + "/versions/1/files/not-a-number")
        .assertStatus(404)
        .reset();
    test.get("/app/organizations/" + organizationId + "/versions/1/files/-1")
        .assertStatus(404)
        .reset();
    test.get("/app/organizations/" + organizationId + "/versions/1/files/999")
        .assertStatus(404)
        .reset();
  }

  @Test
  public void rejectsAnInvalidPath() throws Exception {
    // Deliberately outside the project's own working tree (unlike build/test/..., which is a subdirectory of this
    // repository's git work tree and so would itself read back as a Git repository via the nearest ancestor .git).
    root = Files.createTempDirectory("admin-ui-not-a-repo-");
    var name = "admin-ui-invalid-" + UUID.randomUUID();

    test.withFormField("name", name)
        .withFormField("path", root.toString())
        .post("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("is not a Git repository"))
        .reset();

    assertEquals(db.listOrganizations().stream().filter(o -> o.name().equals(name)).count(), 0L);
  }

  @Test
  public void rootRedirectsToTheListing() {
    test.get("/")
        .assertRedirect(303, "/app/organizations/");
  }
}
