package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests for Phase C: the JS-side UI event loop host binding (`__uiRun`),
 * `__uiExit`, and browser-style `setTimeout`/`setInterval`.
 *
 * JS lacks Lua's `os.pullEvent` (requires Rhino continuations). The library's
 * `ui.run(root)` therefore delegates to a host-provided `__uiRun(dispatcher)`
 * which polls the event queue and reenters Rhino to invoke user callbacks.
 */
class JsUiRunTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() = Unit
    }

    private class FakeEnv(
        override val mount: WritableMount = InMemoryMount(),
        override val cwd: String = "",
        override val out: ShellOutput,
        override val events: ScriptEventQueue = ScriptEventQueue(),
    ) : ScriptEnv {
        override fun findPeripheral(kind: String) = null
    }

    @Test
    fun `uiRun dispatches a pre-queued event to the dispatcher and exits`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        // Queue an event BEFORE entering __uiRun; the loop picks it up on the first poll.
        env.events.offer(ScriptEvent("monitor_touch", listOf(1, 2, 3, 4, "scott")))

        val r = RhinoJSHost().eval(
            """
            __uiRun(function(ev) {
              print("ev=" + ev.name + " col=" + ev.args[0] + " player=" + ev.args[4]);
              __uiExit();
            });
            print("done");
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("ev=monitor_touch col=1 player=scott", "done")
    }

    @Test
    fun `setTimeout fires once after delay and uiRun returns`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        val r = RhinoJSHost().eval(
            """
            setTimeout(function() {
              print("timeout fired");
              __uiExit();
            }, 20);
            __uiRun(function(ev) { print("dispatch called: " + ev.name); });
            print("after run");
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // Dispatcher must NOT see the synthetic timer event (intercepted by __uiRun).
        out.lines shouldBe listOf("timeout fired", "after run")
    }

    @Test
    fun `clearTimeout before fire prevents callback`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        val r = RhinoJSHost().eval(
            """
            var id1 = setTimeout(function() { print("should not fire"); }, 100);
            clearTimeout(id1);
            setTimeout(function() {
              print("exit-timer");
              __uiExit();
            }, 30);
            __uiRun(function(ev) {});
            print("after run");
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("exit-timer", "after run")
    }

    @Test
    fun `setInterval fires repeatedly until clearInterval`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        val r = RhinoJSHost().eval(
            """
            var count = 0;
            var id = setInterval(function() {
              count = count + 1;
              if (count >= 3) {
                clearInterval(id);
                __uiExit();
              }
            }, 10);
            __uiRun(function(ev) {});
            print("count=" + count);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines.size shouldBe 1
        out.lines[0] shouldContain "count=3"
    }

    @Test
    fun `external thread offering events wakes the dispatcher`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        // Producer thread: offers three events spaced ~15ms apart, then ends.
        // Script's dispatcher counts them and exits after the third.
        val producer = Thread({
            for (i in 1..3) {
                Thread.sleep(15)
                env.events.offer(ScriptEvent("tick", listOf(i)))
            }
        }, "test-producer").apply { isDaemon = true }
        producer.start()

        val r = RhinoJSHost().eval(
            """
            var ticks = 0;
            __uiRun(function(ev) {
              if (ev.name === "tick") {
                ticks = ticks + ev.args[0];
                if (ticks >= 6) __uiExit();
              }
            });
            print("ticks=" + ticks);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("ticks=6")
    }

    @Test
    fun `dispatcher exceptions do not kill the event loop`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        env.events.offer(ScriptEvent("bad", listOf()))
        env.events.offer(ScriptEvent("good", listOf()))

        val r = RhinoJSHost().eval(
            """
            var goodCount = 0;
            __uiRun(function(ev) {
              if (ev.name === "bad") throw new Error("oops");
              if (ev.name === "good") {
                goodCount++;
                __uiExit();
              }
            });
            print("good=" + goodCount);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("good=1")
    }

    @Test
    fun `uiRun rejects non-function argument`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        val r = RhinoJSHost().eval(
            """__uiRun(42);""",
            "test.js", env,
        )
        r.ok shouldBe false
        r.errorMessage shouldContain "first argument must be a function"
    }

    @Test
    fun `setTimeout returns a numeric id`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        val r = RhinoJSHost().eval(
            """
            var id = setTimeout(function() {}, 5);
            print("id-type=" + typeof id);
            print("id-positive=" + (id > 0));
            clearTimeout(id);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("id-type=number", "id-positive=true")
    }

    @Test
    fun `event args preserve numeric and string types`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        env.events.offer(ScriptEvent("payload", listOf("a", 7, 3.5, true, null)))

        val r = RhinoJSHost().eval(
            """
            __uiRun(function(ev) {
              print("s=" + ev.args[0]);
              print("i=" + ev.args[1]);
              print("d=" + ev.args[2]);
              print("b=" + ev.args[3]);
              print("n=" + (ev.args[4] === null));
              __uiExit();
            });
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("s=a", "i=7", "d=3.5", "b=true", "n=true")
    }

    @Test
    fun `queue accumulated events are drained in order`() {
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        env.events.offer(ScriptEvent("e", listOf(1)))
        env.events.offer(ScriptEvent("e", listOf(2)))
        env.events.offer(ScriptEvent("e", listOf(3)))
        env.events.offer(ScriptEvent("stop", listOf()))

        val r = RhinoJSHost().eval(
            """
            var seen = [];
            __uiRun(function(ev) {
              if (ev.name === "stop") { __uiExit(); return; }
              seen.push(ev.args[0]);
            });
            print(seen.join(","));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("1,2,3")
    }

    @Test
    fun `setInterval with short period does not starve uiExit`() {
        // Regression for a timing-order bug: the interval daemon thread could
        // flood the queue faster than the dispatcher drains it, and the exit
        // signal might sit behind interval ticks forever. We assert that once
        // __uiExit is called, __uiRun returns in finite time.
        val out = CapturingOut()
        val env = FakeEnv(out = out)
        val r = RhinoJSHost().eval(
            """
            var count = 0;
            var id = setInterval(function() {
              count++;
              if (count === 5) {
                clearInterval(id);
                __uiExit();
              }
            }, 5);
            __uiRun(function(ev) {});
            print("count=" + count);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines.size shouldBe 1
        // Count could be exactly 5 or a little more if a tick was already in flight
        // when clearInterval landed; we just need the loop to terminate and count to
        // be at least 5.
        val countLine = out.lines[0]
        countLine shouldContain "count="
        val n = countLine.removePrefix("count=").toInt()
        n shouldBeGreaterThanOrEqual 5
    }
}
