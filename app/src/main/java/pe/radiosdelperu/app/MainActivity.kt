package pe.radiosdelperu.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import pe.radiosdelperu.app.catalog.CatalogRepository
import pe.radiosdelperu.app.catalog.Station
import pe.radiosdelperu.app.player.PlaybackService
import pe.radiosdelperu.app.ui.StationAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: StationAdapter
    private lateinit var txtNowTitle: TextView
    private lateinit var txtNowStatus: TextView
    private lateinit var btnStop: Button

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var stations: List<Station> = emptyList()
    private var currentStation: Station? = null
    private var candidateIndex = 0
    private var retryCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    private val repository by lazy { CatalogRepository(this) }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* opcional */ }

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
        btnStop = findViewById(R.id.btnStop)

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

        btnStop.setOnClickListener { stopPlayback() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Nunca cerramos de golpe: la app se minimiza y la radio sigue sonando.
                moveTaskToBack(true)
            }
        })

        renderIdle()
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onStop() {
        retryRunnable?.let { handler.removeCallbacks(it) }
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
            val c = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            controller = c
            c?.addListener(playerListener)
            syncFromPlayer()
        }, ContextCompat.getMainExecutor(this))
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromPlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromPlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlaybackFailed()
        }
    }

    // ---------------------------------------------------------------- acciones

    private fun onStationClicked(station: Station) {
        val c = controller ?: run {
            Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show()
            return
        }

        if (c.currentMediaItem?.mediaId == station.id) {
            if (c.isPlaying) stopPlayback() else resume()
            return
        }

        playStation(station)
    }

    private fun playStation(station: Station) {
        val c = controller ?: return
        prepareNotificationPermission()

        cancelRetry()
        retryCount = 0
        candidateIndex = 0
        currentStation = station

        val item = MediaItem.Builder()
            .setUri(station.candidates[0])
            .setMediaId(station.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.tagline)
                    .build()
            )
            .build()

        c.setMediaItem(item)
        c.prepare()
        c.play()
        showStatus(getString(R.string.status_connecting), live = true)
        adapter.setActive(station.id)
    }

    private fun resume() {
        controller?.play()
    }

    private fun stopPlayback() {
        cancelRetry()
        controller?.stop()
        controller?.clearMediaItems()
        currentStation = null
        adapter.setActive(null)
        renderIdle()
    }

    private fun onPlaybackFailed() {
        val station = currentStation ?: return
        cancelRetry()

        // Primero probamos las URLs alternativas de la misma emisora.
        if (candidateIndex < station.candidates.lastIndex) {
            candidateIndex++
            controller?.let { c ->
                val item = MediaItem.Builder()
                    .setUri(station.candidates[candidateIndex])
                    .setMediaId(station.id)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(station.name)
                            .setArtist(station.tagline)
                            .build()
                    )
                    .build()
                c.setMediaItem(item)
                c.prepare()
                c.play()
            }
            showStatus(getString(R.string.status_connecting), live = true)
            return
        }

        if (retryCount >= 4) {
            showStatus(getString(R.string.status_unavailable), live = false)
            return
        }

        val delayMs = when (retryCount) {
            0 -> 1_500L
            1 -> 3_000L
            2 -> 6_000L
            else -> 12_000L
        }
        retryCount++
        showStatus(getString(R.string.status_retrying), live = false)

        val runnable = Runnable {
            controller?.prepare()
            controller?.play()
        }
        retryRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    // ---------------------------------------------------------------- estado UI

    private fun syncFromPlayer() {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId
        val station = stations.firstOrNull { it.id == mediaId }
            ?: currentStation?.takeIf { it.id == mediaId }
        if (station != null) currentStation = station

        val active = c.isPlaying || c.playWhenReady
        adapter.setActive(if (active) mediaId else null)

        when {
            station == null -> renderIdle()
            c.playbackState == Player.STATE_BUFFERING -> {
                txtNowTitle.text = station.name
                showStatus(getString(R.string.status_connecting), live = true)
                btnStop.text = getString(R.string.action_stop)
            }
            c.isPlaying -> {
                txtNowTitle.text = station.name
                showStatus(getString(R.string.status_live), live = true)
                btnStop.text = getString(R.string.action_stop)
            }
            else -> {
                txtNowTitle.text = station.name
                showStatus(getString(R.string.status_paused), live = false)
                btnStop.text = getString(R.string.action_listen)
            }
        }
    }

    private fun applyCatalog(snapshot: CatalogRepository.Snapshot) {
        val currentId = currentStation?.id
        stations = snapshot.stations
        adapter.submit(stations)
        currentStation = stations.firstOrNull { it.id == currentId } ?: currentStation
        syncFromPlayer()
    }

    private fun renderIdle() {
        txtNowTitle.text = getString(R.string.idle_title)
        showStatus(getString(R.string.idle_hint), live = false)
        btnStop.visibility = View.GONE
    }

    private fun showStatus(text: String, live: Boolean) {
        btnStop.visibility = View.VISIBLE
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

    override fun onDestroy() {
        cancelRetry()
        controller?.removeListener(playerListener)
        super.onDestroy()
    }
}
