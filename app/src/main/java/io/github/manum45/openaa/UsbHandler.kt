package io.github.manum45.openaa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.Tag
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.ContextCompat.registerReceiver
import kotlinx.coroutines.Runnable
import java.io.FileInputStream
import java.io.FileOutputStream


//https://stackoverflow.com/questions/73019160/the-getparcelableextra-method-is-deprecated
inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
    SDK_INT >= 33 -> getParcelableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
}

inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? = when {
    SDK_INT >= 33 -> getParcelable(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelable(key) as? T
}


class AccessoryCommunicator(val accessory: UsbAccessory, val usbManager: UsbManager) : Runnable{
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null



    fun openAccessory() {
        Log.d(TAG, "openAccessory")
        fileDescriptor = usbManager.openAccessory(accessory)
        fileDescriptor?.fileDescriptor?.also { fd ->
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)
            val thread = Thread(null, this, "AccessoryCommunicatorThread")
            thread.start()
        }
    }

    override fun run() {
        Log.d(TAG, "All systems go")
    }
}


class AutoUsbHandler : BroadcastReceiver() {

    val ACTION_USB_DEVICE_ATTACHED = UsbManager.ACTION_USB_DEVICE_ATTACHED
    val ACTION_USB_ACCESSORY_ATTACHED = UsbManager.ACTION_USB_ACCESSORY_ATTACHED
    val ACTION_USB_ACCESSORY_HANDSHAKE = "android.hardware.usb.action.USB_ACCESSORY_HANDSHAKE"


    private var usbManager : UsbManager? = null

    private var communicator: AccessoryCommunicator? = null


    fun logAccessory(accessory: UsbAccessory) {
        Log.d(TAG,
            "manufacturer: " + accessory.manufacturer.toString()
                    + ", model: " + accessory.model.toString()
                    + ", version: " + accessory.version.toString()
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun logDevice(device: UsbDevice) {
        Log.d(TAG,
            "vendorId: " + device.vendorId.toHexString()
                    + ", productId: " + device.productId.toHexString()
                    + ", class: " + device.deviceClass.toHexString()
                    + ", subclass: " + device.deviceSubclass.toHexString()
                    + ", protocol: " + device.deviceProtocol.toHexString()
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "USB onReceive " + intent.action)


        if (ACTION_USB_ACCESSORY_ATTACHED == intent.action) {
            synchronized(this) {
                // in this case the device this app is running on is the USB client, connected to
                // an "Accessory" that is usb host
                Log.d(TAG, "Connected device is host")
                val device_acc: UsbAccessory = intent.parcelable(UsbManager.EXTRA_ACCESSORY)!!
                logAccessory(device_acc)

            }
        }
        else if (ACTION_USB_DEVICE_ATTACHED == intent.action) {
            synchronized(this) {
                // in this case the device this app is running on is the host
                Log.d(TAG, "This device is host")
                val device_host: UsbDevice = intent.parcelable(UsbManager.EXTRA_DEVICE)!!
                logDevice(device_host)

            }
        }
        else if(ACTION_USB_ACCESSORY_HANDSHAKE == intent.action) {
            synchronized(this) {
                Log.d(TAG, "======= Accessory handshake detected:  " + ACTION_USB_ACCESSORY_HANDSHAKE + " ==== ")
                //val device_acc: UsbAccessory? = intent.parcelable(UsbManager.EXTRA_ACCESSORY)
                // val start: Boolean? = intent.parcelable("android.hardware.usb.extra.ACCESSORY_START")

                // Log.d(TAG, "Start Accessory received: " + start)\

                var device_acc: UsbAccessory? = null

                val accessoryList: Array<out UsbAccessory>? = usbManager?.accessoryList

                if(accessoryList != null) {
                    device_acc = accessoryList[0]
                }

                if(device_acc == null) {
                    Log.d(TAG, "no accessory found")
                }
                else {
                    logAccessory(device_acc)

                    if(usbManager  == null) {
                        Log.e(TAG, "ERROR: trying to start communicating, but usbManager is null")
                    }
                    else {
                        communicator = AccessoryCommunicator(device_acc, usbManager!!)
                        communicator!!.openAccessory()
                    }
                }
            }
        }
    }

    fun setup(usbMan: UsbManager) {
        usbManager = usbMan
    }

    fun enumerateDevices(){
        if(usbManager == null)
        {
            Log.e(TAG, "ERROR: usbManager is null")
        }
        Log.d(TAG, "USB Devices (this device is host):")
        val deviceList: HashMap<String, UsbDevice>? = usbManager?.deviceList
        deviceList?.values?.forEach { device ->
            logDevice(device)
        }
        Log.d(TAG, "USB Accessories (connected device is host):")
        val accessoryList: Array<out UsbAccessory>? = usbManager?.accessoryList
        accessoryList?.forEach { accessory ->
            logAccessory(accessory)
        }

    }
}