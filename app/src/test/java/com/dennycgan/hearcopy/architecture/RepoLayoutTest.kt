package com.dennycgan.hearcopy.architecture

import org.junit.Test

/** Repo root and docs/ hold only allowlisted entries (ADR-001). */
class RepoLayoutTest {

    private val rootFiles = setOf(
        ".gitignore",
        ".gitattributes",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "README.md",
        "LICENSE",
        "CHANGELOG.md",
    )

    private val rootDirs = setOf("gradle", "app", "docs", ".github")

    private val docsFiles = setOf(
        "docs/STATE.md",
        "docs/DECISIONS.md",
        "docs/PHASE_HISTORY.md",
    )

    @Test
    fun `repo root holds only allowlisted files and directories`() {
        val violations = Repo.files.mapNotNull { path ->
            val top = path.substringBefore('/')
            val isDir = path.contains('/')
            when {
                isDir && top !in rootDirs -> "$path (root directory '$top/' is not allowlisted)"
                !isDir && top !in rootFiles -> "$path (root file is not allowlisted)"
                else -> null
            }
        }
        assertNoViolations("Repo root entries must be allowlisted:", violations)
    }

    @Test
    fun `docs holds only the three docs files`() {
        val violations = Repo.files
            .filter { it.startsWith("docs/") && it !in docsFiles }
            .map { "$it (not an allowlisted docs file)" }
        assertNoViolations("docs/ must hold only ${docsFiles.sorted()}:", violations)
    }

    @Test
    fun `nothing under docs private is in the repo`() {
        val violations = Repo.files.filter { it.startsWith("docs/private/") }
        assertNoViolations("docs/private/ must stay ignored:", violations)
    }
}
