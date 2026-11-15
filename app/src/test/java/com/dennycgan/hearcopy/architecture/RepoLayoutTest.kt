package com.dennycgan.hearcopy.architecture

import org.junit.Test

/** Repo root and docs/ hold only allowlisted entries (ADR-001, ADR-016, ADR-022). */
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
        "CLAUDE.md",
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

    /** README images: files directly in docs/images/ with one of these extensions. */
    private val docsImagesDir = "docs/images/"
    private val docsImageExtensions = setOf("svg", "png", "jpg")

    private fun isDocsImage(path: String): Boolean {
        if (!path.startsWith(docsImagesDir)) return false
        val name = path.removePrefix(docsImagesDir)
        return '/' !in name &&
            name.substringBeforeLast('.', "").isNotEmpty() &&
            name.substringAfterLast('.', "") in docsImageExtensions
    }

    @Test
    fun `docs holds only the three docs files and README images`() {
        val violations = Repo.files
            .filter { it.startsWith("docs/") && it !in docsFiles && !isDocsImage(it) }
            .map { "$it (not an allowlisted docs file or docs/images/ image)" }
        assertNoViolations(
            "docs/ must hold only ${docsFiles.sorted()} and images in $docsImagesDir ${docsImageExtensions.sorted()}:",
            violations,
        )
    }

    @Test
    fun `nothing under docs private is in the repo`() {
        val violations = Repo.files.filter { it.startsWith("docs/private/") }
        assertNoViolations("docs/private/ must stay ignored:", violations)
    }
}
