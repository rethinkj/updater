# rethinkj Updater

A library that keeps Java apps up to date on Windows and Linux: it finds new releases (GitHub, GitLab, Gitea and Forgejo, or a JSON manifest on any web server), reads their release notes, downloads and verifies them, and replaces the app's files through a small helper process once the app has exited.

- Repository: https://github.com/rethinkj/updater
- Documentation for users: https://updater.rethinkj.com, its source in `docs/`
- License: MIT

This file holds the rules for working on the repository, and nothing else. What the library does is in the code, its Javadoc and the documentation site. Do not describe features, findings, measurements or your own work here; the file changes only when a rule changes.

## Targets

- Windows 11 or later and Linux, on x64. macOS is not a target.
- Headless: no windows, dialogs, prompts or output on the console. The library hands its callers objects, results and progress through its API, and they decide what to show. Errors are exceptions or results, never something printed.
- Runs on an ordinary JRE, JDK 25. GraalVM native image is not a goal, but it has to stay possible. That means:
  - No `java.desktop`: no AWT, no Swing, no ImageIO.
  - No reflection, dynamic proxies or classes generated at run time in this code.
  - A library this code depends on at run time is added only with a reason in its pull request, and only if it runs in a native image without reflection, or ships the reachability metadata it needs.
- `helper` depends on `java.base` only. It runs from a copy outside the app's folder while it replaces the app's files, so it cannot load anything from there.

## Modules

| Directory | Artifact | Purpose |
| --- | --- | --- |
| `core/` | `rethinkj-updater-core` | Sources, versions and channels, release notes, download and verification, package `com.rethinkj.updater` |
| `helper/` | `rethinkj-updater-helper` | The process that waits for the app to exit, replaces its files, rolls back on failure and starts it again, package `com.rethinkj.updater.helper` |
| `examples/` | not published | Runnable apps, written as a user of the library writes them |
| `docs/` | not a Maven module | The documentation site, a Node project of its own |

`core` and `helper` do not depend on each other. They talk through the command line and files the helper reads, which are part of the public contract: a newer `core` must work with the helper an installed app already has.

## Build

Requires JDK 25 and Maven 3.9+.

```sh
mvn verify                        # build and test everything
mvn -pl helper -am verify         # one module plus the modules it depends on
mvn spotless:apply                # format; mvn verify fails on a file that is not formatted
```

`mvn verify` also fails on a finding of PMD, or of its copy-paste detector within a module, with the rules in `config/pmd/rules.xml`. A finding the code cannot avoid gets `// NOPMD: <why>` on its line. `config/pmd/baseline.properties` lets the classes listed there off the design rules named beside them; it only shrinks.

The site, with Node 24 in `docs/`: `npm ci`, then `npm run build`.

## Tests

- Keep tests few and fast. A test earns its place by failing when behaviour a user can notice breaks. No benchmarks and no timing printouts in the test suite, no tests of tests, and no second test for what another already covers.
- The default build does not use the network. Sources are tested against a local HTTP server that answers with recorded responses under `<module>/src/test/resources`. Tests against the real services run only with `-Dnetwork.tests=true`.
- Replacing files is tested on both platforms, in a temporary folder: a locked file on Windows, a failure halfway through, paths with spaces and non-ASCII characters. The CI builds on Linux and Windows for this reason.

## Rules the code does not show

- An update never leaves an app half replaced. A download goes to a temporary file and is verified before anything in the app's folder changes; the helper either finishes the replacement or restores what was there.
- A download whose checksum is unknown or does not match is never applied. If the app configures a public key, an update without a valid signature is not applied either.
- Shared build configuration belongs in the root `pom.xml`. The version is changed with `mvn versions:set -DnewVersion=<version>`.

## Documentation

- The site in `docs/` is the documentation, for people and agents. Keep it short and current: a change that users notice updates the page it concerns in the same pull request.
- Nothing on a page is written by hand that the build could check: Java code is a snippet from `examples/src/test/java/com/rethinkj/updater/examples/docs`. `docs/scripts/docs.mjs` fails the build on what does not exist.
- What changed goes into the changelog (below). Plans, status, findings and reports go into the issue tracker, never into the repository: no `PLAN.md`, `SUMMARY.md`, `VALIDATION.md` or review notes, and nothing of the kind in this file.

## Changes

- Nobody pushes to `main`. Work on a branch from `origin/main`, push it and open a pull request on GitHub. It is merged by squash once its checks pass.
- One pull request per topic, small enough to review. Its description says what changed and why, in a few sentences.
- Commit messages and pull request titles follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <summary>`, for example `feat(core): read GitHub releases`. The scope is the module when the change stays in one. A breaking change gets `!` before the colon. The subject has at most 72 characters; a body only when the diff does not show why.
- A change that users of the library can notice (new or changed API, changed behaviour, a fix, a raised requirement) adds `changelog/unreleased/<YYYY-MM-DD>-<slug>.md` with one top-level bullet written for those users. These files are folded into `CHANGELOG.md` at release.

## This repo is public

Everything pushed stays public in history. Before committing, run `git diff --cached | grep -nE '/home/|/Users/|/private/var/|\.ts\.net|ses_[A-Za-z0-9]{20}'` and keep these out:

- secrets, tokens and credential file contents, also in recorded HTTP responses and test keys that are not marked as such;
- home folder and temp paths, machine names and tailnet hostnames. Write repo-relative paths, and use `host.example.ts.net` in tests;
- session IDs, chat or Slack links, and quotes from private sessions, chats or calls;
- issue numbers, links and suggested branch names from a private issue tracker;
- teammates' names attached to their plans, opinions or internal discussions, and private repos or branches;
- quotes of, or citations to, internal guides and rules. Say what the code does and why on its own terms;
- agent handoff notes, raw evidence, logs and benchmark dumps.
