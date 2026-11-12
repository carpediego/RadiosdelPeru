package pe.radiosdelperu.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import pe.radiosdelperu.app.catalog.CatalogRepository
import pe.radiosdelperu.app.catalog.Station
import pe.radiosdelperu.app.player.PlaybackService
import pe.radiosdelperu.app.player.PlaybackStatus
import pe.radiosdelperu.app.ui.StationAdapter

/**
 * Pantalla única.
 *
 * Ya no manda en la reproducción: el que decide (y el que reintenta) es
 * [PlaybackService], porque tiene que funcionar también con la pantalla bloqueada.
 * Aquí solo se pinta el estado que publica el servicio y se envían las órdenes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: StationAdapter
    private lateinit var txtNowTitle: TextView
    private lateinit var txtNowStatus: TextView
    private lateinit var btnAction: Button

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var stations: List<Station> = emptyList()

    private val repository by lazy { CatalogRepository(this) }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* opcional */ }

    private val onStatusChanged: (PlaybackStatus.State) -> Unit = { state ->
        render(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        txtNowTitle = findViewById(R.id.txtNowTitle)
        txtNowStatus = findViewById(R.id.txtNowStatus)
        btnAction = findViewById(R.id.btnStop)

        adapter = StationAdapter { station -> onStationClicked(station) }
        val list = findViewById<RecyclerView>(R.id.rvStations)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        stations = repository.load().stations
        adapter.submit(stations)

        // El catálogo remoto se consulta en segundo plano; si hay versión nueva, se
        // refresca la lista sin molestar al usuario y sin cortar la radio.
        repository.refreshAsync { snapshot -> applyCatalog(snapshot) }

        btnAction.setOnClickListener { onActionClicked() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Nunca cerramos de golpe: la app se minimiza y la radio sigue sonando.
                moveTaskToBack(true)
            }
        })

        render(PlaybackStatus.current)
    }

    override fun onStart() {
        super.onStart()
        PlaybackStatus.addListener(onStatusChanged)
        connectController()
    }

    override fun onStop() {
        PlaybackStatus.removeListener(onStatusChanged)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = try {
                future.get()
            } catch (e: Exception) {
                null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------- acciones

    /**
     * El único botón grande. Con el mismo botón se escucha, se pausa y se reanuda:
     *  - sonando / conectando  → DETENER (corta del todo y quita la tarjeta);
     *  - en pausa              → ESCUCHAR (vuelve al directo, en el momento actual).
     */
    private fun onActionClicked() {
        val c = controller ?: run {
            Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show()
            return
        }

        when (PlaybackStatus.current.phase) {
            PlaybackStatus.Phase.PAUSED,
            PlaybackStatus.Phase.UNAVAILABLE -> c.play()

            PlaybackStatus.Phase.LIVE,
            PlaybackStatus.Phase.CONNECTING,
            PlaybackStatus.Phase.RETRYING -> stopPlayback(c)

            PlaybackStatus.Phase.IDLE -> Unit
        }
    }

    private fun onStationClicked(station: Station) {
        val c = controller ?: run {
            Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show()
            return
        }

        val state = PlaybackStatus.current
        if (state.stationId != station.id) {
            playStation(c, station)
            return
        }

        when (state.phase) {
            PlaybackStatus.Phase.LIVE,
            PlaybackStatus.Phase.CONNECTING,
            PlaybackStatus.Phase.RETRYING -> stopPlayback(c)

            PlaybackStatus.Phase.PAUSED,
            PlaybackStatus.Phase.UNAVAILABLE -> c.play()

            PlaybackStatus.Phase.IDLE -> playStation(c, station)
        }
    }

    private fun playStation(c: MediaController, station: Station) {
        prepareNotificationPermission()

        val item = PlaybackService.mediaItemFor(station) ?: return
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    private fun stopPlayback(c: MediaController) {
        c.stop()
        c.clearMediaItems()
        // El servicio ve la lista vacía, borra la emisora recordada y deja el estado en reposo.
    }

    // ---------------------------------------------------------------- estado UI

    private fun render(state: PlaybackStatus.State) {
        val station = state.stationId?.let { id -> stations.firstOrNull { it.id == id } }

        if (station == null || state.phase == PlaybackStatus.Phase.IDLE) {
            adapter.setActive(null)
            renderIdle()
            return
        }

        adapter.setActive(
            if (state.phase == PlaybackStatus.Phase.UNAVAILABLE) null else station.id
        )
        txtNowTitle.text = station.name
        btnAction.text = when (state.phase) {
            PlaybackStatus.Phase.PAUSED,
            PlaybackStatus.Phase.UNAVAILABLE -> getString(R.string.action_listen)

            else -> getString(R.string.action_stop)
        }

        when (state.phase) {
            PlaybackStatus.Phase.CONNECTING -> showStatus(getString(R.string.status_connecting), live = true)
            PlaybackStatus.Phase.LIVE -> showStatus(getString(R.string.status_live), live = true)
            PlaybackStatus.Phase.PAUSED -> showStatus(getString(R.string.status_paused), live = false)
            PlaybackStatus.Phase.RETRYING -> showStatus(getString(R.string.status_retrying), live = false)
            PlaybackStatus.Phase.UNAVAILABLE -> showStatus(getString(R.string.status_unavailable), live = false)
            PlaybackStatus.Phase.IDLE -> renderIdle()
        }
    }

    private fun applyCatalog(snapshot: CatalogRepository.Snapshot) {
        stations = snapshot.stations
        adapter.submit(stations)
        render(PlaybackStatus.current)
    }

    private fun renderIdle() {
        txtNowTitle.text = getString(R.string.idle_title)
        txtNowStatus.text = getString(R.string.idle_hint)
        txtNowStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        btnAction.visibility = View.GONE
    }

    private fun showStatus(text: String, live: Boolean) {
        btnAction.visibility = View.VISIBLE
        txtNowStatus.text = text
        val color = if (live) R.color.live else R.color.text_secondary
        txtNowStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun prepareNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
