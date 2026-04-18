package com.brewingcoder.oc2.platform.network

/**
 * Boundary a script uses for `network.*` calls. The BE provides the production
 * impl that talks to [NetworkInboxes] + [com.brewingcoder.oc2.platform.ChannelRegistry];
 * tests can pass [NOOP] or a fake.
 *
 * Rule B: scripts never see the BE directly — they see this seam.
 */
interface NetworkAccess {
    /** Identity of the host computer. -1 means "no id assigned" (only true for [NOOP]). */
    fun id(): Int

    /**
     * Deliver [message] to every other computer registered on [channel]. When
     * [channel] is null, broadcasts on the host's own channel. Self-exclusion is
     * the impl's responsibility. Oversized messages drop silently (see
     * [NetworkInboxes.MAX_MESSAGE_BYTES]).
     */
    fun send(message: String, channel: String? = null)

    /** Pop the oldest pending message from the host's inbox, or null. */
    fun recv(): NetworkInboxes.Message?

    /** Peek without removing. */
    fun peek(): NetworkInboxes.Message?

    /** Pending message count for the host. */
    fun size(): Int

    companion object {
        /** Inert default — used by tests and ScriptEnv's default. */
        val NOOP: NetworkAccess = object : NetworkAccess {
            override fun id(): Int = -1
            override fun send(message: String, channel: String?) {}
            override fun recv(): NetworkInboxes.Message? = null
            override fun peek(): NetworkInboxes.Message? = null
            override fun size(): Int = 0
        }
    }
}
