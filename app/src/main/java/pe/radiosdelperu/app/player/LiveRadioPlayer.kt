package pe.radiosdelperu.app.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Envoltorio del reproductor que garantiza volver **al directo** al reanudar.
 *
 * Un directo no tiene "cinta": lo que queda en el buffer al pausar ya pasó y, si la pausa
 * dura, el servidor cierra la conexión (Icecast corta oyentes inactivos, HLS borra los
 * segmentos viejos). Por eso, al recibir play después de una pausa, se vuelve a abrir el
 * stream desde el momento actual.
 *
 * La decisión se toma aquí y no en la pantalla porque todos los controles (notificación,
 * pantalla de bloqueo, audífonos Bluetooth, Android Auto, reloj) acaban llamando a
 * [play] o a [setPlayWhenReady]. Así se arregla en un solo sitio.
 */
class LiveRadioPlayer(delegate: Player) : ForwardingPlayer(delegate) {

    /** Se puso en pausa y todavía no se ha reanudado en vivo. */
    private var pausedWaitingForLive = false

    /** Evita recargar dos veces si el control pide play() y setPlayWhenReady(true). */
    private var reloading = false

    /** El servicio avisa cuando el reproductor se pausa de verdad. */
    fun onPaused() {
        pausedWaitingForLive = true
    }

    /** Ya se está oyendo: estamos en vivo. */
    fun onLive() {
        pausedWaitingForLive = false
    }

    /** Cambió la emisora: no hay nada que "reanudar". */
    fun onTrackChanged() {
        pausedWaitingForLive = false
    }

    /** Hubo un error: la reconexión la lleva el servicio, no este envoltorio. */
    fun onError() {
        pausedWaitingForLive = false
    }

    override fun play() {
        restartAtLiveEdgeIfPaused()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            restartAtLiveEdgeIfPaused()
        } else {
            pausedWaitingForLive = true
        }
        super.setPlayWhenReady(playWhenReady)
    }

    private fun restartAtLiveEdgeIfPaused() {
        if (!pausedWaitingForLive || reloading) return

        val item = currentMediaItem ?: return
        pausedWaitingForLive = false

        // Si está en IDLE (por ejemplo tras un error) el reproductor ya va a cargar
        // desde el principio: abrir conexión nueva otra vez solo duplicaría el trabajo.
        if (playbackState == Player.STATE_IDLE || isPlaying) return

        reloading = true
        try {
            // Volver a poner el mismo MediaItem reinicia la posición y obliga a pedir
            // el stream de nuevo: eso es justamente "el momento actual".
            setMediaItem(item)
            prepare()
        } finally {
            reloading = false
        }
    }
}
