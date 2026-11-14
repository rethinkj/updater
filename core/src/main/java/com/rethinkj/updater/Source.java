package com.rethinkj.updater;

import java.io.IOException;
import java.util.List;

/** Where the versions of an app are published, such as {@link GitHubSource}. */
public interface Source {

  /**
   * The published releases, in no particular order. Releases whose tag is not a SemVer version,
   * and drafts, are left out.
   *
   * @throws RateLimitException if the service refuses more requests for now
   * @throws IOException if the service cannot be reached or answers with an error
   */
  List<Release> releases() throws IOException;
}
