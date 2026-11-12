package pe.radiosdelperu.app.player

import android.content.Context

/**
 * Recuerda la última emisora escuchada.
 *
 * Sirve para dos cosas:
 *  - la pantalla principal sepa qué estaba sonando si el proceso se reinicia;
 *  - Android pueda reanudar desde la tarjeta de la pantalla de bloqueo, incluso
 *    cuando el servicio ya no estaba vivo (ver `PlaybackService.onPlaybackResumption`).
 */
class SessionState(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastStationId(): String? = prefs.getString(KEY_STATION, null)

    fun saveStation(stationId: String) {
        prefs.edit().putString(KEY_STATION, stationId).apply()
    }

    /** Se llama solo cuando el usuario pulsa DETENER: ahí sí queremos olvidar. */
    fun clear() {
        prefs.edit().remove(KEY_STATION).apply()
    }

    private companion object {
        const val PREFS = "player_state"
        const val KEY_STATION = "last_station"
    }
}
