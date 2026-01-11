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
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.IOException


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

    // https://developer.android.com/develop/connectivity/usb/accessory
    private val maxBufLengthBytes: Int = 16384

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    private var buffer: ByteArray = ByteArray(maxBufLengthBytes)

    private var stopComm: Boolean = false



    fun openAccessory() {
        // https://developer.android.com/develop/connectivity/usb/accessory#communicating-a
        Log.d(TAG, "open accessory")
        fileDescriptor = usbManager.openAccessory(accessory)
        fileDescriptor?.fileDescriptor?.also { fd ->
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)
            val thread = Thread(null, this, "AccessoryCommunicatorThread")
            thread.start()
        }
    }

    fun closeAccessory() {
        // https://developer.android.com/develop/connectivity/usb/accessory#terminating-a
        if(fileDescriptor != null) {
            Log.d(TAG, "closing accessory")
            stopComm = true
            fileDescriptor?.close()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun run() {
        Log.d(TAG, "All systems go")

        val inputStreamReader: InputStreamReader = InputStreamReader(inputStream)
        // https://developer.android.com/develop/connectivity/usb/accessory
        val bufferedReader: BufferedReader = BufferedReader(inputStreamReader, maxBufLengthBytes)
        if(inputStream != null) {
            while (!stopComm) {
                try {
                    var bytesRead = inputStream!!.read(buffer)

                    if(bytesRead > 0)
                    {
                        for(i in 0..<bytesRead)
                        {
                            // how to decode this?
                            // data sent by open AA: ?? 51 ??
                            // data received: 00 03 00 06 00 01 00 01 00 01
                            // https://github.com/f1xpl/aasdk/blob/development/src/USB/AccessoryModeProtocolVersionQuery.cpp
                            Log.d(TAG, buffer[i].toHexString())
                        }
                    }

                } catch (e: IOException) {
                    /// TODO: handle more gracefully
                    Log.d(TAG, "Connection closed")
                    stopComm = true
                }
            }
        }
    }
}


class AutoUsbHandler : BroadcastReceiver() {
    val ACTION_USB_ACCESSORY_ATTACHED = UsbManager.ACTION_USB_ACCESSORY_ATTACHED
    val ACTION_USB_ACCESSORY_DETACHED = UsbManager.ACTION_USB_ACCESSORY_DETACHED

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

                Log.e(TAG, "Unhandled case: when does this occur?")

            }
        }
        else if(ACTION_USB_ACCESSORY_DETACHED == intent.action) {
            synchronized(this) {
                Log.d(TAG, "Accessory disconnected")
                val accessory: UsbAccessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)!!
                logAccessory(accessory)

                accessory.apply {
                    // call your method that cleans up and closes communication with the accessory
                    communicator?.closeAccessory()
                }

            }
        }
        else if(ACTION_USB_ACCESSORY_HANDSHAKE == intent.action) {
            synchronized(this) {
                Log.d(TAG,
                    "======= Accessory handshake detected:  $ACTION_USB_ACCESSORY_HANDSHAKE ==== "
                )
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
        else {
            Log.e(TAG, "Unhandled intent")
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