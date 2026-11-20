package com.rethinkj.updater.examples.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.updater.GiteaSource;
import com.rethinkj.updater.Source;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The sources of the page Sources, read from the real services. */
class SourcesTest {

  @Test
  @EnabledIfSystemProperty(named = "network.tests", matches = "true")
  void readsEveryPageFromGiteaAndForgejo() throws IOException {
    // docs:start gitea-source
    Source source = new GiteaSource(URI.create("https://gitea.com/"), "gitea/runner", null);
    // docs:end gitea-source
    assertTrue(source.releases().size() > 50, "more than one page");
    Source codeberg = new GiteaSource(URI.create("https://codeberg.org/"), "forgejo/forgejo", null);
    assertTrue(codeberg.releases().size() > 50, "more than one page");
  }
}
