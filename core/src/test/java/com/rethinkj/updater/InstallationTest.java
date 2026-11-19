package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void reportsTheResultOnceAndDeletesTheHelpersCopyOnlyOnceItIsDone() throws Exception {
    Path app = folder(temp.resolve("Bücher App"));
    Path updates = folder(temp.resolve("updates"));
    Path copy = folder(updates.resolve("helper-1"));
    Path beside = folder(temp.resolve("Bücher App.backup-" + System.currentTimeMillis()));
    Path download = folder(updates.resolve("helper-notes")); // the app's, not a copy
    Installation installation = new Installation(app, updates, List.of());

    Files.writeString(updates.resolve("plan.properties"), "format=1\n");
    assertEquals(Optional.empty(), installation.result());
    assertTrue(Files.exists(copy), "deleted while the helper is at work");

    Files.writeString(
        updates.resolve("result.properties"), "format=1\nstatus=applied\nmessage=Replaced\n");
    assertEquals(
        Optional.of(new Installation.Result(Installation.Result.Status.APPLIED, "", "Replaced")),
        installation.result());
    assertEquals(Optional.empty(), installation.result());
    assertFalse(Files.exists(copy));
    assertTrue(Files.exists(beside.resolve("file")), "the helper deletes what it built");
    assertTrue(Files.exists(download.resolve("file")));
  }

  /** A folder with a file in it. */
  private static Path folder(Path folder) throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("file"), folder.getFileName().toString());
    return folder;
  }
}
