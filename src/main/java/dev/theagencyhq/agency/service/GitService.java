/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * A thin wrapper around the {@code git} CLI. Every command uses {@code git -C <path>} rather than a working
 * directory, folds stderr into stdout, and is bounded by a timeout.
 *
 * <p>{@code pull} uses {@code --ff-only} deliberately: The Agency must never create a merge commit in a
 * developer's repository. A source that has diverged fails the pull and keeps serving from the current HEAD.
 */
public class GitService {
  public static final String DRAIN_THREAD_NAME = "GitService-drain";
  public static final Duration PULL_TIMEOUT = Duration.ofSeconds(60);
  public static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DRAIN_JOIN_TIMEOUT = Duration.ofSeconds(2);
  private static final System.Logger logger = System.getLogger(GitService.class.getName());
  private final Duration pullTimeout;
  private final Duration queryTimeout;

  public GitService() {
    this(PULL_TIMEOUT, QUERY_TIMEOUT);
  }

  /**
   * @param pullTimeout How long {@link #pull} waits before destroying the process and failing. Exposed mainly so
   *     tests can prove the timeout is genuinely enforced without waiting out the real {@link #PULL_TIMEOUT};
   *     production callers should use the no-arg constructor and the two public constants instead.
   * @param queryTimeout How long {@link #head} and {@link #isWorkTree} wait before destroying the process and
   *     failing.
   */
  public GitService(Duration pullTimeout, Duration queryTimeout) {
    this.pullTimeout = pullTimeout;
    this.queryTimeout = queryTimeout;
  }

  // destroyForcibly() only guarantees killing the process this class launched directly. A transport helper git
  // spawned (`ext::`, `ssh`) can inherit the same stdout pipe and, on some platform or JDK, outlive it — leaving
  // the reader thread started by run() blocked in readAllBytes() forever, since a blocked stream read is not
  // interruptible via Thread.interrupt(). Closing our end of the pipe forces that read to terminate regardless
  // of what the destroyed process's descendants do: verified directly (not inferred) with a standalone
  // experiment where a child process was left fully alive and running, untouched, and closing our InputStream
  // from the main thread still unblocked a concurrent readAllBytes() call in another thread within about 1ms.
  private static void abort(Process process, Thread reader) {
    process.destroyForcibly();
    try {
      process.getInputStream().close();
    } catch (IOException e) {
      logger.log(System.Logger.Level.DEBUG, "Unable to close the process output stream", e);
    }

    try {
      reader.join(DRAIN_JOIN_TIMEOUT.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static GitResult run(Duration timeout, Path path, String... arguments) {
    var command = new ArrayList<String>(List.of("git", "-C", path.toString()));
    command.addAll(List.of(arguments));

    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException e) {
      logger.log(System.Logger.Level.DEBUG, "Unable to run [" + String.join(" ", command) + "]", e);
      return new GitResult(-1, "Unable to run [" + String.join(" ", command) + "]: [" + e.getMessage() + "]");
    }

    // Drained on a separate thread concurrently with waitFor below, rather than read-to-EOF then wait. Reading
    // first avoids ever deadlocking on a full pipe buffer, but InputStream.readAllBytes() has no timeout of its
    // own: a process that runs long without producing output — which is exactly how git behaves once stderr is
    // not a terminal, e.g. a pull stuck against an unreachable remote — would then block this thread until the
    // process exits on its own, silently defeating the timeout below instead of being bounded by it. A platform
    // thread, not virtual: it is named so tests can assert it is never leaked past abort() (Thread.getAllStackTraces(),
    // used for exactly that assertion elsewhere in this project's tests, does not enumerate virtual threads).
    var outputHolder = new AtomicReference<>(new byte[0]);
    var reader = Thread.ofPlatform().name(DRAIN_THREAD_NAME).daemon(true).start(() -> {
      try {
        outputHolder.set(process.getInputStream().readAllBytes());
      } catch (IOException e) {
        // Expected on the abort() path below, which closes this stream to unblock this exact read. Also
        // reachable from a genuine spurious I/O error mid-read; either way the process may have already
        // succeeded, so this deliberately falls back to empty captured output rather than failing a result
        // whose exit code could otherwise be entirely valid.
        logger.log(System.Logger.Level.DEBUG, "Unable to read output of [" + String.join(" ", command) + "]", e);
      }
    });

    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        abort(process, reader);
        return new GitResult(-1, "The command [" + String.join(" ", command) + "] timed out after [" + timeout + "]");
      }

      // The process has already exited, so its stdout is already closed; this only bounds the otherwise
      // vanishingly small wait for the reader thread to drain whatever output was already buffered.
      reader.join(DRAIN_JOIN_TIMEOUT.toMillis());
      return new GitResult(process.exitValue(), new String(outputHolder.get(), StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      abort(process, reader);
      return new GitResult(-1, "The command [" + String.join(" ", command) + "] was interrupted");
    }
  }

  public Optional<String> head(Path path) {
    var result = run(queryTimeout, path, "rev-parse", "HEAD");
    if (!result.success()) {
      return Optional.empty();
    }

    var sha = result.output().trim();
    return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
  }

  public boolean isWorkTree(Path path) {
    if (!Files.isDirectory(path)) {
      return false;
    }

    var result = run(queryTimeout, path, "rev-parse", "--is-inside-work-tree");
    return result.success() && result.output().trim().equals("true");
  }

  public GitResult pull(Path path) {
    return run(pullTimeout, path, "pull", "--ff-only");
  }

  public record GitResult(int exitCode, String output) {
    public boolean success() {
      return exitCode == 0;
    }
  }
}
