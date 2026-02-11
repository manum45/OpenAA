package io.github.manum45.openaa

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Runnable
import java.io.FileInputStream
import java.io.FileOutputStream


class AccessoryCommunicator(val accessory: UsbAccessory, val usbManager: UsbManager, context: Context) :
    Runnable {

    // https://developer.android.com/develop/connectivity/usb/accessory
    private val maxBufLengthBytes: Int = 16384

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    private var inBuffer: ByteArray = ByteArray(maxBufLengthBytes)

    private var stopComm: Boolean = false

    private var usbStreamer: UsbStreamer = UsbStreamer();

    private var messageHandler: MessageHandler = MessageHandler(usbStreamer, context);




    fun openAccessory() {
        // https://developer.android.com/develop/connectivity/usb/accessory#communicating-a
        Log.d(TAG, "open accessory")
        fileDescriptor = usbManager.openAccessory(accessory)
        fileDescriptor?.fileDescriptor?.also { fd ->
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)
            usbStreamer.registerStreams(inputStream!!, outputStream!!)
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
        Log.d(TAG, "Starting communication with Accessory")

        if(inputStream != null) {
            while (!stopComm) {
                var bytesRead = usbStreamer.read(inBuffer)

                if(bytesRead > 0)
                {
                    messageHandler.receiveData(inBuffer, bytesRead)
                }
                else if(bytesRead == -1)
                {
                    stopComm = true
                }
            }
        }
    }
}
