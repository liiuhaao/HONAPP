package com.example.honapp

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.whileSelect
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class HONVpnService(
    private val address: InetAddress = InetAddress.getByName("202.120.87.33"),
//    private val address: InetAddress = InetAddress.getByName("106.75.247.84"),
    private val port: Int = 54345
) :
    VpnService() {
    companion object {
        private const val TAG = "HONVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    private val closeCh = Channel<Unit>()
    private val inputCh = Channel<DatagramPacket>()

    private var alive = true
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            stopVpn()
        }
        return Service.START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        setupVpn()
        startVpn()
    }

    private fun setupVpn() {
        val builder =
            Builder().addAddress("10.0.2.15", 24).addDnsServer("8.8.8.8").addRoute("0.0.0.0", 0)
                .setSession(TAG)
        vpnInterface = builder.establish()
        Log.d(TAG, "VPN interface has established!")
    }

    private fun startVpn() {
        GlobalScope.launch { vpnRunLoop() }
    }


    private suspend fun vpnRunLoop() {
        Log.d(TAG, "Running loop...")
        // Receive from local and send to remote network.
        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        // Receive from remote and send to local network.
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        GlobalScope.launch {
            loop@ while (alive) {
                val buffer = ByteBuffer.allocate(65536)
                val readBytes = vpnInputStream!!.read(buffer.array())
                if (readBytes <= 0) {
                    delay(100)
                    continue@loop
                }

//                val packet = DatagramPacket(buffer.array(), readBytes)
//                Log.d(TAG, "UDP Request ${packet.length}: ${packet.data}")

                val packet = DatagramPacket(buffer.array(), readBytes, address, port)
                val socketSend = DatagramSocket()
                protect(socketSend)
                try {
                    socketSend.send(packet)
                    Log.d("$TAG Request", "lenght=${packet.length}, data=${packet.data}")
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e("$TAG Request", "Request Send Error: $e")
                }
                socketSend.close()

//                val ipV4Packet = IpV4Packet(buffer)
//                when (ipV4Packet.getProtocol()) {
//                    TransportProtocol.UDP -> {
//                        Log.d(TAG, "UDP REQUEST\n${ipV4Packet}")
//                    }
//                    TransportProtocol.TCP -> {
//                        Log.d(TAG, "TCP REQUEST\n${ipV4Packet}")
//                    }
//                    else -> {
//                        Log.w(TAG, "Unknown packet type")
//                    }
//                }
            }
        }
        whileSelect {
            inputCh.onReceive { value ->
                Log.d("$TAG Response", "$value")
                vpnOutputStream!!.write(value.data)
                true
            }
            closeCh.onReceiveCatching {
                false
            }
        }
        vpnExitLoop()
    }

    private fun stopVpn() {
        alive = false
        closeCh.close()
        vpnInterface?.close()
        stopSelf()
        Log.i(TAG, "Stopped VPN")
    }

    private fun vpnExitLoop() {
        vpnInputStream!!.close()
        vpnOutputStream!!.close()
        alive = false
        Log.i(TAG, "Exit loop")
    }
}