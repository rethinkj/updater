package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationTest {

  @TempDir
  Path temp;

  @Test
  void reportsTheResultOnceAndDeletesWhatEarlierUpdatesLeftOnlyWhenNoHelperIsAtWork()
      throws Exception {
    Path app = file(temp.resolve("Bücher App/bin/app")).getParent().getParent();
    Path updates = temp.resolve("updates");
    List<Path> left = List.of(
        file(temp.resolve("Bücher App.backup-1/bin/app")).getParent().getParent(),
        file(temp.resolve("Bücher App.new-2/bin/app")).getParent().getParent(),
        file(updates.resolve("helper-3/runtime/bin/java"))
            .getParent()
            .getParent()
            .getParent());
    List<Path> kept = List.of(
        file(temp.resolve("Bücher App.backup-x/bin/app")),
        file(temp.resolve("Other App.new-2/bin/app")),
        file(temp.resolve("outside/data")));
    // A link in an old version to a folder outside: deleting the version keeps what is there.
    link(left.getFirst().resolve("data"), temp.resolve("outside"));
    Installation installation = new Installation(app, updates, List.of());

    Files.writeString(updates.resolve("plan.properties"), "format=1\n");
    assertEquals(Optional.empty(), installation.result());
    left.forEach(p -> assertTrue(Files.exists(p), p + " is deleted while a helper is at work"));

    Files.writeString(
        updates.resolve("result.properties"),
        "format=1\nstatus=rolled-back\nreason=not-confirmed\nmessage=Back to 1.2.0\n");
    assertEquals(
        Optional.of(new Installation.Result(
            Installation.Result.Status.ROLLED_BACK, "not-confirmed", "Back to 1.2.0")),
        installation.result());
    assertEquals(Optional.empty(), installation.result());
    left.forEach(p -> assertFalse(Files.exists(p), p + " is left"));
    kept.forEach(p -> assertTrue(Files.exists(p), p + " is deleted"));
    assertTrue(Files.exists(app.resolve("bin/app")));
  }

  private static Path file(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.writeString(file, file.getFileName().toString());
  }

  /** A symbolic link, or on Windows a junction, which needs no rights to create. */
  private static void link(Path link, Path target) throws Exception {
    if (File.separatorChar == '\\') {
      Process mklink = new ProcessBuilder(
              "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
          .redirectErrorStream(true)
          .start();
      String out = new String(mklink.getInputStream().readAllBytes());
      assertEquals(0, mklink.waitFor(), out);
    } else {
      Files.createSymbolicLink(link, target);
    }
  }
}
