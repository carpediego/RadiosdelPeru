package pe.radiosdelperu.app.player

/**
 * Estado de reproducción compartido entre el servicio y la pantalla.
 *
 * El servicio y la actividad viven en el mismo proceso, así que no hace falta IPC:
 * el servicio publica el estado y la pantalla lo dibuja. Se escribe y se lee siempre
 * en el hilo principal.
 */
object PlaybackStatus {

    enum class Phase {
        /** No hay nada cargado. */
        IDLE,
        /** Abriendo el stream. */
        CONNECTING,
        /** Sonando en directo. */
        LIVE,
        /** En pausa a petición del usuario. */
        PAUSED,
        /** Falló la conexión y el servicio está reintentando. */
        RETRYING,
        /** Se agotaron los reintentos. */
        UNAVAILABLE
    }

    data class State(
        val stationId: String? = null,
        val phase: Phase = Phase.IDLE
    )

    private val listeners = LinkedHashSet<(State) -> Unit>()

    var current: State = State()
        private set

    fun update(state: State) {
        if (state == current) return
        current = state
        listeners.toList().forEach { it(state) }
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }
}
