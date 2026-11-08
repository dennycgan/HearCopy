package com.dennycgan.hearcopy.architecture

import org.junit.Test

/** Main sources keep the fixed package layout (ADR-001). */
class PackageLayoutTest {

    private val basePath = "app/src/main/java/com/dennycgan/hearcopy/"
    private val basePackage = "com.dennycgan.hearcopy"

    private val packages = setOf(
        "capture", "overlay", "transcription", "notify", "state", "data", "ui",
    )

    private val rootFiles = setOf("AppContainer.kt", "HearCopyApplication.kt")

    private val mainSources = Repo.existingFiles.filter {
        it.startsWith("app/src/") &&
            !it.startsWith("app/src/test/") &&
            !it.startsWith("app/src/androidTest/") &&
            (it.endsWith(".kt") || it.endsWith(".java"))
    }

    @Test
    fun `every main source file lives under the package root`() {
        val violations = mainSources
            .filterNot { it.startsWith(basePath) }
            .map { "$it (outside $basePath)" }
        assertNoViolations("Main sources must live under $basePath:", violations)
    }

    @Test
    fun `package root holds only allowlisted files and packages`() {
        val violations = mainSources
            .filter { it.startsWith(basePath) }
            .mapNotNull { path ->
                val relative = path.removePrefix(basePath)
                val top = relative.substringBefore('/')
                when {
                    relative.contains('/') && top !in packages ->
                        "$path (package '$top' is not allowlisted)"
                    !relative.contains('/') && relative !in rootFiles ->
                        "$path (root file is not allowlisted)"
                    else -> null
                }
            }
        assertNoViolations(
            "Package root may hold only $rootFiles and packages $packages:",
            violations,
        )
    }

    @Test
    fun `package declaration matches the directory`() {
        val packageLine = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val violations = mainSources
            .filter { it.startsWith(basePath) && it.endsWith(".kt") }
            .mapNotNull { path ->
                val expected = (basePackage + "/" + path.removePrefix(basePath))
                    .substringBeforeLast('/')
                    .replace('/', '.')
                val declared = packageLine.find(Repo.file(path).readText())?.groupValues?.get(1)
                if (declared == expected) null else "$path (declares '$declared', expected '$expected')"
            }
        assertNoViolations("Package declarations must match directories:", violations)
    }
}
