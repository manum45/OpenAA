package io.github.manum45.openaa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Parcelable
import androidx.core.content.ContextCompat.registerReceiver


private const val ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
private const val ACTION_USB_ACCESSORY_ATTACHED = "android.hardware.usb.action.USB_ACCESSORY_ATTACHED"


//https://stackoverflow.com/questions/73019160/the-getparcelableextra-method-is-deprecated
inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
    SDK_INT >= 33 -> getParcelableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
}

inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? = when {
    SDK_INT >= 33 -> getParcelable(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelable(key) as? T
}



private val usbReceiver = object : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        if (ACTION_USB_DEVICE_ATTACHED == intent.action) {
            synchronized(this) {
                // in this case the device this app is running on is the host
                val device_host: UsbDevice? = intent.parcelable(UsbManager.EXTRA_DEVICE)
            }
        }
        else if (ACTION_USB_ACCESSORY_ATTACHED == intent.action) {
            synchronized(this) {
                // in this case the device this app is running on is the accessory
                val device_acc: UsbAccessory? = intent.parcelable(UsbManager.EXTRA_ACCESSORY)
            }
        }
    }
}

class UsbHandler {
    fun setup(mainActivity: MainActivity) {
        val filter = IntentFilter(ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(ACTION_USB_ACCESSORY_ATTACHED)
        mainActivity.registerReceiver(usbReceiver, filter)
    }
}