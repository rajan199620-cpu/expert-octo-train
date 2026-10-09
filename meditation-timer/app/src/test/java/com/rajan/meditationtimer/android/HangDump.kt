package com.rajan.meditationtimer

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
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
        val threads = ManagementFactory.getThreadMXBean()
        for (info in threads.dumpAllThreads(true, true)) {
            log.println(
                "\"${info.threadName}\" ${info.threadState}" +
                    (info.lockName?.let { " waiting for $it" } ?: "") +
                    (info.lockOwnerName?.let { " held by \"$it\"" } ?: ""),
            )
            for ((depth, frame) in info.stackTrace.withIndex().take(MAX_FRAMES)) {
                log.println("\tat $frame")
                for (monitor in info.lockedMonitors) if (monitor.lockedStackDepth == depth) log.println("\t- locked $monitor")
            }
            if (info.stackTrace.size > MAX_FRAMES) log.println("\t... ${info.stackTrace.size - MAX_FRAMES} more")
        }
        threads.findDeadlockedThreads()?.let { ids ->
            log.println("HANG: deadlocked: " + threads.getThreadInfo(ids).joinToString { "\"${it.threadName}\"" })
        }
    }

    private companion object {
        const val MAX_FRAMES = 60
    }
}
