package com.dennycgan.hearcopy.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.Test

/**
 * Import rules 1-8 of the architecture rules (ADR-001). The rule text is
 * kept in the private agent rules; each test name below states its rule.
 *
 * Each rule checks both import directives and fully qualified references in
 * code (comments removed). Kotlin star imports bring in only the members of
 * that exact package, which is how wildcard imports are matched.
 */
class DependencyDirectionTest {

    private val base = "com.dennycgan.hearcopy"

    private val files: List<KoFileDeclaration> = Konsist.scopeFromProduction().files
        .filter { it.packagee?.name?.startsWith(base) == true }

    // Rule 1
    @Test
    fun `state imports no android or androidx`() {
        check(
            rule = "Rule 1: state/ must not use android.* or androidx.*",
            scope = { it.inPackage("state") },
            targets = listOf("android.", "androidx."),
        )
    }

    // Rule 2
    @Test
    fun `MediaProjection and AudioRecord appear only in CaptureService and PlaybackCaptureSource`() {
        val allowed = setOf("capture/CaptureService.kt", "capture/PlaybackCaptureSource.kt")
        val classes = listOf("android.media.projection.MediaProjection", "android.media.AudioRecord")
        val simpleNames = Regex("""\b(MediaProjection|AudioRecord)\b""")
        val violations = files
            .filterNot { file -> allowed.any { file.unixPath().endsWith("/hearcopy/$it") } }
            .flatMap { file ->
                val importHits = file.importNames()
                    .filter { name -> classes.any { importHits(name, it) } }
                    .map { "${file.label()}: import $it" }
                val code = file.code()
                val codeHits = simpleNames.findAll(code)
                    .map { "${file.label()}:${lineOf(code, it.range.first)}: ${it.value}" }
                importHits + codeHits
            }
        assertNoViolations(
            "Rule 2: MediaProjection and AudioRecord only in CaptureService and PlaybackCaptureSource",
            violations,
        )
    }

    // Rule 3
    @Test
    fun `transcription does not depend on capture, overlay or ui`() {
        check(
            rule = "Rule 3: transcription/ must not use capture/, overlay/ or ui/",
            scope = { it.inPackage("transcription") },
            targets = listOf("$base.capture.", "$base.overlay.", "$base.ui."),
        )
    }

    // Rule 4
    @Test
    fun `ui and overlay use capture only through CaptureCommands and never transcription`() {
        val commands = "$base.capture.CaptureCommands"
        check(
            rule = "Rule 4: ui/ and overlay/ may use capture/ only through CaptureCommands, never transcription/",
            scope = { it.inPackage("ui") || it.inPackage("overlay") },
            targets = listOf("$base.capture.", "$base.transcription."),
            exempt = { name -> name == commands || name.startsWith("$commands.") },
        )
    }

    // Rule 5
    @Test
    fun `only data touches Keystore and DataStore`() {
        check(
            rule = "Rule 5: only data/ may use Keystore or DataStore",
            scope = { !it.inPackage("data") },
            targets = listOf("java.security.KeyStore", "android.security.keystore.", "androidx.datastore."),
        )
    }

    // Rule 6
    @Test
    fun `overlay imports no Compose`() {
        check(
            rule = "Rule 6: overlay/ must not use Compose",
            scope = { it.inPackage("overlay") },
            targets = listOf("androidx.compose."),
        )
    }

    // Rule 7
    @Test
    fun `concrete implementations are constructed only in AppContainer`() {
        val concrete = listOf(
            "PlaybackCaptureSource",
            "TranscriptionClient",
            "SettingsRepository",
            "CaptureStateStore",
        )
        val violations = files
            .filterNot { it.unixPath().endsWith("/hearcopy/AppContainer.kt") }
            .flatMap { file ->
                val code = file.code()
                concrete.flatMap { name ->
                    val calls = Regex("""(?<![\w.])$name\s*\(""").findAll(code)
                        .filterNot { code.substring(0, it.range.first).trimEnd().endsWith("class") }
                    val references = Regex("""::\s*$name\b""").findAll(code)
                    (calls + references).map { "${file.label()}:${lineOf(code, it.range.first)}: ${it.value.trim()}" }
                }
            }
        assertNoViolations("Rule 7: concrete implementations may be constructed only in AppContainer", violations)
    }

    // Rule 8
    @Test
    fun `transcription and data do not use android util Log`() {
        check(
            rule = "Rule 8: transcription/ and data/ must not use android.util.Log",
            scope = { it.inPackage("transcription") || it.inPackage("data") },
            targets = listOf("android.util.Log"),
        )
    }

    /**
     * Fails when a file in [scope] imports or references any of [targets].
     * A target ending in '.' is a package tree; otherwise it is a class.
     */
    private fun check(
        rule: String,
        scope: (KoFileDeclaration) -> Boolean,
        targets: List<String>,
        exempt: (String) -> Boolean = { false },
    ) {
        val violations = files.filter(scope).flatMap { file ->
            val importViolations = file.importNames()
                .filter { name -> !exempt(name) && targets.any { importHits(name, it) } }
                .map { "${file.label()}: import $it" }
            val code = file.code()
            val referenceViolations = targets.flatMap { target ->
                val pattern = if (target.endsWith(".")) {
                    Regex("""(?<![\w.])${Regex.escape(target)}[\w.]*""")
                } else {
                    Regex("""(?<![\w.])${Regex.escape(target)}(?!\w)[\w.]*""")
                }
                pattern.findAll(code)
                    .filterNot { exempt(it.value) }
                    .map { "${file.label()}:${lineOf(code, it.range.first)}: ${it.value}" }
            }
            importViolations + referenceViolations
        }
        assertNoViolations(rule, violations)
    }

    private fun importHits(import: String, target: String): Boolean {
        if (import.endsWith(".*")) {
            val pkg = import.removeSuffix(".*")
            return if (target.endsWith(".")) {
                "$pkg.".startsWith(target)
            } else {
                target.substringBeforeLast('.') == pkg || pkg == target || pkg.startsWith("$target.")
            }
        }
        return if (target.endsWith(".")) {
            import.startsWith(target)
        } else {
            import == target || import.startsWith("$target.")
        }
    }

    private fun KoFileDeclaration.inPackage(sub: String): Boolean {
        val pkg = packagee?.name.orEmpty()
        return pkg == "$base.$sub" || pkg.startsWith("$base.$sub.")
    }

    private fun KoFileDeclaration.importNames(): List<String> = imports.map { it.name }

    /** Source without comments, import directives or the package directive. */
    private fun KoFileDeclaration.code(): String =
        stripComments(text).lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) "" else line
        }

    private fun KoFileDeclaration.unixPath(): String = path.replace('\\', '/')

    private fun KoFileDeclaration.label(): String = unixPath().substringAfter("/app/src/")
}
