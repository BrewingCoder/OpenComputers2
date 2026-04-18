package com.brewingcoder.oc2.platform.storage

/**
 * Raised by [Mount] / [WritableMount] operations for any I/O, lookup, or capacity
 * failure. Mount impls wrap underlying [java.io.IOException]s in this so the VM's
 * filesystem host can translate to Lua/JS error semantics in one place.
 */
class StorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
