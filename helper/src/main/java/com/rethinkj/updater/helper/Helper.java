package com.rethinkj.updater.helper;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The helper process: {@code java -jar rethinkj-updater-helper.jar <plan>}, where {@code <plan>}
 * is the path of {@code plan.properties} in the app's update folder. It waits until the app has
 * exited and replaces it. With the plan's mode {@code restart} it starts the new version and waits
 * for {@code confirmed.properties} in the same folder; without it, it puts the old version back
 * and starts that. What it did, it writes into {@code result.properties} in the same folder; it
 * prints nothing. Once the new version is in place, it names the old one in {@code
 * backup.properties} there, so that core can finish the update should the helper be ended first.
 *
 * <p>{@code java -jar rethinkj-updater-helper.jar list <folder>} writes the list of the app's own
 * files into a built app's folder, before it is packed.
 */
public final class Helper {

  /** How long the helper tries again to rename a file that another process has open. */
  private static final Duration LOCKED_FOR = Duration.ofSeconds(30);

  /** Names the old version while the new one is in place and has not failed to confirm. */
  private static final String BACKUP = "backup.properties";

  private static final String APPLIED = "applied";
  private static final String REJECTED = "rejected";

  private Helper() {}

  /**
   * Exits with 0 if it applied the plan, 1 if it did not, and 2 if the arguments are neither a plan
   * nor {@code list <folder>}. With {@code list}, an exception tells that the list cannot be
   * written.
   */
  public static void main(String[] args) throws IOException {
    if (args.length == 2 && args[0].equals("list")) {
      Replace.writeList(Path.of(args[1]));
      return;
    }
    System.exit(args.length == 1 ? run(Path.of(args[0])) : 2);
  }

  static int run(Path file) {
    Path folder = file.toAbsolutePath().getParent();
    // Held until the result is written, or the helper is ended: core starts no helper meanwhile.
    FileChannel lock = null; // NOPMD: closed below, after the result
    IOException unlocked = null;
    try {
      lock = FileChannel.open(
          folder.resolve("helper.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      lock.lock(); // waits for a helper still at work, which an older core may have started
    } catch (IOException e) {
      unlocked = e;
    }
    Plan plan = null;
    Path backup = null;
    boolean running = false; // whether the version in place runs, started and confirmed
    boolean unconfirmed = false; // whether the version that did not confirm may be in place
    String status = APPLIED;
    String reason = null;
    String message;
    try {
      plan = Plan.read(file);
      ProcessHandle.of(plan.pid()).ifPresent(app -> app.onExit().join());
      if (unlocked != null) {
        // Else core could take this helper for one that was ended, and delete the old version.
        throw new Plan.Rejected("failed", "Cannot lock helper.lock: " + unlocked);
      }
      backup = Replace.apply(plan, LOCKED_FOR);
      // For core to delete, should the helper be ended before it reports; if it cannot be
      // written, core cannot tell and leaves the old version where it is.
      write(folder, BACKUP, "backup", backup.toString());
      message = "Replaced " + plan.app() + " with " + plan.file();
      if (plan.mode() == Plan.Mode.RESTART) {
        running = startConfirmed(plan, folder);
        if (!running) {
          unconfirmed = true;
          rollBack(plan, backup);
          unconfirmed = false;
          status = "rolled-back";
          reason = "not-confirmed";
          message = "The new version did not confirm within "
              + plan.confirmWithin().toSeconds() + " s that it runs, or exited before, so "
              + plan.app() + " holds the old one again";
        }
      }
    } catch (Plan.Rejected e) {
      status = REJECTED;
      reason = e.reason;
      message = e.getMessage();
    } catch (Replace.RolledBack e) {
      status = "rolled-back";
      reason = e.reason;
      message = e.getMessage();
    } catch (Exception e) { // NOPMD: also a RuntimeException from a broken archive
      status = REJECTED;
      reason = "failed";
      message = e.toString();
    }
    // If it cannot be written, nowhere is left to report it but the exit code.
    boolean reported =
        write(folder, "result.properties", "status", status, "reason", reason, "message", message);
    close(lock); // before the backup is deleted, which may take a while: the helper has reported
    if (reported && status.equals(APPLIED)) {
      // Only now: until there is a result, the backup tells core the new version is in place.
      Replace.delete(backup);
    }
    // The version that ran before, once the result is there, which it reads.
    if (reported && plan != null && plan.mode() == Plan.Mode.RESTART && !running && !unconfirmed) {
      startOld(plan, folder);
    }
    return status.equals(APPLIED) ? 0 : 1;
  }

  /** Puts the old version back, retrying a locked file also if the helper was interrupted. */
  private static void rollBack(Plan plan, Path backup) throws IOException {
    boolean interrupted = Thread.interrupted(); // an interrupted thread would not wait to retry
    try {
      Replace.rollBack(plan.app(), backup, LOCKED_FOR);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Starts the version that ran before, if it can; the result says why not. */
  private static void startOld(Plan plan, Path folder) {
    try {
      start(plan, folder);
    } catch (IOException _) {
      // Such as when the app's folder is missing.
    }
  }

  /** Starts the version in place with the plan's command. */
  private static Process start(Plan plan, Path folder) throws IOException {
    return new ProcessBuilder(plan.command())
        .directory(folder.toFile()) // not the app's folder, which a rollback renames
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  /**
   * Starts the new version and waits until it writes {@code confirmed.properties}. If it does not
   * within the plan's time, or exits before, or the helper is interrupted, it deletes {@code
   * backup.properties}, ends the new version and returns false.
   */
  private static boolean startConfirmed(Plan plan, Path folder) {
    Path confirmed = folder.resolve("confirmed.properties");
    // Gathered at every poll: a process that outlives its parent gets another one.
    // shortcut: a child that is started and orphaned within one poll escapes; contain the tree
    // (a job object, a process group) if that shows up, which java.base alone cannot.
    Set<ProcessHandle> started = new HashSet<>();
    try {
      Files.deleteIfExists(confirmed);
      Process app = start(plan, folder);
      started.add(app.toHandle());
      long end = System.nanoTime() + plan.confirmWithin().toNanos();
      while (true) {
        gather(started);
        boolean exited = !app.isAlive(); // before the check: it may confirm and then exit
        if (Files.exists(confirmed)) {
          return true;
        }
        if (exited || System.nanoTime() - end >= 0) {
          break;
        }
        Thread.sleep(100);
      }
    } catch (IOException _) {
      // Such as when the new version no longer has the command's launcher.
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt(); // then it rolls back, as if the version did not confirm
    }
    try {
      // First: should the helper be ended from here on, core must not keep the new version.
      Files.deleteIfExists(folder.resolve(BACKUP));
    } catch (IOException _) {
      // Then core would keep it, but only if the helper were also ended before it reports.
    }
    stop(started);
    return false;
  }

  /**
   * Ends the processes the new version started, and waits for them, so that none of them holds a
   * file of it open: on Windows, a jpackage launcher starts itself again as its child.
   */
  private static void stop(Set<ProcessHandle> started) {
    gather(started);
    started.forEach(ProcessHandle::destroyForcibly);
    long end = System.nanoTime() + LOCKED_FOR.toNanos();
    for (ProcessHandle p : started) {
      long left = Math.max(0, end - System.nanoTime());
      p.onExit().completeOnTimeout(p, left, TimeUnit.NANOSECONDS).join();
    }
  }

  /** Adds the descendants of the processes in {@code started}, also of those with a new parent. */
  private static void gather(Set<ProcessHandle> started) {
    for (ProcessHandle p : List.copyOf(started)) {
      p.descendants().forEach(started::add);
    }
  }

  private static void close(FileChannel lock) {
    try {
      if (lock != null) {
        lock.close();
      }
    } catch (IOException _) {
      // The lock goes with the process.
    }
  }

  /**
   * Writes the file {@code name} of format 1 into {@code folder}, replacing an earlier one, with
   * keys and their values in turn, and returns whether it could. A key whose value is null is left
   * out.
   */
  private static boolean write(Path folder, String name, String... keysAndValues) {
    Properties p = new Properties();
    p.setProperty("format", "1");
    for (int i = 0; i < keysAndValues.length; i += 2) {
      if (keysAndValues[i + 1] != null) {
        p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
      }
    }
    try {
      Path temp = Files.createTempFile(folder, name + "-", ".tmp");
      try {
        try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
          p.store(out, null);
        }
        // Whole or not at all: the helper may be ended while it writes, when the user logs off.
        Files.move(
            temp,
            folder.resolve(name),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temp);
      }
      return true;
    } catch (IOException _) {
      return false;
    }
  }
}
