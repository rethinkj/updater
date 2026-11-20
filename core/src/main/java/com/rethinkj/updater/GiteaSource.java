package com.rethinkj.updater;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * The releases of a repository on a Gitea or Forgejo instance, such as codeberg.org, read through
 * the instance's REST API.
 *
 * <p>Forgejo's API is Gitea's, and both answer as GitHub's does: this source reads every page of
 * releases, 50 to a request, and leaves out drafts and tags that are not a version, as {@link
 * GitHubSource} does. Neither reports a file's checksum, so a {@link Downloader} verifies a file
 * with a checksum file attached to its release. A token lets a private repository be read; a
 * {@link Downloader} given this source sends it with requests to the instance, never to where the
 * instance redirects.
 */
public final class GiteaSource implements Source {

  private final GitHubSource api;

  /**
   * A repository on a Gitea or Forgejo instance.
   *
   * @param instance the instance's address, such as {@code https://codeberg.org/}, below which its
   *     API is at {@code api/v1/}
   * @param repository owner and name, such as {@code forgejo/forgejo}
   * @param token a token that may read the repository's releases, or {@code null} for none
   * @throws IllegalArgumentException if {@code repository} is not owner/name, {@code instance} is
   *     neither https nor on this machine, or {@code token} has characters other than visible ASCII
   */
  public GiteaSource(URI instance, String repository, String token) {
    // With a slash before the API's path, so that an instance below a path keeps it.
    api = new GitHubSource(
        URI.create(instance.toString().replaceFirst("/*$", "/") + "api/v1/"),
        repository,
        token,
        false);
  }

  @Override
  public List<Release> releases() throws IOException {
    return api.releases();
  }

  /** The Authorization header for {@code uri}, which only the instance gets. */
  String authorization(URI uri) {
    return api.authorization(uri);
  }
}
