package com.brewingcoder.oc2.platform.os

import com.brewingcoder.oc2.platform.os.commands.DefaultCommands
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end shell tests. Exercises the full default command set through
 * [Shell.execute] against an [InMemoryMount] — same path the BlockEntity
 * takes at runtime, just with a fake mount instead of disk-backed.
 */
class ShellTest {

    private lateinit var shell: Shell
    private lateinit var session: ShellSession
    private lateinit var mount: WritableMount

    @BeforeEach
    fun setup() {
        shell = DefaultCommands.build()
        mount = InMemoryMount(capacityBytes = 1024L)
        session = ShellSession(
            mount = mount,
            metadataProvider = { ShellMetadata(computerId = 7, channelId = "alpha", location = "(1,2,3)") },
        )
    }

    @Test
    fun `empty input is a no-op`() {
        val r = shell.execute("", session)
        r.lines shouldBe emptyList()
        r.exitCode shouldBe 0
    }

    @Test
    fun `unknown command returns 127`() {
        val r = shell.execute("nope", session)
        r.exitCode shouldBe 127
        r.lines.first() shouldContain "command not found"
    }

    @Test
    fun `help lists all registered commands`() {
        val r = shell.execute("help", session)
        r.exitCode shouldBe 0
        val joined = r.lines.joinToString("\n")
        for (cmd in listOf("ls", "cd", "pwd", "mkdir", "rm", "cat", "write", "echo", "help", "clear", "id", "df")) {
            joined shouldContain cmd
        }
    }

    @Test
    fun `echo prints args joined with spaces`() {
        shell.execute("echo hello world", session).lines shouldBe listOf("hello world")
    }

    @Test
    fun `echo respects double-quoted args`() {
        shell.execute("""echo "a b c"""", session).lines shouldBe listOf("a b c")
    }

    @Test
    fun `pwd starts at root`() {
        shell.execute("pwd", session).lines shouldBe listOf("/")
    }

    @Test
    fun `mkdir then cd then pwd round-trips`() {
        shell.execute("mkdir foo", session).exitCode shouldBe 0
        shell.execute("cd foo", session).exitCode shouldBe 0
        shell.execute("pwd", session).lines shouldBe listOf("/foo")
    }

    @Test
    fun `cd dotdot moves to parent`() {
        shell.execute("mkdir foo", session)
        shell.execute("cd foo", session)
        shell.execute("mkdir bar", session)
        shell.execute("cd bar", session)
        shell.execute("pwd", session).lines shouldBe listOf("/foo/bar")
        shell.execute("cd ..", session)
        shell.execute("pwd", session).lines shouldBe listOf("/foo")
    }

    @Test
    fun `cd with no args returns to root`() {
        shell.execute("mkdir foo", session)
        shell.execute("cd foo", session)
        shell.execute("cd", session)
        shell.execute("pwd", session).lines shouldBe listOf("/")
    }

    @Test
    fun `cd to nonexistent dir errors and stays put`() {
        val r = shell.execute("cd doesnotexist", session)
        r.exitCode shouldBe 1
        r.lines.first() shouldContain "no such directory"
        shell.execute("pwd", session).lines shouldBe listOf("/")
    }

    @Test
    fun `write then cat round-trips text`() {
        shell.execute("""write hi.txt "hello world"""", session).exitCode shouldBe 0
        shell.execute("cat hi.txt", session).lines shouldBe listOf("hello world")
    }

    @Test
    fun `ls shows files and dirs with mode column`() {
        shell.execute("mkdir adir", session)
        shell.execute("write file.txt content", session)
        val r = shell.execute("ls", session)
        val body = r.lines.drop(3) // skip blank + Mode/---- header rows
        body.size shouldBe 2
        body.any { it.startsWith("d----") && it.endsWith("adir") } shouldBe true
        body.any { it.startsWith("-----") && it.endsWith("file.txt") } shouldBe true
    }

    @Test
    fun `ls in subdir formats files with their sizes`() {
        shell.execute("mkdir foo", session)
        shell.execute("cd foo", session)
        shell.execute("write a hello", session)            // 5 bytes
        shell.execute("write b helloworld", session)       // 10 bytes
        val r = shell.execute("ls", session)
        val body = r.lines.drop(3)
        body.size shouldBe 2
        body[0].startsWith("-----") shouldBe true
        body[0].endsWith("  a") shouldBe true
        body[0].contains("     5") shouldBe true
        body[1].startsWith("-----") shouldBe true
        body[1].endsWith("  b") shouldBe true
        body[1].contains("    10") shouldBe true
    }

    @Test
    fun `ls in empty dir prints (empty) instead of nothing`() {
        shell.execute("mkdir empty", session)
        shell.execute("cd empty", session)
        val r = shell.execute("ls", session)
        r.lines shouldBe listOf("(empty)")
    }

    @Test
    fun `ls of a single file prints that one file`() {
        shell.execute("write only.txt content", session)   // 7 bytes
        val r = shell.execute("ls only.txt", session)
        val body = r.lines.drop(3)
        body.size shouldBe 1
        body[0].startsWith("-----") shouldBe true
        body[0].endsWith("  only.txt") shouldBe true
        body[0].contains("     7") shouldBe true
    }

    @Test
    fun `rm removes a file`() {
        shell.execute("write doomed content", session)
        shell.execute("rm doomed", session).exitCode shouldBe 0
        shell.execute("ls", session).lines shouldBe listOf("(empty)")
    }

    @Test
    fun `rm recursive on a directory`() {
        shell.execute("mkdir dir", session)
        shell.execute("cd dir", session)
        shell.execute("write inner content", session)
        shell.execute("cd ..", session)
        shell.execute("rm dir", session).exitCode shouldBe 0
        shell.execute("ls", session).lines shouldBe listOf("(empty)")
    }

    @Test
    fun `clear sets the clearScreen flag`() {
        val r = shell.execute("clear", session)
        r.clearScreen shouldBe true
        r.lines shouldBe emptyList()
    }

    @Test
    fun `id reports computer metadata`() {
        val r = shell.execute("id", session)
        r.lines.joinToString("\n").let { joined ->
            joined shouldContain "7"
            joined shouldContain "alpha"
            joined shouldContain "(1,2,3)"
        }
    }

    @Test
    fun `df reports capacity and remaining`() {
        shell.execute("write filler XXXXXXXX", session)  // 8 bytes
        val r = shell.execute("df", session)
        val joined = r.lines.joinToString("\n")
        joined shouldContain "1.00 KiB"
        joined shouldContain "used"
        joined shouldContain "free"
    }

    @Test
    fun `metadata is re-read on each execute`() {
        // Simulate the channel changing between commands (BE.setChannel did this)
        var currentChannel = "alpha"
        val liveSession = ShellSession(
            mount = mount,
            metadataProvider = { ShellMetadata(7, currentChannel, "(0,0,0)") },
        )
        shell.execute("id", liveSession).lines.joinToString("\n") shouldContain "alpha"
        currentChannel = "beta"
        shell.execute("id", liveSession).lines.joinToString("\n") shouldContain "beta"
    }

    @Test
    fun `write past capacity reports error`() {
        val small = InMemoryMount(capacityBytes = 4)
        val s = ShellSession(
            mount = small,
            metadataProvider = { ShellMetadata(0, "x", "()") },
        )
        val r = shell.execute("write big AAAAAAAAAA", s)  // 10 bytes into 4-byte cap
        r.exitCode shouldBe 1
        r.lines.first() shouldContain "out of space"
    }

    @Test
    fun `cat on missing file errors`() {
        val r = shell.execute("cat nope", session)
        r.exitCode shouldBe 1
        r.lines.first() shouldContain "no such file"
    }

    @Test
    fun `mkdir creates nested via the parent-aware mount`() {
        val r = shell.execute("mkdir a", session)
        r.exitCode shouldBe 0
        shell.execute("mkdir a/b", session).exitCode shouldBe 0
        val body = shell.execute("ls a", session).lines.drop(3)
        body.size shouldBe 1
        body[0].endsWith("b") shouldBe true
        body[0].startsWith("d----") shouldBe true
    }
}
