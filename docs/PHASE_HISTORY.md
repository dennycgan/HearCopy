# HearCopy Phase History

Archive of each closed Cap's phases. Append only.

## Cap 0: Foundation (2026-11-08)

- [x] 0.1 Build skeleton: Gradle wrapper, version catalog, single :app
  module, minimal app that builds and installs (f900cc1, 9381edc)
- [x] 0.2 Architecture guard tests (RepoLayout, PackageLayout,
  DependencyDirection, DependencyAllowlist, Manifest, CopyAndTheme,
  Language); ADR-001 (bd394d0)
- [x] 0.3 CI workflow: build, unit and architecture tests, lint,
  gitleaks, commit-message check through one shared rules script;
  ADR-002 (db901ac)
