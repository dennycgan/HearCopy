package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.transcription.TranscriptionClient

/**
 * The key as it is saved: surrounding whitespace trimmed, then the one key
 * rule shared with the transcription client (ADR-012). Null when the
 * trimmed value breaks the rule.
 */
fun normaliseApiKey(raw: String): String? = raw.trim().takeIf { TranscriptionClient.isValidApiKey(it) }
