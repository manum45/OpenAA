package io.github.manum45.openaa

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException


fun byteArrayToHex(byteArray: ByteArray, numBytes: Int): String {
    var msg :String = ""

    for(i in 0..<numBytes)
    {
        msg += byteArray[i].toHexString() + " "
    }
    return msg
}


/**
 * An interface for the underlying transport mechanism to send data to the head unit.
 */
interface IUsbStreamer {
    fun write(data: ByteArray)
}


class UsbStreamer() : IUsbStreamer {
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    fun registerStreams(inputStream: FileInputStream, outputStream: FileOutputStream) {
        this.inputStream = inputStream
        this.outputStream = outputStream
    }

    override fun write(data: ByteArray){
        Log.d(TAG, "UsbStreamer write: " + byteArrayToHex(data, data.size))
        /// TODO: handle return value
        write(data, data.size)
    }

    fun write(data: ByteArray, length: Int): Boolean {
        var success = true
        try {
            for(i in 0..<length){
                outputStream!!.write(data, 0, length)
            }
        }
        catch (e: IOException) {
            /// TODO: handle more gracefully?
            Log.d(TAG, "Connection closed")
            success = false
        }
        return success
    }

    fun read(inBuffer: ByteArray): Int {
        try {
            var bytesRead = inputStream!!.read(inBuffer)

            if(bytesRead > 0)
            {
                var msg = byteArrayToHex(inBuffer, bytesRead)
                Log.d(TAG, "UsbStreamer read: $msg")
            }

            return bytesRead
        }
        catch (e: IOException) {
            /// TODO: handle more gracefully?
            Log.d(TAG, "Connection closed")
            return -1
        }
    }
}