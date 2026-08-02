/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module java.base;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.agency.service.*;
import dev.theagencyhq.agency.tests.*;

import static org.testng.Assert.*;

@SuppressWarnings("BusyWait")
@Test
public class GitServiceTest extends BaseTest {
  private Path root;

  // Mirrors DatabaseServiceTest's assertNoLeakedPoolThreads: Thread.getAllStackTraces() does not enumerate
  // virtual threads at all (verified directly — a named virtual thread parked in Thread.sleep() never appeared
  // in it), which is exactly why GitService's drain thread is a named platform thread and not a virtual one;
  // otherwise this assertion would trivially "pass" whether or not the thread was actually gone.
  private static void assertDrainThreadGone() throws InterruptedException {
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    do {
      var stillRunning = Thread.getAllStackTraces()
                               .keySet()
                               .stream()
                               .anyMatch(t -> t.getName().equals(GitService.DRAIN_THREAD_NAME));
      if (!stillRunning) {
        return;
      }
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    fail("GitService's drain thread [" + GitService.DRAIN_THREAD_NAME + "] is still running 5s after pull() timed out");
  }

  @AfterMethod
  public void afterMethod() throws IOException {
    deleteDirectory(root);
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    // Deliberately the system temp directory, not this project's usual build/test/: the-agency checkout is
    // itself a Git work tree, so a directory under build/test/ is always "inside a work tree" per git's upward
    // .git discovery — verified directly with `git -C build/test/<anything> rev-parse --is-inside-work-tree`
    // printing [true] with no .git of its own. headIsEmptyOutsideARepository and the first half of
    // isWorkTreeDetectsARepository need a directory that is genuinely outside any repository.
    root = Files.createTempDirectory("git-service-");
  }

  @Test
  public void headIsEmptyOutsideARepository() {
    assertTrue(new GitService().head(root).isEmpty());
  }

  @Test
  public void headReturnsTheCommitSHA() throws Exception {
    Files.writeString(root.resolve("README.md"), "hello\n");
    initRepository(root);

    var head = new GitService().head(root);
    assertTrue(head.isPresent());
    assertEquals(head.get().length(), 40, head.get());
  }

  @Test
  public void isWorkTreeDetectsARepository() throws Exception {
    assertFalse(new GitService().isWorkTree(root));
    Files.writeString(root.resolve("README.md"), "hello\n");
    initRepository(root);
    assertTrue(new GitService().isWorkTree(root));
  }

  @Test
  public void pullFailsWithNoRemoteButDoesNotThrow() throws Exception {
    Files.writeString(root.resolve("README.md"), "hello\n");
    initRepository(root);

    var result = new GitService().pull(root);
    assertFalse(result.success());
    assertFalse(result.output().isBlank());
  }

  // Proves two things about the fix to the brief's original run(): (1) pull() is actually bounded by its
  // configured timeout — not less, which would mean some unrelated failure fired instead of the timeout (e.g.
  // protocol.ext.allow not honored on some git version, or a script permission problem), silently proving
  // nothing; and not the remote's full hang duration, which was the original bug (reading a process's stdout to
  // EOF before calling waitFor(timeout, ...) leaves the timeout unenforced whenever the child produces no
  // output before exiting, exactly how git behaves once stderr is not a terminal). And (2) the thread draining
  // that stdout is gone afterward rather than leaked forever: destroyForcibly() only guarantees killing the
  // direct git process, and a transport helper it spawned (here, `ext::`'s script) can inherit the same stdout
  // pipe and outlive it, which is why run()'s abort() explicitly closes the stream rather than relying on the
  // child's own termination.
  //
  // Uses the two-argument constructor to inject a short timeout instead of waiting out the real 60s
  // PULL_TIMEOUT: an earlier version of this test proved the identical thing against the real constant (a
  // 90s hang, elapsed ~63s instead of ~90s) but cost the whole suite about a minute for one test. This reaches
  // the same conclusion in well under a second.
  @Test
  public void pullTimesOutStrictlyAndDoesNotLeakItsDrainThread() throws Exception {
    Files.writeString(root.resolve("README.md"), "hello\n");
    initRepository(root);
    run(root, "git", "config", "protocol.ext.allow", "always");

    var script = Files.createTempFile("git-service-sleeper-", ".sh");
    try {
      Files.writeString(script, "#!/bin/sh\nsleep 5\n");
      Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
      run(root, "git", "remote", "add", "origin", "ext::" + script);

      var timeout = Duration.ofMillis(250);
      var start = Instant.now();
      var result = new GitService(timeout, timeout).pull(root);
      var elapsed = Duration.between(start, Instant.now());

      assertFalse(result.success());
      assertTrue(result.output().contains("timed out after"), result.output());
      // Lower bound: proves it actually waited for the timeout instead of failing fast for an unrelated reason.
      assertTrue(elapsed.compareTo(timeout) >= 0,
          "pull() returned in [" + elapsed + "], before its configured timeout [" + timeout + "] could have fired");
      // Upper bound: proves it did not wait for the remote's full 5s hang.
      assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0,
          "pull() took [" + elapsed + "], which suggests it waited for the remote's hang instead of timing out");

      assertDrainThreadGone();
    } finally {
      Files.deleteIfExists(script);
    }
  }
}
