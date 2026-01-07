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
import android.util.Log
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



@OptIn(ExperimentalStdlibApi::class)
private val usbReceiver = object : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "USB onReceive " + intent.action)
        if (ACTION_USB_DEVICE_ATTACHED == intent.action) {
            synchronized(this) {
                // in this case the device this app is running on is the host
                Log.d(TAG, "App is now host")
                val device_host: UsbDevice? = intent.parcelable(UsbManager.EXTRA_DEVICE)
                Log.d(TAG,
                    "vendorId: " + device_host?.vendorId?.toHexString()
                        + ", productId: " + device_host?.productId?.toHexString()
                        + ", class: " + device_host?.deviceClass?.toHexString()
                        + ", subclass: " + device_host?.deviceSubclass?.toHexString()
                        + ", protocol: " + device_host?.deviceProtocol?.toHexString()
                )

            }
        }
        else if (ACTION_USB_ACCESSORY_ATTACHED == intent.action) {
            synchronized(this) {
                // in this case the device this app is running on is the accessory
                Log.d(TAG, "App is now accessory")
                val device_acc: UsbAccessory? = intent.parcelable(UsbManager.EXTRA_ACCESSORY)
                Log.d(TAG,
                    "manufacturer: " + device_acc?.manufacturer.toString()
                      + ", model: " + device_acc?.model.toString()
                      + ", version: " + device_acc?.version.toString()
                )
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