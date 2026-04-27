package com.brewingcoder.oc2.platform.peripheral

/**
 * Script-facing surface for a Tier-2 Control Plane block. Scripts get a handle
 * via `peripheral.find("controlplane")` from a Computer on the same channel.
 *
 * The Control Plane runs a Sedna RV64 Linux VM in-world; this interface is the
 * narrow seam between the host (which steps the VM on the server tick) and
 * scripts (which want lifecycle control + the console buffer for status surfaces).
 *
 * Until firmware ships, the practical use is "is the VM ticking, how many cycles
 * has it accumulated, what did it print recently". Once a kernel lands the same
 * surface gives scripts a way to read kernel banners + boot-time messages
 * without needing a pseudoterminal.
 *
 * Intentionally NOT exposed:
 *   - direct RAM read/write (would let scripts bypass the VM's MMU + leak BootInfo)
 *   - direct disk read/write (would let scripts bypass the guest filesystem)
 *   - sending bytes TO the VM's stdin (needs a real terminal session, not random
 *     `write()` calls from arbitrary scripts)
 *
 * All of those are in scope for a future "console session" peripheral that
 * implements proper attach/detach semantics. This one is the read-mostly
 * status surface.
 */
interface ControlPlanePeripheral : Peripheral {
    override val kind: String get() = "controlplane"

    /** User-assigned name (or `controlplane` fallback). Used by `peripheral.find(kind, name)`. */
    override val name: String

    /** Cumulative CPU cycle count since VM boot. Resets on power-cycle. */
    fun cycles(): Long

    /** Whether the VM is currently ticking (`true`) or suspended (`false`). */
    fun isPowered(): Boolean

    /**
     * Flip power state. Returns the new state. Powering off closes the live VM
     * (releasing RAM + disk FD); powering on lets the next server tick lazy-boot
     * a fresh VM that re-attaches the same disk image.
     */
    fun togglePower(): Boolean

    /**
     * Last [maxLines] lines of UART16550A output the VM has produced. Empty
     * lines are dropped; CR is stripped. Bounded ring (4 KB), so a chatty kernel
     * will lose its earliest output as the buffer fills.
     */
    fun consoleTail(maxLines: Int): List<String>

    /** Wipe the console capture ring. Doesn't affect the running VM's stdout. */
    fun consoleClear()

    /** Disk image capacity in bytes (`0` if no disk attached). */
    fun diskCapacity(): Long

    /** Human-readable single-line summary — RAM, disk, cycles, power state. */
    fun describe(): String
}
