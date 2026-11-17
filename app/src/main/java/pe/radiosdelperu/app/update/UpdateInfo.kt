package pe.radiosdelperu.app.update

import org.json.JSONObject

/**
 * Lo que publica `version.json` en GitHub.
 *
 * Ejemplo:
 * ```
 * {
 *   "versionCode": 5,
 *   "versionName": "1.4.0",
 *   "apkUrl": "https://github.com/.../releases/download/v1.4.0/RadiosDelPeru-1.4.0.apk",
 *   "sha256": "…",
 *   "notes": "Actualizador dentro de la app"
 * }
 * ```
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String?
) {
    companion object {

        /** Devuelve `null` si el archivo no es válido o no se puede confiar en él. */
        fun parse(raw: String): UpdateInfo? = try {
            val obj = JSONObject(raw)
            val code = obj.optInt("versionCode", 0)
            val name = obj.optString("versionName", "").trim()
            val url = obj.optString("apkUrl", "").trim()

            if (code <= 0 || name.isEmpty() || !url.startsWith("https://")) {
                null
            } else {
                UpdateInfo(
                    versionCode = code,
                    versionName = name,
                    apkUrl = url,
                    sha256 = obj.optString("sha256", "").trim().lowercase().ifEmpty { null },
                    notes = obj.optString("notes", "").trim().ifEmpty { null }
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
