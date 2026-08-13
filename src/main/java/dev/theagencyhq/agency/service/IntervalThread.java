/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * A service thread that runs {@link #execute()} on a fixed interval and can be woken early by a {@link #nudge()}.
 * This mirrors the Handler's class of the same name, deliberately: the two codebases already hand-mirror the wire
 * contract, and a service loop whose shutdown and wake-up semantics are proven on one side should not be
 * re-derived on the other.
 *
 * <p>The interval is measured from the end of one run to the start of the next, so a slow run never queues up
 * back-to-back runs. Waiting happens before the first run, so the interval doubles as the initial delay.
 *
 * <p>A nudge that arrives while {@link #execute()} is running is recorded in {@code nudgePending} rather than lost.
 * That is the whole reason the flag exists: {@link Condition#signal()} is edge-triggered and means nothing to a
 * thread that is not parked. The flag is set <em>before</em> the signal, so either the parked thread is woken or
 * the next {@link #awaitNudge()} sees the flag and skips the wait entirely.
 *
 * <p>The lock is held only around the wait, never across {@link #execute()}, so {@link #nudge()} never blocks
 * behind a running cycle. Shutdown is a flag plus a signal — never {@link Thread#interrupt()} — so an in-flight run
 * finishes on its own rather than being torn apart partway through a build.
 */
public abstract class IntervalThread extends Thread {
  private static final System.Logger logger = System.getLogger(IntervalThread.class.getName());
  private final Lock lock = new ReentrantLock();
  private final Condition nudge = lock.newCondition();
  private final AtomicBoolean nudgePending = new AtomicBoolean();
  private volatile boolean running = true;

  protected IntervalThread(String name) {
    super(name);
    setDaemon(true);
  }

  /**
   * Wakes the thread now rather than letting it wait out the rest of its interval. Safe to call from any thread, at
   * any time, including before the thread is started and after it has stopped. The nudge carries no payload and is
   * never a correctness requirement — if it is coalesced away, the next interval run converges normally.
   */
  public void nudge() {
    // Set before signaling. If a run is in flight nobody is parked and the signal is discarded, but the flag
    // survives and the next awaitNudge() skips its wait.
    nudgePending.set(true);
    lock.lock();
    try {
      nudge.signal();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void run() {
    logger.log(System.Logger.Level.DEBUG, "[{0}] started with an interval of [{1}]s", getName(), intervalSeconds());
    while (running) {
      if (!awaitNudge() || !running) {
        break;
      }

      try {
        execute();
      } catch (Throwable t) {
        // An escape here would end the loop and silently stop the service for the life of the process
        logger.log(System.Logger.Level.ERROR, "The [" + getName() + "] cycle threw", t);
      }
    }

    logger.log(System.Logger.Level.DEBUG, "[{0}] stopped", getName());
  }

  /**
   * FOR TESTING ONLY!
   */
  public void testRun() {
    execute();
  }

  /**
   * Stops the thread after the run in flight completes. Never interrupts, so an in-flight build is always allowed
   * to finish rather than leaving a half-written version behind. Callers that need to wait should follow this with
   * {@link Thread#join(long)}.
   */
  public void shutdown() {
    running = false;
    lock.lock();
    try {
      nudge.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * One cycle of whatever this service does. Implementations may throw — the loop logs and continues.
   */
  protected abstract void execute();

  /**
   * @return The number of seconds to wait between runs. Read fresh before every wait.
   */
  protected abstract long intervalSeconds();

  /**
   * Waits for a nudge or for the interval to expire, whichever comes first.
   *
   * @return True to run the next cycle, false if the thread was interrupted and must stop.
   */
  private boolean awaitNudge() {
    lock.lock();
    try {
      if (nudgePending.compareAndSet(true, false)) {
        return true;      // A nudge landed while the last cycle was running, so run again immediately
      }

      // A spurious wakeup costs one early cycle, which is harmless -- every cycle here is idempotent
      //noinspection ResultOfMethodCallIgnored
      nudge.await(intervalSeconds(), TimeUnit.SECONDS);
      nudgePending.set(false);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      lock.unlock();
    }
  }
}
