package com.dennycgan.hearcopy.architecture

import org.junit.Test

/**
 * The tech stack is closed (ADR-001): every catalog entry is allowlisted and
 * build scripts declare dependencies and plugins only through the catalog.
 * The allowlists cover the approved V1 stack; the catalog holds only what is
 * in use.
 */
class DependencyAllowlistTest {

    private val allowedPlugins = setOf(
        "com.android.application",
        "org.jetbrains.kotlin.plugin.compose",
    )

    private val allowedLibraries = setOf(
        // Cap 0
        "androidx.activity:activity-compose",
        "androidx.compose:compose-bom",
        "androidx.compose.ui:ui",
        "androidx.compose.material3:material3",
        "junit:junit",
        "com.lemonappdev:konsist",
        // Approved for later Caps
        "org.jetbrains.kotlinx:kotlinx-coroutines-android",
        "org.jetbrains.kotlinx:kotlinx-coroutines-test",
        "com.squareup.okhttp3:okhttp",
        "com.squareup.okhttp3:mockwebserver3",
        "androidx.datastore:datastore-preferences",
        "androidx.lifecycle:lifecycle-runtime-compose",
        // Cap 2, test only (ADR-011): JVM stand-in for Android's built-in org.json
        "org.json:json",
    )

    private val catalogPath = "gradle/libs.versions.toml"

    @Test
    fun `every catalog library is allowlisted`() {
        val violations = catalogEntries("libraries").mapNotNull { (alias, value) ->
            val coordinate = libraryCoordinate(value)
            when {
                coordinate == null -> "$alias (cannot read group and name from: $value)"
                coordinate !in allowedLibraries -> "$alias = $coordinate (not allowlisted)"
                else -> null
            }
        }
        assertNoViolations("Catalog libraries must be allowlisted:", violations)
    }

    @Test
    fun `every catalog plugin is allowlisted`() {
        val violations = catalogEntries("plugins").mapNotNull { (alias, value) ->
            val id = pluginId(value)
            when {
                id == null -> "$alias (cannot read plugin id from: $value)"
                id !in allowedPlugins -> "$alias = $id (not allowlisted)"
                else -> null
            }
        }
        assertNoViolations("Catalog plugins must be allowlisted:", violations)
    }

    @Test
    fun `build scripts declare dependencies and plugins only through the catalog`() {
        val checks = listOf(
            Regex("""\b\w*([iI]mplementation|[aA]pi|[cC]ompileOnly|[rR]untimeOnly)\s*\(\s*(?!libs\.|platform\s*\(\s*libs\.|project\s*\()""") to
                "dependency not taken from the catalog",
            Regex(""""[\w.\-]+:[\w.\-]+(:[^"]*)?"""") to "literal dependency coordinate",
            Regex("""\bid\s*\(""") to "plugin applied by id instead of a catalog alias",
            Regex("""\bkotlin\s*\(\s*"""") to "Kotlin plugin applied by id instead of a catalog alias",
            Regex("""\bclasspath\s*\(""") to "buildscript classpath dependency",
            Regex("""\b(files|fileTree)\s*\(""") to "local file dependency",
        )
        val violations = Repo.existingFiles
            .filter { it.endsWith(".gradle.kts") || it.endsWith(".gradle") }
            .flatMap { path ->
                val text = stripComments(Repo.file(path).readText())
                checks.flatMap { (regex, reason) ->
                    regex.findAll(text).map { "$path:${lineOf(text, it.range.first)}: $reason: ${it.value.trim()}" }
                }
            }
        assertNoViolations("Build scripts must use the version catalog only:", violations)
    }

    /** (alias, value) pairs of one TOML table in the catalog. */
    private fun catalogEntries(table: String): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        var current = ""
        Repo.file(catalogPath).readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("[") -> current = line.trim('[', ']').trim()
                current == table && line.contains('=') ->
                    entries += line.substringBefore('=').trim() to line.substringAfter('=').trim()
            }
        }
        return entries
    }

    private fun libraryCoordinate(value: String): String? {
        fun field(name: String) = Regex("""\b$name\s*=\s*"([^"]+)"""").find(value)?.groupValues?.get(1)
        val group = field("group")
        val name = field("name")
        if (group != null && name != null) return "$group:$name"
        field("module")?.let { return it }
        return Regex("""^"([^":]+):([^":]+)(:[^"]*)?"$""").find(value)
            ?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
    }

    private fun pluginId(value: String): String? =
        Regex("""\bid\s*=\s*"([^"]+)"""").find(value)?.groupValues?.get(1)
            ?: Regex("""^"([^":]+)(:[^"]*)?"$""").find(value)?.groupValues?.get(1)
}
