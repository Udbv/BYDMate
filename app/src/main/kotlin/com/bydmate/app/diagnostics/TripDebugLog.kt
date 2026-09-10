package com.bydmate.app.diagnostics

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Per-trip guidance log written on the car itself.
 *
 * Wi-Fi is gone the moment the car leaves the driveway, so anything that only exists in `logcat`
 * is unreachable for the drive that actually matters. This writes a compact, structured record of
 * every guidance decision straight to the public Download folder, where it survives the trip and
 * can be collected later over USB, a file manager, or ADB once the car is home again.
 *
 * It is **off by default** and gated by an explicit debug switch, because it records what the
 * navigator showed on screen: street names, and with them, by implication, where the car went.
 * Nothing here is sent anywhere; the files stay on the car until the owner copies them off.
 *
 * One file per trip, newest [MAX_FILES] kept. Writes are buffered and flushed on a background
 * drain so the 300 ms push loop never touches storage.
 */
object TripDebugLog {
    private const val TAG = "TripDebugLog"
    const val PREFS_NAME = "diagnostics"
    const val KEY_ENABLED = "trip_debug_log"

    /** Rolling window of trips kept on the car. */
    const val MAX_FILES = 12

    /** A single trip's log is capped so a long drive cannot fill the partition. */
    private const val MAX_BYTES = 4L * 1024 * 1024

    private const val FOLDER = "BYDMate-trips"

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var enabled = false
    @Volatile private var file: File? = null
    @Volatile private var written = 0L
    @Volatile private var truncated = false
    private val queue = ConcurrentLinkedQueue<String>()

    /** Reads the switch; call once when the service starts and after the setting changes. */
    fun refresh(context: Context) {
        enabled = isEnabled(context)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        enabled = on
        Log.i(TAG, "trip debug log ${if (on) "enabled" else "disabled"}")
        if (!on) endTrip("switch off")
    }

    /** True while a trip file is open; cheap enough to guard every call site. */
    val active: Boolean get() = enabled && file != null

    /**
     * Opens a new trip file. Safe to call repeatedly: a trip already open is kept, so a
     * restarted service does not split one drive across two files.
     */
    @Synchronized
    fun startTrip(context: Context, reason: String) {
        refresh(context)
        if (!enabled || file != null) return
        val dir = downloadDir(context) ?: run {
            Log.w(TAG, "no writable storage; trip log not started")
            return
        }
        val target = File(dir, "trip-${stamp.format(Date())}.log")
        runCatching {
            target.writeText(header(context, reason))
            file = target
            written = target.length()
            truncated = false
            prune(dir)
            Log.i(TAG, "trip log open: ${visiblePath(target)}")
        }.onFailure { Log.w(TAG, "cannot open trip log: ${it.message}") }
    }

    /** Appends one line. Never throws, never blocks on storage. */
    fun event(tag: String, message: String) {
        if (!active) return
        queue.add("${clock.format(Date())} $tag $message")
        if (queue.size >= 32) drain()
    }

    /** Same, but only when the value differs from the previous one under the same key. */
    private val lastByKey = HashMap<String, String>()

    @Synchronized
    fun changed(tag: String, key: String, message: String) {
        if (!active) return
        if (lastByKey[key] == message) return
        lastByKey[key] = message
        event(tag, message)
    }

    @Synchronized
    fun drain() {
        val target = file ?: run { queue.clear(); return }
        if (queue.isEmpty()) return
        val batch = StringBuilder()
        while (true) {
            val line = queue.poll() ?: break
            batch.append(line).append('\n')
        }
        if (batch.isEmpty()) return
        if (written >= MAX_BYTES) {
            if (!truncated) {
                truncated = true
                runCatching { target.appendText("-- size cap reached, further events dropped --\n") }
            }
            return
        }
        runCatching {
            target.appendText(batch.toString())
            written += batch.length
        }.onFailure { Log.w(TAG, "trip log write failed: ${it.message}") }
    }

    /** Flushes and closes the current trip. */
    @Synchronized
    fun endTrip(reason: String) {
        val target = file ?: return
        queue.add("${clock.format(Date())} TRIP end: $reason")
        file = target
        drain()
        file = null
        lastByKey.clear()
        Log.i(TAG, "trip log closed: ${visiblePath(target)} (${target.length() / 1024} KB)")
    }

    /** Files on the car, newest first, for the settings screen. */
    fun files(context: Context): List<File> =
        downloadDir(context)?.listFiles { f -> f.name.startsWith("trip-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun folderPath(context: Context): String =
        downloadDir(context)?.let { visiblePath(it) } ?: "-"

    private fun prune(dir: File) {
        val logs = dir.listFiles { f -> f.name.startsWith("trip-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: return
        logs.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    /** Same candidate chain the FID dump and the log recorder use, in a folder of our own. */
    private fun downloadDir(context: Context): File? {
        val base = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            context.getExternalFilesDir(null),
        ).firstOrNull { (it.isDirectory || it.mkdirs()) && it.canWrite() } ?: return null
        val dir = File(base, FOLDER)
        return if (dir.isDirectory || dir.mkdirs()) dir else base
    }

    private fun visiblePath(f: File): String {
        val p = f.absolutePath
        val cut = p.indexOf("/Download")
        return if (cut >= 0) p.substring(cut + 1) else p
    }

    private fun header(context: Context, reason: String): String = buildString {
        append("BYDMate trip debug log\n")
        append("opened: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append("  reason: ").append(reason).append('\n')
        append("app: ").append(com.bydmate.app.BuildConfig.VERSION_NAME)
            .append(" (").append(com.bydmate.app.BuildConfig.VERSION_CODE).append(")")
            .append("  flavor: ").append(com.bydmate.app.BuildConfig.FLAVOR).append('\n')
        append("vehicle: ").append(com.bydmate.app.hud.HudDialect.readVehicleType().ifEmpty { "-" })
            .append("  hud dialect: ").append(com.bydmate.app.hud.HudDialect.resolve(context)).append('\n')
        append("Contains navigator screen text (street names). Kept on the car only.\n")
        append("--------------------------------------------------------------\n")
    }
}
