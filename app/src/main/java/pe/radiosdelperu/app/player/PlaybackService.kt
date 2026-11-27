package pe.radiosdelperu.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import pe.radiosdelperu.app.MainActivity
import pe.radiosdelperu.app.catalog.CatalogRepository
import pe.radiosdelperu.app.catalog.Station

/**
 * Servicio de reproducción en segundo plano.
 *
 * MediaSessionService se encarga del servicio en primer plano, la notificación con
 * controles, los controles de la pantalla de bloqueo y la integración con Bluetooth
 * y Android Auto.
 *
 * Aquí también vive todo lo que debe funcionar con la pantalla apagada:
 *  - reanudar siempre **en vivo** (delegado en [LiveRadioPlayer]);
 *  - probar las URLs alternativas de la emisora;
 *  - reintentar con espera cuando se cae la conexión;
 *  - recordar la última emisora para poder reanudarla aunque el proceso ya no exista.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var livePlayer: LiveRadioPlayer
    private lateinit var sessionState: SessionState
    private lateinit var catalog: CatalogRepository

    private var stations: List<Station> = emptyList()
    private var station: Station? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var stallRunnable: Runnable? = null
    private var candidateIndex = 0
    private var retryCount = 0
    private var slowRetryCount = 0

    /** Estado "de espera" que no se puede deducir del reproductor: reintentando o caída. */
    private var pending: PlaybackStatus.Phase? = null

    /**
     * Hubo alguna vez una emisora cargada. Sirve para no confundir "el usuario pulsó
     * DETENER" (hay que olvidar la emisora) con "el servicio acaba de arrancar y aún
     * no hay nada" (hay que recordarla para poder reanudar).
     */
    private var hasLoadedStation = false

    /**
     * El usuario pausó a propósito. Sirve para no reconectar solos si el stream falla
     * mientras está en pausa: sonaría sin que nadie lo haya pedido.
     */
    private var pausedByUser = false

    override fun onCreate() {
        super.onCreate()

        sessionState = SessionState(this)
        catalog = CatalogRepository(this)
        stations = catalog.load().stations

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        livePlayer = LiveRadioPlayer(player)
        player.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, livePlayer)
            .setSessionActivity(openAppIntent())
            .setCallback(SessionCallback())
            // En Android 12+ el sistema a veces impide arrancar en segundo plano. Si pasa,
            // al menos el botón de play queda a la vista para reintentar desde la app.
            .setShowPlayButtonIfPlaybackIsSuppressed(true)
            .build()

        // Si vuelve la red, reintentar de inmediato en vez de esperar al siguiente intento.
        try {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Sin permiso o sin red: los reintentos normales cubren el caso.
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // ------------------------------------------------------------ eventos player

    private val playerListener = object : Player.Listener {

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                pausedByUser = false
                // Si veníamos de "no disponible", pulsar play es un reintento manual.
                if (pending == PlaybackStatus.Phase.UNAVAILABLE) {
                    pending = null
                    candidateIndex = 0
                    retryCount = 0
                    slowRetryCount = 0
                }
            } else {
                livePlayer.onPaused()
                cancelStallWatchdog()
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    pausedByUser = true
                }
            }
            refreshStatus()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                livePlayer.onLive()
                pausedByUser = false
                retryCount = 0
                slowRetryCount = 0
                pending = null
            }
            refreshStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING && pending != PlaybackStatus.Phase.UNAVAILABLE) {
                pending = null
            }
            if (playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                armStallWatchdog()
            } else if (playbackState != Player.STATE_BUFFERING) {
                cancelStallWatchdog()
            }
            refreshStatus()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            livePlayer.onTrackChanged()
            val id = mediaItem?.mediaId

            if (id != station?.id) {
                station = stations.firstOrNull { it.id == id }
                candidateIndex = 0
                retryCount = 0
                slowRetryCount = 0
                pending = null
            }

            if (id != null) {
                hasLoadedStation = true
                sessionState.saveStation(id)
            } else if (hasLoadedStation) {
                // El usuario pulsó DETENER: sí queremos olvidar la emisora.
                hasLoadedStation = false
                pausedByUser = false
                sessionState.clear()
            }
            refreshStatus()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (player.mediaItemCount == 0 && hasLoadedStation) {
                cancelRetry()
                hasLoadedStation = false
                station = null
                pending = null
                sessionState.clear()
                refreshStatus()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "error de reproducción ${error.errorCodeName}: ${error.message}")
            onPlaybackError()
        }
    }

    private fun refreshStatus() {
        val current = station
        val phase: PlaybackStatus.Phase = when {
            current == null || player.currentMediaItem == null -> PlaybackStatus.Phase.IDLE
            pending != null -> pending as PlaybackStatus.Phase
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.Phase.CONNECTING
            player.isPlaying -> PlaybackStatus.Phase.LIVE
            player.playWhenReady -> PlaybackStatus.Phase.CONNECTING
            else -> PlaybackStatus.Phase.PAUSED
        }

        PlaybackStatus.update(
            PlaybackStatus.State(
                stationId = if (phase == PlaybackStatus.Phase.IDLE) null else current?.id,
                phase = phase
            )
        )
    }

    // -------------------------------------------------------------- reintentos

    private fun onPlaybackError() {
        val current = station ?: return
        cancelRetry()
        livePlayer.onError()

        // En pausa no se reconecta solo: el usuario no ha pedido que suene.
        if (pausedByUser) {
            pending = null
            refreshStatus()
            return
        }

        // 1. Otra URL de la misma emisora.
        if (candidateIndex < current.candidates.lastIndex) {
            candidateIndex++
            retryCount = 0
            startStation(current, candidateIndex)
            return
        }

        // 2. Reintentos rápidos con espera creciente.
        if (retryCount < MAX_RETRIES) {
            val delayMs = BACKOFF_MS[retryCount.coerceAtMost(BACKOFF_MS.lastIndex)]
            retryCount++
            scheduleRetry(current, delayMs)
            return
        }

        // 3. Reintentos lentos: un corte de internet largo no debe dejar la radio muda
        //    para siempre. Se sigue intentando cada 30 s durante 10 minutos más.
        if (slowRetryCount < MAX_SLOW_RETRIES) {
            slowRetryCount++
            scheduleRetry(current, SLOW_RETRY_MS)
            return
        }

        pending = PlaybackStatus.Phase.UNAVAILABLE
        refreshStatus()
    }

    private fun scheduleRetry(current: Station, delayMs: Long) {
        pending = PlaybackStatus.Phase.RETRYING
        refreshStatus()

        val runnable = Runnable {
            retryRunnable = null
            startStation(current, candidateIndex)
        }
        retryRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /**
     * Vigila que el stream no se quede colgado sin dar error (pasa al cambiar de WiFi a
     * datos: el socket viejo se queda mudo). Si sigue "conectando" demasiado tiempo, se
     * vuelve a pedir el stream.
     */
    private fun armStallWatchdog() {
        cancelStallWatchdog()
        val runnable = Runnable {
            stallRunnable = null
            if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
                val current = station ?: return@Runnable
                Log.i(TAG, "stream colgado ${STALL_TIMEOUT_MS} ms: reconectando")
                retryCount = 0
                startStation(current, candidateIndex)
            }
        }
        stallRunnable = runnable
        handler.postDelayed(runnable, STALL_TIMEOUT_MS)
    }

    private fun cancelStallWatchdog() {
        stallRunnable?.let { handler.removeCallbacks(it) }
        stallRunnable = null
    }

    // ------------------------------------------------------------------ red

    private val connectivity: ConnectivityManager? by lazy {
        try {
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cuando vuelve la red no esperamos al siguiente reintento: si estábamos sin conexión,
     * reintentamos al instante.
     *
     * OJO: este callback llega en un hilo del sistema, no en el principal. Todo el estado
     * del servicio se toca en el hilo principal, así que se reenvía con `handler.post`.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                val phase = pending ?: return@post
                if (phase != PlaybackStatus.Phase.RETRYING &&
                    phase != PlaybackStatus.Phase.UNAVAILABLE
                ) return@post

                val current = station ?: return@post
                Log.i(TAG, "red disponible: reintento inmediato")
                cancelRetry()
                retryCount = 0
                slowRetryCount = 0
                startStation(current, candidateIndex)
            }
        }
    }

    private fun startStation(target: Station, index: Int) {
        val item = mediaItemFor(target, index) ?: return
        pending = null
        hasLoadedStation = true
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    // ------------------------------------------------------- reanudar el sistema

    /**
     * Android llama aquí cuando el usuario pulsa play en la tarjeta de la pantalla de
     * bloqueo y la sesión ya no tenía nada cargado (app cerrada, teléfono reiniciado o
     * servicio detenido). Devolvemos la última emisora; Media3 la prepara y la arranca.
     */
    private inner class SessionCallback : MediaSession.Callback {

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        // La variante nueva (con `isForPlayback`) todavía es @UnstableApi.
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Si el usuario pulsó DETENER, no hay nada que reanudar: mejor un no-op
            // que arrancar una emisora que nadie eligió.
            val target = sessionState.lastStationId()
                ?.let { id -> stations.firstOrNull { it.id == id } }
                ?: return Futures.immediateFailedFuture(
                    UnsupportedOperationException("No hay emisora recordada")
                )

            val item = mediaItemFor(target, 0)
                ?: return Futures.immediateFailedFuture(
                    IllegalStateException("Emisora sin URL: ${target.id}")
                )

            station = target
            hasLoadedStation = true
            candidateIndex = 0
            retryCount = 0
            pending = null
            sessionState.saveStation(target.id)

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(item),
                    0,
                    C.TIME_UNSET // posición por defecto = el directo, ahora
                )
            )
        }
    }

    // ---------------------------------------------------------------- ciclo vida

    /**
     * Si el usuario desliza la app fuera de "recientes" mientras escucha, la radio debe
     * seguir. Solo cerramos el servicio si no hay nada reproduciéndose; en pausa Android
     * deja la tarjeta de reanudación y esta se resuelve con [SessionCallback].
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        cancelRetry()
        cancelStallWatchdog()
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // No estaba registrado.
        }
        player.removeListener(playerListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        // Si el servicio se va, la pantalla no debe quedarse mostrando "EN VIVO".
        PlaybackStatus.update(PlaybackStatus.State())
        super.onDestroy()
    }

    companion object {

        private const val TAG = "PlaybackService"
        private const val MAX_RETRIES = 4
        private val BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L)

        /** Reintentos lentos: cada 30 s durante 10 minutos (20 intentos). */
        private const val SLOW_RETRY_MS = 30_000L
        private const val MAX_SLOW_RETRIES = 20

        /** Si sigue "conectando" tanto tiempo sin dar error, se reconecta. */
        private const val STALL_TIMEOUT_MS = 25_000L

        /** El mismo MediaItem que usa la pantalla y la reanudación del sistema. */
        fun mediaItemFor(station: Station, candidateIndex: Int = 0): MediaItem? {
            val url = station.candidates.getOrNull(candidateIndex) ?: return null
            return MediaItem.Builder()
                .setUri(url)
                .setMediaId(station.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.tagline)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }
}
