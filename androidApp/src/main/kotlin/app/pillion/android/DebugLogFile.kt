package app.pillion.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists this app's own logcat output to a file so a failed ride-side connection can be diagnosed
 * after the phone is unplugged from the PC. An app may read its own log lines without READ_LOGS, so
 * every `Log.*` call (Pillion, SDL, Bluetooth stack warnings, crashes) lands in the file without
 * touching the call sites.
 *
 * Pull it with: `adb pull /sdcard/Android/data/app.pillion/files/pillion-debug.log`
 */
object DebugLogFile {
    private const val FILE_NAME = "pillion-debug.log"
    // ~400 KB/h of streaming lines: 25 MB per file; full files are kept as .1, .2, … (max MAX_ROTATED).
    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val MAX_ROTATED = 10
    private var file: File? = null
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, FILE_NAME)
        if (f.length() > MAX_BYTES) rotate(f)
        file = f
        started = true
        Thread({ capture(f) }, "pillion-debuglog").apply { isDaemon = true }.start()
        snapshot(context, "app start")
    }

    /** One-off state dump: versions, permissions and the bonded Bluetooth devices the connect will try. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun snapshot(context: Context, reason: String) {
        val f = file ?: return
        val sb = StringBuilder()
        sb.appendLine("===== SNAPSHOT ($reason) ${now()} =====")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("app ${info.versionName} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        }
        val btConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
        sb.appendLine("BLUETOOTH_CONNECT granted: $btConnect")
        runCatching {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            sb.appendLine("bluetooth adapter: ${if (adapter == null) "none" else "enabled=${adapter.isEnabled}"}")
            if (adapter != null && btConnect) {
                val bonded = adapter.bondedDevices
                sb.appendLine("bonded devices (${bonded.size}):")
                for (d in bonded) sb.appendLine("  name='${d.name}' addr=${d.address} type=${d.type}")
            }
        }.onFailure { sb.appendLine("bluetooth query failed: $it") }
        sb.appendLine("=====")
        runCatching { f.appendText(sb.toString()) }
    }

    private fun capture(f: File) {
        runCatching {
            val proc = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
            f.appendText("===== log capture started ${now()} pid=${Process.myPid()} =====\n")
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    synchronized(this) { f.appendText(line + "\n") }
                    if (f.length() > MAX_BYTES) synchronized(this) {
                        rotate(f)
                    }
                }
            }
        }
    }

    /**
     * Moves the full log to the next numbered file (`pillion-debug.log.1`, `.2`, …) and never overwrites
     * an earlier one, so nothing is lost on a long ride. Only the oldest files beyond [MAX_ROTATED]
     * are deleted (about 250 MB in total at the default size).
     */
    private fun rotate(f: File) {
        val dir = f.parentFile ?: return
        val numbered = dir.listFiles { _, name -> name.startsWith("$FILE_NAME.") }
            ?.mapNotNull { it.name.removePrefix("$FILE_NAME.").toIntOrNull()?.let { n -> n to it } }
            ?.sortedBy { it.first }
            .orEmpty()
        val next = (numbered.lastOrNull()?.first ?: 0) + 1
        f.renameTo(File(dir, "$FILE_NAME.$next"))
        numbered.dropLast(MAX_ROTATED - 1).forEach { it.second.delete() }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
