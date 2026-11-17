package pe.radiosdelperu.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import pe.radiosdelperu.app.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actualizador propio, sin tienda de aplicaciones.
 *
 * 1. Lee `version.json` de GitHub (una vez al día, o cuando el usuario lo fuerza).
 * 2. Si hay una versión más nueva, descarga el APK a la caché y comprueba el sha256.
 * 3. Abre el instalador del sistema.
 *
 * Android **siempre** pide confirmación para instalar una app: no se puede hacer en
 * silencio. Lo que sí se evita es el cable, copiar archivos y desinstalar.
 *
 * La firma del APK es la última red de seguridad: si alguien pusiera un APK ajeno en
 * `version.json`, Android lo rechaza porque la firma no coincide con la instalada.
 */
class Updater(private val activity: Activity) {

    interface Listener {
        fun onChecking(forced: Boolean)
        fun onUpToDate(forced: Boolean, installedVersion: String)
        fun onUpdateAvailable(info: UpdateInfo, forced: Boolean)
        fun onDownloading(percent: Int)
        fun onReady(file: File)
        fun onError(message: String)
        fun onInstallPermissionNeeded()
    }

    var listener: Listener? = null

    private val context: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val busy = AtomicBoolean(false)

    val installedVersionName: String
        get() = packageInfo()?.versionName ?: "?"

    private val installedVersionCode: Int
        get() {
            val info = packageInfo() ?: return 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }

    /** Busca actualización. `forced = true` ignora la comprobación diaria. */
    fun check(forced: Boolean) {
        if (!forced && !shouldCheck()) return
        if (!busy.compareAndSet(false, true)) return

        main.post { listener?.onChecking(forced) }

        Thread {
            val info = fetchLatest()
            main.post {
                busy.set(false)

                if (info == null) {
                    // En la comprobación silenciosa no molestamos con errores de red.
                    if (forced) listener?.onError(context.getString(R.string.update_no_connection))
                    return@post
                }

                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                if (info.versionCode > installedVersionCode) {
                    listener?.onUpdateAvailable(info, forced)
                } else {
                    listener?.onUpToDate(forced, installedVersionName)
                }
            }
        }.start()
    }

    /** Descarga el APK y avisa con [Listener.onReady] cuando está listo y verificado. */
    fun download(info: UpdateInfo) {
        if (!busy.compareAndSet(false, true)) return

        Thread {
            try {
                val dir = File(context.cacheDir, CACHE_DIR)
                if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()

                val target = File(dir, "RadiosDelPeru-${info.versionName}.apk")
                val total = downloadTo(info.apkUrl, target) { percent ->
                    main.post { listener?.onDownloading(percent) }
                }

                if (total <= 0L) throw IllegalStateException("APK vacío")
                if (total != target.length()) throw IllegalStateException("APK incompleto")

                val expected = info.sha256
                if (expected != null && !expected.equals(sha256(target), ignoreCase = true)) {
                    target.delete()
                    throw IllegalStateException("sha256 no coincide")
                }

                main.post {
                    busy.set(false)
                    listener?.onReady(target)
                }
            } catch (_: Exception) {
                main.post {
                    busy.set(false)
                    listener?.onError(context.getString(R.string.update_download_failed))
                }
            }
        }.start()
    }

    /** Abre el instalador del sistema con el APK ya descargado. */
    fun install(file: File) {
        if (!file.exists()) {
            listener?.onError(context.getString(R.string.update_download_failed))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            listener?.onInstallPermissionNeeded()
            openInstallSettings()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            listener?.onError(context.getString(R.string.update_install_failed))
        }
    }

    // ------------------------------------------------------------------ interno

    private fun openInstallSettings() {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (_: Exception) {
            // Algunos fabricantes no exponen esta pantalla; el usuario tendrá que buscarla.
        }
    }

    private fun shouldCheck(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    private fun packageInfo(): android.content.pm.PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchLatest(): UpdateInfo? {
        for (url in REMOTE_URLS) {
            val text = try {
                httpGet(url)
            } catch (_: Exception) {
                null
            } ?: continue

            UpdateInfo.parse(text)?.let { return it }
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) {
                null
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                if (text.length in 10..MAX_JSON_CHARS) text else null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadTo(url: String, target: File, onProgress: (Int) -> Unit): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.android.package-archive")
        }

        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) throw IllegalStateException("APK demasiado grande")

            var read = 0L
            var lastPercent = -1

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0 && read <= MAX_APK_BYTES) {
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            return read
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS = "updates"
        const val KEY_LAST_CHECK = "last_check"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_JSON_CHARS = 64_000
        const val MAX_APK_BYTES = 25L * 1024 * 1024
        const val CACHE_DIR = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val USER_AGENT = "RadiosDelPeru/1.4 (+https://github.com/carpediego/RadiosdelPeru)"

        /** Primero el archivo crudo (se actualiza en ~5 min), luego jsDelivr de respaldo. */
        val REMOTE_URLS = listOf(
            "https://raw.githubusercontent.com/carpediego/RadiosdelPeru/main/version.json",
            "https://cdn.jsdelivr.net/gh/carpediego/RadiosdelPeru@main/version.json"
        )
    }
}
