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
  void reportsTheResultOnceAndThenDeletesOnlyWhatTheHelperLeft() throws Exception {
    Path app = folder(temp.resolve("Bücher App"));
    Path updates = folder(temp.resolve("updates"));
    long now = System.currentTimeMillis();
    List<Path> left = List.of(
        folder(temp.resolve("Bücher App.backup-" + now)),
        folder(temp.resolve("Bücher App.new-" + now)),
        folder(updates.resolve("helper-" + now)));
    List<Path> kept = List.of(
        folder(temp.resolve("Bücher App.backup-20261119")), // the user's, older than the plan
        folder(temp.resolve("Other App.new-" + now)),
        folder(temp.resolve("outside")));
    // A link in the old version to a folder outside: deleting the version keeps what is there.
    link(left.getFirst().resolve("data"), temp.resolve("outside"));
    Installation installation = new Installation(app, updates, List.of());

    Files.writeString(updates.resolve("plan.properties"), "format=1\n");
    assertEquals(Optional.empty(), installation.result());
    left.forEach(p -> assertTrue(Files.exists(p), p + " is deleted while a helper is at work"));

    Files.writeString(
        updates.resolve("result.properties"), "format=1\nstatus=applied\nmessage=Replaced\n");
    assertEquals(
        Optional.of(new Installation.Result(Installation.Result.Status.APPLIED, "", "Replaced")),
        installation.result());
    assertEquals(Optional.empty(), installation.result());
    left.forEach(p -> assertFalse(Files.exists(p), p + " is left"));
    kept.forEach(p -> assertTrue(Files.exists(p), p + " is deleted"));
    assertTrue(Files.exists(temp.resolve("outside/file")));
  }

  @Test
  void keepsTheOldVersionIfTheAppsFolderIsMissing() throws Exception {
    Path updates = folder(temp.resolve("updates"));
    Path backup = folder(temp.resolve("Bücher App.backup-" + System.currentTimeMillis()));
    Files.writeString(updates.resolve("plan.properties"), "format=1\n");
    Files.writeString(
        updates.resolve("result.properties"),
        "format=1\nstatus=rejected\nreason=failed\nmessage=The old version is in " + backup + "\n");

    Installation installation = new Installation(temp.resolve("Bücher App"), updates, List.of());

    assertEquals(
        Installation.Result.Status.REJECTED, installation.result().orElseThrow().status());
    assertTrue(Files.exists(backup.resolve("file")));
  }

  /** A folder with a file in it. */
  private static Path folder(Path folder) throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("file"), folder.getFileName().toString());
    return folder;
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
