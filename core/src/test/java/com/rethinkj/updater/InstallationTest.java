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
    Path beside = folder(temp.resolve("Bücher App.backup-1")); // the user's, or the helper's
    Installation installation = new Installation(app, updates, List.of());

    Files.writeString(updates.resolve("plan.properties"), "format=1\n");
    assertEquals(Optional.empty(), installation.result());
    assertTrue(Files.exists(copy), "deleted while the helper is at work");

    Files.writeString(
        updates.resolve("result.properties"),
        "format=1\nstatus=rolled-back\nreason=not-confirmed\nmessage=Back to 1.2.0\n");
    assertEquals(
        Optional.of(new Installation.Result(
            Installation.Result.Status.ROLLED_BACK, "not-confirmed", "Back to 1.2.0")),
        installation.result());
    assertEquals(Optional.empty(), installation.result());
    assertFalse(Files.exists(copy));
    assertTrue(Files.exists(beside.resolve("file")));
  }

  /** A folder with a file in it. */
  private static Path folder(Path folder) throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("file"), folder.getFileName().toString());
    return folder;
  }
}
