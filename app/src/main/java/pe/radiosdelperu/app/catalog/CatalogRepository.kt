package pe.radiosdelperu.app.catalog

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Origen del catálogo de emisoras.
 *
 * Estrategia:
 *   1. `assets/stations.json` viene dentro del APK: la app funciona siempre, incluso sin internet.
 *   2. Si hay una copia remota más nueva en caché, se usa esa (el robot corrige URLs rotas).
 *   3. Al arrancar se consulta el CDN como máximo una vez cada [CHECK_INTERVAL_MS].
 *
 * Regla que nunca se rompe: solo se guardan URLs de *entrada*. Los proveedores
 * (mdstrm, zeno…) redirigen a una CDN con token que caduca, así que cachear la
 * URL final dejaría la emisora muda en minutos.
 */
class CatalogRepository(private val context: Context) {

    data class Snapshot(val stations: List<Station>, val version: Int)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Catálogo vigente: la copia remota cacheada si es más nueva; si no, la embebida. */
    fun load(): Snapshot {
        val embedded = readEmbedded() ?: return Snapshot(emptyList(), 0)
        val cached = readCached()
        return if (cached != null && cached.version > embedded.version) cached else embedded
    }

    /**
     * Busca una versión nueva del catálogo sin bloquear la interfaz.
     * Si encuentra una versión más nueva y válida, la guarda y avisa en el hilo principal.
     */
    fun refreshAsync(onUpdated: (Snapshot) -> Unit) {
        if (!shouldCheck()) return

        Thread {
            val fresh = fetchRemote()
            if (fresh != null && fresh.version > load().version) {
                writeCache(fresh)
                mainHandler.post { onUpdated(fresh) }
            }
            val now = System.currentTimeMillis()
            // Si no se pudo consultar (sin internet), reintentamos en 30 min en vez de 6 h.
            val stamp = if (fresh != null) now else now - CHECK_INTERVAL_MS + FAILURE_RETRY_MS
            prefs.edit().putLong(KEY_LAST_CHECK, stamp).apply()
        }.start()
    }

    // ------------------------------------------------------------------ red

    private fun shouldCheck(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    private fun fetchRemote(): Snapshot? {
        for (url in REMOTE_URLS) {
            val text = try {
                httpGet(url)
            } catch (_: Exception) {
                null
            } ?: continue

            val snapshot = try {
                parse(text)
            } catch (_: Exception) {
                null
            } ?: continue

            // Salvaguarda: si el remoto llega mutilado, seguimos con el embebido.
            if (snapshot.stations.size >= MIN_STATIONS) return snapshot
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
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.length in 100..MAX_BODY_CHARS) text else null
        } finally {
            connection.disconnect()
        }
    }

    // --------------------------------------------------------------- archivo

    private fun readEmbedded(): Snapshot? = try {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        parse(raw)
    } catch (_: Exception) {
        null
    }

    private fun readCached(): Snapshot? = try {
        if (!cacheFile.exists()) null else parse(cacheFile.readText())
    } catch (_: Exception) {
        null
    }

    private fun writeCache(snapshot: Snapshot) {
        try {
            val text = snapshot.toJson()
            val tmp = File(cacheFile.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(text)
            if (cacheFile.exists()) cacheFile.delete()
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
            // Si falla la caché, no pasa nada: seguimos con la copia embebida.
        }
    }

    // ---------------------------------------------------------------- parseo

    private fun parse(raw: String): Snapshot {
        val root = JSONObject(raw)

        val schema = root.optInt("schema", 1)
        require(schema <= SUPPORTED_SCHEMA) { "Esquema de catálogo no soportado: $schema" }

        val array = root.optJSONArray("stations") ?: return Snapshot(emptyList(), 0)
        val out = ArrayList<Station>(array.length())

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val urls = ArrayList<String>()
            val candidates = obj.optJSONArray("candidates")
            if (candidates != null) {
                for (j in 0 until candidates.length()) {
                    val url = candidates.getJSONObject(j).optString("url", "")
                    if (url.startsWith("http://") || url.startsWith("https://")) urls.add(url)
                }
            }
            if (urls.isEmpty()) continue

            val provider = obj.optJSONObject("provider")

            out.add(
                Station(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    tagline = obj.optString("tagline", ""),
                    category = obj.optString("category", "otras"),
                    color = obj.optString("color", "#0B5FFF"),
                    priority = obj.optInt("priority", 100),
                    active = obj.optBoolean("active", true),
                    candidates = urls,
                    providerKind = provider?.optString("kind"),
                    providerId = provider?.optString("id")
                )
            )
        }

        val visible = out
            .filter { it.active }
            .sortedWith(compareBy({ it.priority }, { it.name }))

        return Snapshot(visible, root.optInt("version", 0))
    }

    private fun Snapshot.toJson(): String {
        // Solo se reescribe lo necesario: la app nunca inventa datos del catálogo.
        val root = JSONObject()
        root.put("schema", SUPPORTED_SCHEMA)
        root.put("version", version)
        val array = org.json.JSONArray()
        for (station in stations) {
            val obj = JSONObject()
            obj.put("id", station.id)
            obj.put("name", station.name)
            obj.put("tagline", station.tagline)
            obj.put("category", station.category)
            obj.put("color", station.color)
            obj.put("priority", station.priority)
            obj.put("active", station.active)
            val candidates = org.json.JSONArray()
            for (url in station.candidates) {
                candidates.put(JSONObject().put("url", url))
            }
            obj.put("candidates", candidates)
            if (station.providerKind != null && station.providerId != null) {
                obj.put(
                    "provider",
                    JSONObject().put("kind", station.providerKind).put("id", station.providerId)
                )
            }
            array.put(obj)
        }
        root.put("stations", array)
        return root.toString()
    }

    private companion object {
        const val ASSET_NAME = "stations.json"
        const val CACHE_FILE = "catalog-cache.json"
        const val PREFS = "catalog"
        const val KEY_LAST_CHECK = "last_check"

        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 8_000
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val FAILURE_RETRY_MS = 30L * 60 * 1000
        const val MAX_BODY_CHARS = 2_000_000
        const val MIN_STATIONS = 5
        const val SUPPORTED_SCHEMA = 1
        const val USER_AGENT = "RadiosDelPeru/1.1 (+https://github.com/carpediego/RadiosdelPeru)"

        /**
         * Primero el archivo crudo (se actualiza en ~5 minutos), luego el CDN
         * de jsDelivr como respaldo (más rápido pero con caché de horas).
         */
        val REMOTE_URLS = listOf(
            "https://raw.githubusercontent.com/carpediego/RadiosdelPeru/main/catalog/stations.json",
            "https://cdn.jsdelivr.net/gh/carpediego/RadiosdelPeru@main/catalog/stations.json"
        )
    }
}
