package com.example.host

import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService running inside the Shizuku ADB/root process (API 29+).
 * Executes real multi-display app launches (`am start --display <N>`), queries displayId 0
 * active tasks for `activeHostPackages`, and probes shell `input -d <displayId>` support.
 */
@Keep
class ShizukuDesktopUserService : IShizukuDesktopService.Stub() {

    private var cachedShellDFlag: Boolean? = null

    override fun enableFreeformDesktopSupport(): Boolean {
        val r1 = execCommand(arrayOf("settings", "put", "global", "enable_freeform_support", "1"))
        val r2 = execCommand(arrayOf("settings", "put", "global", "force_resizable_activities", "1"))
        return r1.exitCode == 0 && r2.exitCode == 0
    }

    override fun launchAppOnDisplay(
        packageName: String,
        activityComponent: String?,
        displayId: Int
    ): Boolean {
        val safePkg = packageName.replace(Regex("[^A-Za-z0-9._]"), "")
        if (safePkg.isBlank() || displayId < 0) return false

        if (displayId > 0) {
            enableFreeformDesktopSupport()
        }

        val comp = activityComponent
            ?.takeIf { it.contains('/') }
            ?.replace(Regex("[^A-Za-z0-9._/$]"), "")

        val freeformCmd = buildFreeformLaunchCommand(safePkg, comp, displayId)
        if (execCommand(freeformCmd).exitCode == 0) {
            return true
        }

        val fallbackCmd = buildFallbackLaunchCommand(safePkg, comp, displayId)
        return execCommand(fallbackCmd).exitCode == 0
    }

    override fun queryHostForegroundPackages(displayId: Int): String {
        val stackOut = execCommand(arrayOf("am", "stack", "list")).stdout
        val pkgs = linkedSetOf<String>()
        val targetDisplayToken = "displayId=$displayId"
        var inTargetDisplay = true

        stackOut.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Stack") || trimmed.startsWith("RootTask") || trimmed.contains("displayId=")) {
                inTargetDisplay = trimmed.contains(targetDisplayToken) || !trimmed.contains("displayId=")
            }
            if (inTargetDisplay && (trimmed.contains("realActivity=") || trimmed.contains("topActivity="))) {
                val match = PACKAGE_REGEX.find(trimmed)
                match?.groupValues?.getOrNull(1)?.let { pkg ->
                    if (!pkg.startsWith("com.android.launcher") && !pkg.startsWith("com.android.systemui")) {
                        pkgs.add(pkg)
                    }
                }
            }
        }
        return pkgs.joinToString(",")
    }

    override fun probeShellInputDisplayFlag(): Boolean {
        cachedShellDFlag?.let { return it }
        // Probe both `input --help` and flag support; -d is not guaranteed on all Android 10-15 ROMs
        val help = execCommand(arrayOf("input", "--help"))
        val combined = "${help.stdout}\n${help.stderr}"
        val supported = combined.contains("-d <DISPLAY_ID>") ||
            combined.contains("-d display_id") ||
            combined.contains("[-d DISPLAY_ID]")
        cachedShellDFlag = supported
        return supported
    }

    override fun injectShellTouch(displayId: Int, action: Int, x: Int, y: Int): Boolean {
        if (!probeShellInputDisplayFlag()) return false
        val cmd = when (action) {
            ACTION_TAP -> arrayOf("input", "-d", displayId.toString(), "touchscreen", "tap", x.toString(), y.toString())
            else -> return false
        }
        return execCommand(cmd).exitCode == 0
    }

    override fun injectShellSwipe(
        displayId: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int
    ): Boolean {
        if (!probeShellInputDisplayFlag()) return false
        val safeDuration = durationMs.coerceIn(50, 1000)
        val cmd = arrayOf(
            "input", "-d", displayId.toString(),
            "touchscreen", "swipe",
            startX.toString(), startY.toString(),
            endX.toString(), endY.toString(),
            safeDuration.toString()
        )
        return execCommand(cmd).exitCode == 0
    }

    override fun injectShellKeyOrText(displayId: Int, keyCode: Int, text: String?): Boolean {
        if (!probeShellInputDisplayFlag()) return false
        val cmd = if (!text.isNullOrEmpty() && keyCode == 0) {
            val sanitized = text.replace(Regex("[^A-Za-z0-9 .,!?_-]"), "")
            if (sanitized.isBlank()) return false
            arrayOf("input", "-d", displayId.toString(), "text", sanitized.replace(" ", "%s"))
        } else if (keyCode > 0) {
            arrayOf("input", "-d", displayId.toString(), "keyevent", keyCode.toString())
        } else {
            return false
        }
        return execCommand(cmd).exitCode == 0
    }

    /**
     * Experimental per-OEM Host relocation path: uses `am start --display 0` (never `am force-stop`)
     * with before/after `am stack list` snapshots so device runs can verify state survival per OEM ROM.
     */
    override fun moveAppBackToHost(packageName: String): String {
        val safePkg = packageName.replace(Regex("[^A-Za-z0-9._]"), "")
        if (safePkg.isBlank()) return "EXPERIMENTAL_RELOCATION|error=invalid_package"
        val before = queryHostForegroundPackages(0)
        val resolveOut = execCommand(arrayOf("cmd", "package", "resolve-activity", "--brief", safePkg)).stdout
        val comp = resolveOut.lineSequence().lastOrNull { it.contains('/') }?.trim()
        val moveCmd = if (!comp.isNullOrBlank()) {
            arrayOf("am", "start", "--display", "0", "-n", comp, "-f", "0x10000000")
        } else {
            arrayOf(
                "am", "start", "--display", "0",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "-p", safePkg,
                "-f", "0x10000000"
            )
        }
        val moveResult = execCommand(moveCmd)
        val after = queryHostForegroundPackages(0)
        return "EXPERIMENTAL_RELOCATION|pkg=$safePkg|exit=${moveResult.exitCode}|before=[$before]|after=[$after]|note=Experimental per-OEM; verify state survival on device"
    }

    override fun queryDisplayStackSnapshot(): String {
        val out = execCommand(arrayOf("am", "stack", "list"))
        val combined = buildString {
            if (out.stdout.isNotBlank()) append(out.stdout)
            if (out.stderr.isNotBlank()) append("\nSTDERR: ").append(out.stderr)
        }.trim()
        return combined.take(4096).ifBlank { "(empty stack output, exit=${out.exitCode})" }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun execCommand(args: Array<String>): BoundedShellExecutor.CmdResult =
        BoundedShellExecutor.exec(args, timeoutMs = 3_000L)

    companion object {
        private const val ACTION_TAP = 0
        const val WINDOWING_MODE_FREEFORM = 5
        private val PACKAGE_REGEX = Regex("(?:realActivity|topActivity)=ComponentInfo\\{?([A-Za-z0-9._]+)/")

        fun buildFreeformLaunchCommand(
            safePkg: String,
            safeComp: String?,
            displayId: Int
        ): Array<String> = if (!safeComp.isNullOrBlank()) {
            arrayOf(
                "am", "start",
                "--display", displayId.toString(),
                "--windowingMode", WINDOWING_MODE_FREEFORM.toString(),
                "-n", safeComp,
                "-f", "0x10000000"
            )
        } else {
            arrayOf(
                "am", "start",
                "--display", displayId.toString(),
                "--windowingMode", WINDOWING_MODE_FREEFORM.toString(),
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "-p", safePkg,
                "-f", "0x10000000"
            )
        }

        fun buildFallbackLaunchCommand(
            safePkg: String,
            safeComp: String?,
            displayId: Int
        ): Array<String> = if (!safeComp.isNullOrBlank()) {
            arrayOf("am", "start", "--display", displayId.toString(), "-n", safeComp, "-f", "0x10000000")
        } else {
            arrayOf("monkey", "-p", safePkg, "-c", "android.intent.category.LAUNCHER", "1")
        }
    }
}

object BoundedShellExecutor {
    data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

    fun exec(args: Array<String>, timeoutMs: Long = 3_000L): CmdResult {
        return try {
            val proc = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()

            val outputBuilder = StringBuilder()
            val readerThread = Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        val buf = CharArray(2048)
                        while (true) {
                            val read = reader.read(buf)
                            if (read < 0) break
                            outputBuilder.append(buf, 0, read)
                        }
                    }
                } catch (_: Throwable) {
                }
            }, "ExtDesk-CmdDrain").apply {
                isDaemon = true
                start()
            }

            val finished = waitForProcess(proc, timeoutMs)
            if (!finished) {
                proc.destroyForcibly()
                readerThread.join(250L)
                return CmdResult(
                    exitCode = -2,
                    stdout = outputBuilder.toString(),
                    stderr = "Command timed out after ${timeoutMs}ms"
                )
            }

            readerThread.join(500L)
            CmdResult(proc.exitValue(), outputBuilder.toString(), "")
        } catch (t: Throwable) {
            CmdResult(-1, "", t.message ?: "")
        }
    }

    private fun waitForProcess(proc: Process, timeoutMs: Long): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            return proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                proc.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                if (System.currentTimeMillis() >= deadline) return false
                Thread.sleep(15L)
            }
        }
    }
}
