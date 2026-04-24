package com.brewingcoder.oc2.platform.http

/**
 * Outcome of an [HttpFetcher.fetch] call. Intentionally closed — callers
 * pattern-match to render the result rather than inspect nullable fields.
 */
sealed interface FetchResult {
    data class Ok(val body: ByteArray, val contentType: String?) : FetchResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ok) return false
            return body.contentEquals(other.body) && contentType == other.contentType
        }
        override fun hashCode(): Int = 31 * body.contentHashCode() + (contentType?.hashCode() ?: 0)
    }

    data class Err(val kind: Kind, val message: String) : FetchResult {
        enum class Kind { DISABLED, BAD_KEY, HTTP_STATUS, TOO_LARGE, TIMEOUT, NETWORK }
    }
}
