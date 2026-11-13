# Extended Desktop — GitHub Actions Debug APK & Release CI Plan

Automated Android CI and Release workflow for **Extended Desktop** that validates JUnit/Robolectric tests, signs with an ephemeral CI debug keystore, builds an installable debug APK, publishes downloadable workflow artifacts, and creates direct-download GitHub Releases on version tags.

## User Review & Critical Decisions

> [!IMPORTANT]
> Revised to incorporate **automatic GitHub Release publishing on `v*` version tags** with direct, unzip-free `.apk` downloads for Android devices. Approve this plan to write the workflow file into the project and output release & usage instructions.

- **Confirmed Decision 1 (Repository Storage & Docs)**: Save the workflow YAML directly inside the project repository so GitHub exports and pushes include it automatically, paired with concise trigger and APK/Release download instructions.
- **Confirmed Decision 2 (Workflow Triggers & Tag Releases)**: Trigger on pushes and pull requests to `main`, manual runs (`workflow_dispatch`), and version tags matching `v*` (for automatic GitHub Releases).
- **Confirmed Decision 3 (Fail-Fast Testing & Reports)**: Run JUnit and Robolectric unit tests before assembling the APK, stop immediately on test failure, and upload HTML/XML test reports only when failures occur (`if: failure()`).
- **Confirmed Decision 4 (Direct-Install Release Publishing)**: Grant `contents: write` job permissions and, on `v*` tag builds, copy the compiled debug APK to `extended-desktop-<tag>-debug.apk` and publish a GitHub Release with auto-generated release notes via `softprops/action-gh-release@v2`.

## 1. Overview & Core Concept

- **What It Does**: Compiles the pure native Android (Kotlin + Jetpack Compose + AIDL) single-module `:app` project on `ubuntu-latest` runners using JDK 21 (Temurin), Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, and Android SDK 36.
- **Target Audience**: Project maintainers and testers needing automated PR verification, quick Actions build artifacts, and one-tap `.apk` downloads directly from the GitHub Releases tab on Android devices.
- **Key Value**: Solves clean-runner keystore absence, prevents broken builds from shipping as artifacts or releases, and removes `.zip` extraction friction on mobile browsers by attaching a versioned `.apk` to each tagged Release.

## 2. CI Pipeline Flow & Developer Experience

- **Trigger & Concurrency**: Runs automatically on `main` pushes/PRs, `v*` tag pushes, and manual dispatches while cancelling superseded in-progress runs per ref.
- **Signing Bootstrap**: Checks for the git-ignored debug keystore before Gradle executes and generates an ephemeral PKCS12 keystore (`android` / `androiddebugkey`) via `keytool` when absent.
- **Outcome Visibility**:
  - *Branch / PR / Manual Green Run*: Tests pass, debug APK compiles, and `extended-desktop-debug-apk` is uploaded to **Actions → Workflow Run → Artifacts**.
  - *Version Tag (`v*`) Green Run*: Same verification and artifact upload, plus a versioned `extended-desktop-<tag>-debug.apk` attached directly to a new **GitHub Release** with generated release notes.
  - *Red Run*: Failing tests halt APK assembly/release creation and upload HTML/XML reports for troubleshooting.

## 3. Key Technical Decisions & Trade-Offs

- **Decision 1: Tag-Gated Release Publishing with Scoped Permissions**
  - *Chosen Approach*: Set `permissions: contents: write` on the build job and gate the APK copy/rename step plus `softprops/action-gh-release@v2` behind `startsWith(github.ref, 'refs/tags/v')`.
  - *Why*: Keeps normal branch and PR runs clean (publishing build artifacts only) while turning any `v*` tag push into an instant, mobile-friendly Release page with zero `.zip` wrapper.
  - *Alternatives Considered*: Separate release-only workflow duplicating setup and Gradle test/build steps (rejected to avoid double CI runtime and configuration drift).

- **Decision 2: Ephemeral Debug Keystore Generation Before Gradle**
  - *Chosen Approach*: Keep `debug.keystore` untracked in git and synthesize it via JDK 21's `keytool -genkeypair` before Gradle runs.
  - *Why*: Matches `app`'s explicit `debugConfig` signing credentials without polluting repository history.
  - *Alternatives Considered*: Committing `debug.keystore` (conflicts with `.gitignore`) or base64 secret injection (unnecessary complexity for debug builds).

- **Decision 3: Wrapper Execution with Safe Export Bootstrap**
  - *Chosen Approach*: Use `gradle/actions/setup-gradle@v4` with `gradle-version: '9.3.1'` for caching and fallback availability, ensure `chmod +x` when the wrapper exists, and bootstrap it cleanly if exported without the wrapper script.
  - *Why*: Guarantees reliable execution across standard clones and AI Studio ZIP exports.
  - *Alternatives Considered*: Assuming `./gradlew` is always present and executable (brittle on fresh exports).

## 4. Pipeline Architecture Diagram *(Technical Reference)*

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Triggers: push(main, tags: v*) · pull_request(main) · workflow_dispatch │
│ Runner: ubuntu-latest · Permissions: contents:write · Concurrency lock  │
└────────────────────────────────────┬────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. Environment, Signing & Wrapper Bootstrap                             │
│    • Checkout code (actions/checkout@v4)                                │
│    • Provision JDK 21 Temurin (actions/setup-java@v4)                   │
│    • Configure Gradle 9.3.1 & cache (gradle/actions/setup-gradle@v4)    │
│    • Generate ephemeral debug keystore via keytool if missing           │
│    • chmod +x wrapper (or bootstrap Gradle 9.3.1 wrapper if absent)     │
└────────────────────────────────────┬────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. Fail-Fast Test Gate                                                  │
│    • Run :app:testDebugUnitTest --stacktrace                            │
│    └─► On Failure: upload HTML/XML reports (actions/upload-artifact@v4) │
└────────────────────────────────────┬────────────────────────────────────┘
                                     ▼ (only if tests pass)
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. Debug APK Assembly & Artifact Upload                                 │
│    • Run :app:assembleDebug --stacktrace                                │
│    • Upload app-debug.apk artifact (extended-desktop-debug-apk)         │
└────────────────────────────────────┬────────────────────────────────────┘
                                     ▼ (only if refs/tags/v*)
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. Versioned GitHub Release Publishing                                  │
│    • Rename copy -> extended-desktop-<tag>-debug.apk                    │
│    • Create Release + notes + attach APK (softprops/action-gh-release)  │
└─────────────────────────────────────────────────────────────────────────┘
```
