<p align="center">
  <img src="assets/logo.svg" alt="rethinkj" width="96">
</p>

# rethinkj Updater

A library that keeps Java apps up to date on Windows and Linux. It finds new releases on GitHub, GitLab, Gitea and Forgejo or in a JSON manifest on any web server, reads their release notes, downloads and verifies them, and replaces the app's files once the app has exited.

## Status

Little is implemented yet: versions, reading GitHub releases, finding the update, and downloading it with SHA-256 verification. The version is `0.1.0-SNAPSHOT`, published from `main` to Maven Central's snapshot repository; [Installation](https://updater.rethinkj.com/docs/get-started) shows how to depend on it. It targets Windows 11 and Linux on x64, with JDK 25.

## What it is meant to do

It is headless: it shows nothing itself, and hands the app objects (versions, release notes, progress, results) to show the way the app wants.

- **Find updates**: releases from GitHub, GitLab, Gitea and Forgejo, or from a JSON manifest; stable and beta channels; versions compared by SemVer, pre-releases included.
- **Read release notes**: the notes of every version between the installed one and the new one, as Markdown and split into sections such as Added, Fixed and Breaking.
- **Download and verify**: into a temporary file, checked against a SHA-256 checksum and, if the app configures a public key, an Ed25519 signature.
- **Apply**: an archive of the app (zip or tar.gz), a single JAR, or a native installer. A small helper process waits for the app to exit, replaces its files, restores them if anything fails, and starts the app again.

## Modules

| Artifact | What it is |
| --- | --- |
| `rethinkj-updater-core` | Sources, versions, release notes, download and verification |
| `rethinkj-updater-helper` | The process that replaces the app's files and starts it again |

Both share the group `com.rethinkj.updater`.

## Building

Requires JDK 25 and Maven 3.9 or later.

```sh
mvn verify
```

## Documentation

**[updater.rethinkj.com](https://updater.rethinkj.com)** is the documentation for users of the library. Its source is in [`docs/`](docs).

[AGENTS.md](AGENTS.md) holds the rules for working on the library itself. Changes since the last release are under [`changelog/unreleased`](changelog/unreleased).

## License

MIT, see [LICENSE](LICENSE).
