package com.rajan.meditationtimer

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A test that hangs otherwise holds CI for the whole 20-minute cap and leaves no clue where it
 * stuck. After [limitSeconds] this prints every thread's stack, and the lock each is waiting for,
 * to the build log; if the test still hasn't finished [graceSeconds] later it stops the test JVM,
 * so the build fails in minutes with the stacks in hand. No test here comes near the limit.
 */
class HangDump(private val limitSeconds: Long = 120, private val graceSeconds: Long = 180) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val finished = CountDownLatch(1)
            Thread({ watch(description, finished) }, "hang-dump").apply { isDaemon = true }.start()
            try {
                base.evaluate()
            } finally {
                finished.countDown()
            }
        }
    }

    private fun watch(test: Description, finished: CountDownLatch) {
        if (finished.await(limitSeconds, TimeUnit.SECONDS)) return
        // Straight to the process's stderr: Gradle puts that in the log, unlike a test's System.err.
        val log = PrintStream(FileOutputStream(FileDescriptor.err), true)
        dump(log, "${test.displayName} has run for $limitSeconds s")
        if (finished.await(graceSeconds, TimeUnit.SECONDS)) return
        dump(log, "${test.displayName} is still stuck $graceSeconds s later; stopping the test JVM")
        Runtime.getRuntime().halt(1)
    }

    private fun dump(log: PrintStream, headline: String) {
        log.println("HANG: $headline. Every thread:")
        for ((thread, stack) in Thread.getAllStackTraces()) {
            log.println("\"${thread.name}\" ${thread.state}")
            for (frame in stack.take(MAX_FRAMES)) log.println("\tat $frame")
            if (stack.size > MAX_FRAMES) log.println("\t... ${stack.size - MAX_FRAMES} more")
        }
        for (line in locks()) log.println("HANG: $line")
    }

    /**
     * Which lock each stuck thread wants and who holds it. Unit tests compile against Android's API,
     * which has no java.lang.management, so this reaches the test JVM's copy by reflection.
     */
    private fun locks(): List<String> = runCatching {
        val api = Class.forName("java.lang.management.ThreadMXBean")
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val bool = Boolean::class.javaPrimitiveType
        val infos = api.getMethod("dumpAllThreads", bool, bool).invoke(threads, true, true) as Array<*>
        val deadlocked = api.getMethod("findDeadlockedThreads").invoke(threads) as LongArray?
        // The first line of each reads "name" ... Id=N STATE on <lock> owned by "other" Id=M.
        infos.map { it.toString().lineSequence().first() }.filter { " on " in it } +
            listOfNotNull(deadlocked?.let { "deadlocked: Id=" + it.joinToString(", Id=") })
    }.getOrElse { listOf("no lock details: $it") }

    private companion object {
        const val MAX_FRAMES = 60
    }
}
