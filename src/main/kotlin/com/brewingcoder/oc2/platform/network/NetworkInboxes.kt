package com.brewingcoder.oc2.platform.network

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-computer message inboxes for `network.send` / `network.recv`.
 *
 * Server-scoped singleton (cleared by [com.brewingcoder.oc2.storage.OC2ServerContext]
 * on ServerStoppingEvent). Restart-transient by design — no NBT, no sidecar
 * file. Picking up messages depends on the recipient's BE being loaded; if it
 * is, messages enqueue; if not, the broadcast loses that recipient.
 *
 * Bounded per-inbox: at [INBOX_CAP] messages, oldest drops. Per-message size
 * capped at [MAX_MESSAGE_BYTES] to keep memory pressure predictable.
 *
 * Thread-safety: every public op is `@Synchronized` on the per-inbox queue.
 * Send/recv from the worker thread is safe.
 */
object NetworkInboxes {

    /** Inbox cap — older messages drop when this is exceeded. */
    const val INBOX_CAP: Int = 32

    /** Per-message size cap (UTF-8 bytes). Oversized messages silently drop. */
    const val MAX_MESSAGE_BYTES: Int = 4096

    /** A queued network message. */
    data class Message(val from: Int, val body: String)

    private val inboxes: ConcurrentHashMap<Int, ArrayDeque<Message>> = ConcurrentHashMap()

    /**
     * Optional global delivery hook — fires ALSO (in addition to the inbox
     * queue) on every successful [deliver]. Set by the BE layer at mod init
     * to fan messages out as `network_message` script events. Platform-pure:
     * this object never imports the BE; it just calls the hook by reference.
     */
    @JvmStatic
    var onDelivery: ((computerId: Int, msg: Message) -> Unit)? = null

    /**
     * Deliver [msg] into the inbox for [computerId]. Caller is responsible for
     * channel filtering and self-exclusion — this just queues.
     */
    fun deliver(computerId: Int, msg: Message) {
        if (msg.body.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return
        val q = inboxes.computeIfAbsent(computerId) { ArrayDeque() }
        synchronized(q) {
            q.addLast(msg)
            while (q.size > INBOX_CAP) q.removeFirst()
        }
        onDelivery?.invoke(computerId, msg)
    }

    /** Pop + return the oldest message in [computerId]'s inbox, or null. */
    fun pop(computerId: Int): Message? {
        val q = inboxes[computerId] ?: return null
        return synchronized(q) { q.removeFirstOrNull() }
    }

    /** Peek at the next message without removing. */
    fun peek(computerId: Int): Message? {
        val q = inboxes[computerId] ?: return null
        return synchronized(q) { q.firstOrNull() }
    }

    /** Number of pending messages in [computerId]'s inbox. */
    fun size(computerId: Int): Int = inboxes[computerId]?.size ?: 0

    /** Clear ALL inboxes — called on ServerStopping. */
    fun clearAll() {
        inboxes.clear()
    }

    /** Test hook — same as [clearAll]. */
    internal fun resetForTest() = clearAll()
}
