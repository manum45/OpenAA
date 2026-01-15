package io.github.manum45.openaa

import android.R
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    var usbHandler: AutoUsbHandler = AutoUsbHandler()
    var receiverRegisterd = false

    companion object {
        init {
            System.loadLibrary("AAServer")
        }

        external fun AAServerHello(): Int
    }


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

        // https://stackoverflow.com/a/59511458
        val logCatViewModel by viewModels<LogcatViewModel>()

        logCatViewModel.logCatOutput().observe(this, Observer { logMessage ->
            logCatText.value += "$logMessage\n"
        })

        usbHandler.setup(getSystemService(Context.USB_SERVICE) as UsbManager)
        if(!receiverRegisterd) {
            Log.d(TAG, "Registering receiver")
            receiverRegisterd = true
            val filter = IntentFilter(usbHandler.ACTION_USB_ACCESSORY_ATTACHED)
            filter.addAction(usbHandler.ACTION_USB_ACCESSORY_DETACHED)
            filter.addAction(usbHandler.ACTION_USB_ACCESSORY_HANDSHAKE)
            this.registerReceiver(usbHandler, filter)
        }

        Log.d(TAG, "AAServer says " + AAServerHello().toHexString())

    }


    override fun onDestroy() {
        this.unregisterReceiver(usbHandler)
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
