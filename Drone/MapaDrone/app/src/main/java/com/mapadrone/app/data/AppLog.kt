package com.mapadrone.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log do app em arquivo (alem do log de cada voo): eventos, erros, excecoes e travamentos.
 * Fica em files/applog/app.log (1 MB, depois vira app.old.log) e as linhas novas sao enviadas ao servidor
 * a cada sincronizacao, para consultar na pagina (Logs do app) sem precisar do cabo.
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L
    private const val MAX_UPLOAD = 1_000_000L
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private var device = "tablet"
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "applog").apply { mkdirs() }
        prefs = ctx.getSharedPreferences("applog", Context.MODE_PRIVATE)
        val id = runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty().take(4)
        device = (Build.MODEL.filter { it.isLetterOrDigit() }.ifEmpty { "tablet" }) + (if (id.isNotEmpty()) "-$id" else "")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("CRASH na thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        log("App iniciado (v$version, $device, Android ${Build.VERSION.RELEASE})")
    }

    private fun file() = File(dir, "app.log")

    @Synchronized
    fun log(msg: String, error: Throwable? = null) {
        if (!::dir.isInitialized) return
        runCatching {
            val f = file()
            if (f.length() > MAX_BYTES) {
                f.renameTo(File(dir, "app.old.log"))
                prefs.edit().putLong("sent", 0).apply()
            }
            val sb = StringBuilder(fmt.format(Date())).append(' ').append(msg).append('\n')
            if (error != null) sb.append(StringWriter().also { error.printStackTrace(PrintWriter(it)) }).append('\n')
            file().appendText(sb.toString(), Charsets.UTF_8)
        }
    }

    /** Texto ainda nao enviado e o deslocamento a gravar se o envio der certo; null se nao ha nada novo. */
    @Synchronized
    fun pending(): Pair<String, Long>? {
        if (!::dir.isInitialized) return null
        val f = file()
        val len = f.length()
        var from = prefs.getLong("sent", 0)
        if (from > len) from = 0
        if (len == from) return null
        val start = maxOf(from, len - MAX_UPLOAD)
        val buf = ByteArray((len - start).toInt())
        RandomAccessFile(f, "r").use { it.seek(start); it.readFully(buf) }
        return String(buf, Charsets.UTF_8) to len
    }

    @Synchronized
    fun markSent(offset: Long) {
        prefs.edit().putLong("sent", offset).apply()
    }

    /** Ultimos erros do logcat do proprio app (o Android deixa o app ler so os proprios logs). */
    fun logcatErrors(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "400", "--pid=${Process.myPid()}", "*:E"))
        p.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        ""
    }

    /** Nome do arquivo no servidor: aparelho + data/hora, para nao sobrescrever envios anteriores. */
    fun uploadName(): String = device + "_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
}
