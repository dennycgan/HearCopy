package com.dennycgan.hearcopy.architecture

import org.junit.Test

/**
 * User-visible copy comes only from string resources, and colour literals
 * live only in ui/theme/ and res/values/colors.xml (ADR-001).
 *
 * Copy is checked at user-visible call sites only. A literal assigned to a
 * variable first and passed on later is not detected.
 */
class CopyAndThemeTest {

    private val literal = "\""

    private val copyCallSites = listOf(
        Regex("""\bText\s*\(\s*(text\s*=\s*)?$literal"""),
        Regex("""\b(setContentTitle|setContentText|setSubText|setTicker|bigText|setText|setHint|setTitle|setContentDescription|showSnackbar)\s*\(\s*$literal"""),
        Regex("""\bToast\.makeText\s*\([^,()]*,\s*$literal"""),
        Regex("""\baddAction\s*\([^,()]*,\s*$literal"""),
        Regex("""\bNotificationChannel\s*\([^,()]*,\s*$literal"""),
        Regex("""\bcontentDescription\s*=\s*$literal"""),
        Regex("""\.(text|hint)\s*=\s*$literal"""),
    )

    private val colourLiterals = listOf(
        Regex("""(?<![\w.])Color\s*\("""),
        Regex("""\bColor\.(Red|Green|Blue|Black|White|Gray|LightGray|DarkGray|Cyan|Magenta|Yellow|Transparent)\b"""),
        Regex("""\bColor\.(BLACK|WHITE|RED|GREEN|BLUE|GRAY|LTGRAY|DKGRAY|CYAN|MAGENTA|YELLOW|TRANSPARENT)\b"""),
        Regex("""\bColor\.(parseColor|rgb|argb|valueOf|pack)\s*\("""),
        Regex("""\b(setBackgroundColor|setTextColor|setColor|setTint|setColorFilter)\s*\(\s*(0x|-?\d)"""),
        Regex("""$literal#[0-9A-Fa-f]{3,8}$literal"""),
    )

    private val themePath = "app/src/main/java/com/dennycgan/hearcopy/ui/theme/"
    private val colorsXml = "app/src/main/res/values/colors.xml"

    private val resourceXml = Repo.existingFiles.filter {
        it.startsWith("app/src/") && it.contains("/res/") && it.endsWith(".xml")
    }

    @Test
    fun `no string literals at user-visible call sites in main sources`() {
        val violations = Repo.mainKotlinFiles.flatMap { path ->
            val code = stripComments(Repo.file(path).readText())
            copyCallSites.flatMap { regex ->
                regex.findAll(code).map { "$path:${lineOf(code, it.range.first)}: ${it.value.trim()}" }
            }
        }
        assertNoViolations("User-visible copy must come from strings.xml:", violations)
    }

    @Test
    fun `xml copy attributes are string resources`() {
        val attribute = Regex("""\b(android|app):(text|hint|title|contentDescription|label)\s*=\s*"([^"]*)"""")
        val violations = resourceXml.flatMap { path ->
            val text = Repo.file(path).readText()
            attribute.findAll(text)
                .filterNot { match ->
                    val value = match.groupValues[3]
                    value.isEmpty() || value == "@null" || value.startsWith("@string/")
                }
                .map { "$path:${lineOf(text, it.range.first)}: ${it.value}" }
        }
        assertNoViolations("XML copy attributes must reference @string/:", violations)
    }

    @Test
    fun `string resources live only in strings xml`() {
        val element = Regex("""<(string|plurals|string-array)\b""")
        val violations = resourceXml
            .filterNot { Regex("""/res/values(-[\w-]+)?/strings\.xml$""").containsMatchIn(it) }
            .flatMap { path ->
                val text = Repo.file(path).readText()
                element.findAll(text).map { "$path:${lineOf(text, it.range.first)}: ${it.value}" }
            }
        assertNoViolations("String resources must be declared in values*/strings.xml:", violations)
    }

    @Test
    fun `kotlin colour literals only in ui theme`() {
        val violations = Repo.mainKotlinFiles
            .filterNot { it.startsWith(themePath) }
            .flatMap { path ->
                val code = stripComments(Repo.file(path).readText())
                colourLiterals.flatMap { regex ->
                    regex.findAll(code).map { "$path:${lineOf(code, it.range.first)}: ${it.value.trim()}" }
                }
            }
        assertNoViolations("Colour literals are allowed only in ui/theme/:", violations)
    }

    @Test
    fun `xml colour literals only in colors xml`() {
        val hex = Regex("""(["'>])\s*#[0-9A-Fa-f]{3,8}\s*(["'<])""")
        val violations = resourceXml
            .filterNot { it == colorsXml }
            .flatMap { path ->
                val text = Repo.file(path).readText()
                hex.findAll(text).map { "$path:${lineOf(text, it.range.first)}: ${it.value.trim()}" }
            }
        assertNoViolations("XML colour literals are allowed only in $colorsXml:", violations)
    }
}
