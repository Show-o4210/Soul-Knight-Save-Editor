package com.example.soul_knight_save_editor.unlock

/** Confirmed formats: numeric IDs and the 16-character lowercase hex ID observed in vivo 8.6.0.
 * Preserve the token exactly; never convert it to a number or infer identity from a default file. */
object SaveAccountId {
    const val TOKEN_PATTERN = "(?:[0-9]+|[0-9a-f]{16})"
    val roleUnlock = Regex("^(?:($TOKEN_PATTERN)_)?c(\\d+)_unlock$")
    val skinUnlock = Regex("^(?:($TOKEN_PATTERN)_)?c(\\d+)_skin(\\d+)$")
}
