package com.dennycgan.hearcopy.architecture

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * No CJK characters in any repo file (ADR-001). Text files must be valid
 * UTF-8, so CJK text in a legacy encoding cannot slip past the scan.
 * Files containing a NUL byte are treated as binary and skipped.
 */
class LanguageTest {

    private val cjkScripts = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
    )

    private fun isCjk(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) in cjkScripts ||
            codePoint in 0x3000..0x303F ||
            codePoint in 0xFF00..0xFFEF

    @Test
    fun `no CJK characters in any repo file`() {
        val violations = Repo.existingFiles.flatMap { path ->
            val bytes = Repo.file(path).readBytes()
            if (bytes.contains(0.toByte())) return@flatMap emptyList()
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return@flatMap listOf("$path (not valid UTF-8)")
            }
            text.lines().mapIndexedNotNull { index, line ->
                val hit = line.codePoints().filter { isCjk(it) }.findFirst()
                if (hit.isPresent) "$path:${index + 1}: U+%04X".format(hit.asInt) else null
            }
        }
        assertNoViolations("CJK characters are not allowed in repo files:", violations)
    }
}
