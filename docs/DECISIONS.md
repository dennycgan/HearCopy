# HearCopy Decisions

Architecture Decision Records. Append only; old reasoning is never
edited, only the Status line changes.

## ADR-001: Guard tests and DECISIONS are the architecture of record
> The kickoff's architecture and stack are turned into guard tests once, in Cap 0, and from then on only the guards and this log define the architecture.
- Status: Accepted (2026-11-08)
- Anchor: bd394d0
### Context
The kickoff defines packages, import rules, a closed tech stack and
manifest/security constraints, but it is a private, editable product spec,
not something the build can check. It also says the Konsist guards may come
after the first usable APK if they would delay delivery.
### Decision
- The kickoff's package layout, import rules, dependency stack, manifest
  constraints, copy/theme rules and language rule are translated once into
  seven JVM tests under app/src/test/.../architecture/ (RepoLayout,
  PackageLayout, DependencyDirection, DependencyAllowlist, Manifest,
  CopyAndTheme, Language).
- From that commit on, the guard tests plus DECISIONS.md are the
  architecture of record. The kickoff is product reference only; code is
  never moved to "match the kickoff".
- The guards, Konsist included, land in Cap 0, before any feature code,
  instead of after the first usable APK as the kickoff allowed.
- The allowlists cover the whole approved V1 set now: root files
  (README.md, LICENSE, CHANGELOG.md), libraries (kotlinx-coroutines-android,
  kotlinx-coroutines-test, okhttp, mockwebserver3, datastore-preferences,
  lifecycle-runtime-compose) and manifest permissions (INTERNET,
  RECORD_AUDIO, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS). The catalog and
  manifest still hold only what is in use.
- Changing a guard or an allowlist is an architecture change: owner
  approval and a new ADR in the same scope.
### Consequences
- Every feature Cap starts with the boundaries already enforced locally
  and in CI; violations fail `./gradlew test`.
- Later Caps add pre-approved libraries and permissions without an ADR;
  anything outside the V1 set needs one.
- Cap 0 spends some time before the first useful APK; accepted, because the
  guards are cheap while the codebase is empty and costly to retrofit.
- Guards against empty packages pass vacuously in Cap 0; each was shown to
  fail on a deliberate local violation before being committed.
- Guards scan tracked plus untracked-not-ignored files, so a stray file
  fails locally before it is staged.
