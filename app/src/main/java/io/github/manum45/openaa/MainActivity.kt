package io.github.manum45.openaa

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import io.github.manum45.openaa.ui.theme.OpenAATheme
import kotlin.getValue

val TAG = "OpenAA"

val logCatText = mutableStateOf("=== Logcat ===\n")

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private var mainService: MainService? = null
    private var isBound = false
    private var pendingProjectionData: Intent? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
            isBound = true
            Log.d(TAG, "MainService bound")
            
            pendingProjectionData?.let { data ->
                completeMediaProjection(data)
                pendingProjectionData = null
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            mainService = null
        }
    }

    private fun completeMediaProjection(data: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data)
        Log.d(TAG, "MediaProjection obtained after service bound")
        usbHandler.setMediaProjection(mediaProjection!!)
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, MainService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            
            pendingProjectionData = result.data
        } else {
            Log.e(TAG, "MediaProjection permission denied")
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "RECORD_AUDIO permission granted")
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            Log.e(TAG, "RECORD_AUDIO permission denied")
        }
    }

    var usbHandler: AutoUsbHandler = AutoUsbHandler()
    var receiverRegisterd = false

    var localMusicPlayer: LocalMusicPlayer? = null

    @OptIn(ExperimentalStdlibApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Creating MainActivity")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenAATheme {
                OpenAAApp(usbHandler)
            }
        }

        //// https://stackoverflow.com/a/59511458
        //val logCatViewModel by viewModels<LogcatViewModel>()

        //logCatViewModel.logCatOutput().observe(this, Observer { logMessage ->
        //    logCatText.value += "$logMessage\n"
        //})

        usbHandler.setup(getSystemService(Context.USB_SERVICE) as UsbManager)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)

        if(!receiverRegisterd) {
            Log.d(TAG, "Registering receiver")
            receiverRegisterd = true
            val filter = IntentFilter(usbHandler.ACTION_USB_ACCESSORY_ATTACHED)
            filter.addAction(usbHandler.ACTION_USB_ACCESSORY_DETACHED)
            filter.addAction(usbHandler.ACTION_USB_ACCESSORY_HANDSHAKE)
            this.registerReceiver(usbHandler, filter)
        }

        // just to test the file
        // localMusicPlayer = LocalMusicPlayer(this.baseContext)
        // localMusicPlayer?.PlayTestMusic()
    }


    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        this.unregisterReceiver(usbHandler)
        val serviceIntent = Intent(this, MainService::class.java)
        stopService(serviceIntent)
        super.onDestroy()
    }
}

@Composable
fun OpenAAApp(usbHandler: AutoUsbHandler) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
        ) { innerPadding -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
                Greeting(
                    name = "Android",
                    modifier = Modifier.padding(innerPadding)
                )
                ActionButton(
                    usbHandler
                )
                LogCatTextView()
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    FAVORITES("Favorites", Icons.Default.Favorite),
    PROFILE("Profile", Icons.Default.AccountBox),
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    OpenAATheme {
        Greeting("Android")
    }
}


@Composable
fun ActionButton(usbHandler: AutoUsbHandler)
{
    Button(
        onClick = {
            Log.d(TAG, "Logging USB connections")
            usbHandler.enumerateDevices()
        }
    )
    {
        Icon(Icons.Default.PlayArrow, "Action")
    }
}

@Composable
fun LogCatTextView()
{
    val content by logCatText
    Text(content, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState(), reverseScrolling = true).horizontalScroll(rememberScrollState()))
}


