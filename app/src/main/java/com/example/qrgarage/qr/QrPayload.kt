package com.example.qrgarage.qr

import java.util.UUID

/**
 * LOC-2: QR labels encode the location's stable UUID, never its name, so renames and
 * reorganisation never invalidate printed labels.
 *
 * Format v1: `garageinv://loc/<uuid>?v=1`. A missing `v` is read as v1. Labels written by a
 * newer app (v > [CURRENT_VERSION]) are rejected with a clear message instead of guessed at.
 */
object QrPayload {
    const val SCHEME = "garageinv"
    const val CURRENT_VERSION = 1

    sealed interface Parsed {
        data class Location(val id: String) : Parsed
        /** Not a payload, but could be a short code typed or printed under the label. */
        data class ShortCode(val code: String) : Parsed
        data class UnsupportedVersion(val version: Int) : Parsed
        data object NotOurs : Parsed
        data object Malformed : Parsed
    }

    fun forLocation(id: String): String = "$SCHEME://loc/$id?v=$CURRENT_VERSION"

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed.Malformed
        if (!text.startsWith("$SCHEME://", ignoreCase = true)) {
            return if (SHORT_CODE.matches(text)) Parsed.ShortCode(text.uppercase()) else Parsed.NotOurs
        }
        val rest = text.substring(SCHEME.length + 3)
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")
        val version = query.split('&')
            .firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")
            ?.let { it.toIntOrNull() ?: return Parsed.Malformed }
            ?: 1
        if (version > CURRENT_VERSION) return Parsed.UnsupportedVersion(version)

        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size != 2 || segments[0] != "loc") return Parsed.Malformed
        val id = segments[1]
        return if (isUuid(id)) Parsed.Location(id.lowercase()) else Parsed.Malformed
    }

    private fun isUuid(s: String) = runCatching { UUID.fromString(s).toString() == s.lowercase() }
        .getOrDefault(false)

    /** Short codes use an unambiguous alphabet (no 0/O, 1/I). */
    const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 5
    private val SHORT_CODE = Regex("^[$CODE_ALPHABET${CODE_ALPHABET.lowercase()}]{$CODE_LENGTH}$")
}
