# Codacy Reek

This is the docker engine we use at Codacy to have [Reek](https://github.com/troessner/reek) support.
You can also create a docker to integrate the tool and language of your choice!
See the [codacy-engine-scala-seed](https://github.com/codacy/codacy-engine-scala-seed) repository for more information.

## Usage

You can create the docker by doing:

```
sbt universal:stage
docker build -t codacy-reek .
```

The docker is ran with the following command:

```
docker run -it -v $srcDir:/src  <DOCKER_NAME>:<DOCKER_VERSION>
```

## Test

We use the [codacy-plugins-test](https://github.com/codacy/codacy-plugins-test) to test our external tools integration.
You can follow the instructions there to make sure your tool is working as expected.

## Update Version with Docs

```sh
bundle update
bundle config set --local path 'vendor/bundle'
bundle install
./scripts/doc_generate.sh <docs_location>
```

Notes on the documentation generation:
- You need to *require* the plugin, so they are injected on the *registry* when fetching the available Cops
- After the `bundle install` the documentation generator will fetch examples from the Cops of the added plugins

## Agent Playbook: Updating This Repository End-to-End

This section is written for an AI coding agent (or a human) tasked with updating this repo — most commonly bumping the wrapped [Reek](https://github.com/troessner/reek) gem version, but also base image / orb / SDK bumps. Follow it top to bottom; it tells you what to change, how to test locally, and how to interpret CI so you can iterate on failures without guessing.

### 1. What this repository is

This is a **Codacy engine**: a thin Scala wrapper (`src/main/scala/codacy/Engine.scala` and `src/main/scala/codacy/reek/Reek.scala`, built on `codacy-engine-scala-seed`) that shells out to the Ruby gem **Reek** (a static code smell detector for Ruby) and packages the result as a Docker image Codacy's platform can run against a customer's source code. The wrapper itself does not implement any analysis logic — it builds a `reek` CLI command, runs it via `CommandRunner`, and parses the output.

Unlike some other Codacy tool-engines, **there is no `DocGenerator` script and no automated pattern-documentation pipeline in this repo.** `docs/patterns.json` and `docs/description/*` exist (so this *is* a rule-based Codacy "pattern" engine), but there is no Scala object, Rake task, or shell script anywhere in the git history that regenerates them from Reek's source. The one prior "bump-shaped" commit in this repo's history (`da5f377`, "Update rules configuration (#4)") hand-edited `docs/patterns.json` directly. Treat `docs/patterns.json` and `docs/description/*.md`/`description.json` as **hand-maintained files that must be kept in sync manually** against Reek's actual smell/rule set for the target version — there is no generator to run.

Note: the README's older "Update Version with Docs" section above (`bundle update` / `./scripts/doc_generate.sh <docs_location>`) references a `scripts/` directory and generator script that do **not exist** in this repository (verified via `git log --all` and a full file listing) — do not rely on it; follow this playbook instead.

- `docs/patterns.json` — the list of Reek smell detectors ("patterns") Codacy knows about, their parameters/defaults, and which are enabled out of the box. Hand-maintained; includes a top-level `"version"` field that should track the pinned Reek gem version.
- `docs/description/description.json` + `docs/description/*.md` — human-readable titles/descriptions per pattern, used in the Codacy UI. Hand-maintained.
- `docs/multiple-tests/with-config-file/*` and `docs/multiple-tests/without-config-file/*` — fixtures (source + expected `results.xml`/`patterns.xml`) used by `codacy-plugins-test`'s "multiple" test mode. There is no `docs/tests/*` (single-file pattern/json test) directory in this repo — only the multiple-tests mode is exercised, matching `.circleci/config.yml`'s `run_multiple_tests: true`.
- `docs/tool-description.md` — short blurb about the tool, hand-maintained.

### 2. Files that encode versions — check all of these on every update

| File | What it controls | What to check |
|---|---|---|
| `Gemfile` / `Gemfile.lock` → `reek` gem | The actual Reek version bundled and run inside the Docker image | `Gemfile` declares `gem "reek"` unpinned, so **`Gemfile.lock` is the real version pin**. Bump by running `bundle update reek` (regenerates the lockfile) rather than hand-editing it, then review the resulting lockfile diff. |
| `docs/patterns.json` → top-level `"version"` | Which Reek version the declared pattern set claims to correspond to | Update this string to match the new Reek version, and reconcile the `patterns` array against Reek's actual smell detectors for that version (check Reek's CHANGELOG/`lib/reek/smells` for added/removed/renamed detectors). |
| `docs/description/description.json` + `docs/description/*.md` | Human-readable text shown in the Codacy UI per pattern | Add/remove/update entries to stay in sync with any pattern changes made in `docs/patterns.json`. |
| `Dockerfile` → `BASE_IMAGE` (`ruby:3.2-alpine3.21`) | Ruby runtime the `reek` gem executes under | Bump only if the target Reek version raises its minimum Ruby requirement, or if asked explicitly. |
| `Dockerfile` → `openjdk17-jre` (apk package) | JRE the compiled Scala wrapper (`Engine`) runs under | Independent of Reek; only touch if asked to change the JVM runtime. |
| `build.sbt` → `codacy-engine-scala-seed` / `scalaVersion` / other library versions | Codacy's engine SDK and other Scala dependencies | Not tied to Reek bumps; check only if explicitly asked to update the SDK. |
| `.circleci/config.yml` → `codacy/base` orb (`codacy/base@12.1.5`) | Shared CircleCI steps (checkout, versioning, sbt build, docker publish, tagging) | Check `git log -p .circleci/config.yml` for prior bump history as a fallback reference if you need to check for newer orb versions. |
| `.circleci/config.yml` → `codacy/plugins-test` orb (`codacy/plugins-test@2.0.11`) | Runs `codacy-plugins-test` in CI after the image is built | Same as above. |
| `project/build.properties` / `project/plugins.sbt` | sbt version / `codacy-sbt-plugin` version | Rarely needs touching; check only if the build itself fails to load. |

### 3. Step-by-step update procedure

1. **Bump the Reek version.** Run `bundle update reek` (requires Ruby + Bundler locally) to regenerate `Gemfile.lock` with the new version and its transitive dependency versions. Do not hand-edit the lockfile's hashes/versions unless bundler is unavailable.
2. **Reconcile `docs/patterns.json` and `docs/description/*`** against Reek's real smell-detector set for the new version — update the `"version"` field, add/remove/rename patterns and their descriptions as needed. There is no generator to lean on here; this is a manual diff against Reek's own docs/changelog/source.
3. **Build and format the Scala wrapper:** `sbt "scalafmtCheckAll; test:scalafmt; scalafmt; universal:stage"` (this mirrors the CI `publish_docker_local` job).
4. **Build the Docker image:** `docker build -t codacy-reek .`
5. **Run `codacy-plugins-test` locally** before pushing — clone https://github.com/codacy/codacy-plugins-test and run its "multiple" DockerTest command against your local image tag (this repo only uses the multiple-tests fixtures, not single pattern/json tests).
6. **Iterate on any fixture mismatches** in `docs/multiple-tests/with-config-file/results.xml` / `docs/multiple-tests/without-config-file/results.xml` if the new Reek version changes output for the sample code, re-running the test after each fix.
7. **Commit** the version bump(s) together with any updated `docs/` files in one change.
8. **Push and open a PR.** CI runs `checkout_and_version` -> `publish_docker_local` (scalafmt + build + docker build) -> `plugins_test` (multiple tests) -> `publish_docker` (master only) -> `tag_version`.
9. **Poll the PR's real CI checks until they all pass — local validation is NOT the finish line.** After every push, run `gh pr checks <pr-url>` and keep re-polling (short sleep while any check is `pending`) until all checks finish. If a check fails, fetch its actual log (CircleCI API/UI for the failing job — don't guess), find the true root cause, fix it, push again (never `--no-verify`, never force-push), and re-poll. Repeat until every check is green. The CI environment's toolchain can differ from your local one, so a clean local run does not guarantee CI passes. Only stop iterating when every check passes, or you hit a genuine product/infra decision that needs a human — in which case explain it in the PR rather than guessing.

### 4. Definition of done

- Reek version bump reflected in `Gemfile.lock` (and `Dockerfile`'s base image, if required by the new version).
- `docs/patterns.json` and `docs/description/*` manually reconciled with the new pattern set, with fixture inconsistencies in `docs/multiple-tests/*` resolved.
- Local `sbt` build/format commands pass.
- Docker image builds successfully.
- `codacy-plugins-test`'s multiple-tests mode passes locally against the freshly built image.
- **After pushing and opening/updating the PR, every CI check on it is green.** Poll `gh pr checks <pr-url>` and iterate on any failure (fetch the real CI log, fix, push, re-poll) until all pass — a passing local build is not sufficient, because the CI toolchain can differ from your local one (see step 9).

## What is Codacy?

[Codacy](https://www.codacy.com/) is an Automated Code Review Tool that monitors your technical debt, helps you improve your code quality, teaches best practices to your developers, and helps you save time in Code Reviews.

### Among Codacy’s features:

- Identify new Static Analysis issues
- Commit and Pull Request Analysis with GitHub, BitBucket/Stash, GitLab (and also direct git repositories)
- Auto-comments on Commits and Pull Requests
- Integrations with Slack, HipChat, Jira, YouTrack
- Track issues in Code Style, Security, Error Proneness, Performance, Unused Code and other categories

Codacy also helps keep track of Code Coverage, Code Duplication, and Code Complexity.

Codacy supports PHP, Python, Ruby, Java, JavaScript, and Scala, among others.

### Free for Open Source

Codacy is free for Open Source projects.
