package com.dennycgan.hearcopy.architecture

import java.io.File

/** Shared helpers for the architecture guard tests. */
internal object Repo {

    /** Repository root: the nearest ancestor that holds settings.gradle.kts. */
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Repository root (settings.gradle.kts) not found")

    /**
     * Tracked plus untracked-not-ignored files
     * (`git ls-files --cached --others --exclude-standard`), as
     * repo-relative paths with '/' separators.
     */
    val files: List<String> by lazy {
        val process = ProcessBuilder(
            "git", "ls-files", "--cached", "--others", "--exclude-standard", "-z",
        )
            .directory(root)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ls-files failed" }
        output.split('\u0000').filter { it.isNotEmpty() }.distinct().sorted()
    }

    /** Files from [files] that exist on disk (a tracked file may be deleted locally). */
    val existingFiles: List<String> by lazy { files.filter { file(it).isFile } }

    /** Kotlin files in production source sets of :app (not test or androidTest). */
    val mainKotlinFiles: List<String> by lazy {
        existingFiles.filter {
            it.startsWith("app/src/") &&
                !it.startsWith("app/src/test/") &&
                !it.startsWith("app/src/androidTest/") &&
                it.endsWith(".kt")
        }
    }

    fun file(path: String): File = File(root, path)
}

/**
 * Replaces comments with spaces while keeping line breaks, so offsets still
 * map to the original line numbers. Line comments are cut naively at "//",
 * which can also cut a string such as a URL; that only hides text, it never
 * creates a false match.
 */
internal fun stripComments(source: String): String {
    val noBlocks = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        .replace(source) { match -> match.value.replace(Regex("[^\n]"), " ") }
    return noBlocks.lines().joinToString("\n") { line ->
        val index = line.indexOf("//")
        if (index < 0) line else line.substring(0, index)
    }
}

/** 1-based line number of [offset] in [text]. */
internal fun lineOf(text: String, offset: Int): Int =
    text.substring(0, offset).count { it == '\n' } + 1

/** Fails with one line per violation, or passes when there are none. */
internal fun assertNoViolations(rule: String, violations: List<String>) {
    if (violations.isNotEmpty()) {
        throw AssertionError(
            "$rule\n" + violations.joinToString("\n") { "  - $it" },
        )
    }
}
