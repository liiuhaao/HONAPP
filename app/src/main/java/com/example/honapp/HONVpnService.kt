package com.example.honapp

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.whileSelect
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class HONVpnService(
//    address: String = "202.120.87.33",
    address: String = "106.75.241.159",
    private val port: Int = 54345
) : VpnService() {
    companion object {
        private const val TAG = "HONVpnService"
    }

    private val inetAddress = InetAddress.getByName(address)
    private val closeCh = Channel<Unit>()
    private val inputCh = Channel<IpV4Packet>()

    private var vpnInterface: ParcelFileDescriptor? = null

    private var alive = true
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null

    private var udpVpnService: UdpVpnService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            stopVpn()
        }
        return Service.START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        setupVpn()
        udpVpnService = UdpVpnService(this, inputCh, inetAddress, port)
        udpVpnService!!.start()
        startVpn()
    }

    private fun setupVpn() {
        val builder =
            Builder().addAddress("10.10.0.15", 24).addDnsServer("8.8.8.8").addRoute("0.0.0.0", 0)
                .setSession(TAG)
        vpnInterface = builder.establish()
        Log.d(TAG, "VPN interface has established!")
    }

    private fun startVpn() {
        GlobalScope.launch { vpnRunLoop() }
    }


    private suspend fun vpnRunLoop() = coroutineScope {
        Log.d(TAG, "Running loop...")
        // Receive from local and send to remote network.
        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        // Receive from remote and send to local network.
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        launch {
            loop@ while (alive) {
                val buffer = ByteBuffer.allocate(65536)
                val readBytes = vpnInputStream!!.read(buffer.array())
                if (readBytes <= 0) {
                    delay(100)
                    continue@loop
                }
                val packet = IpV4Packet(buffer, readBytes)
                Log.d(TAG, "REQUEST: $packet")
                udpVpnService!!.outputChannel.send(packet)
            }
        }
        whileSelect {
            inputCh.onReceive { packet ->
                Log.d(TAG, "RESPONSE: $packet")
                vpnOutputStream!!.write(packet.rawData)
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
        vpnInputStream!!.close()
        vpnOutputStream!!.close()
        udpVpnService!!.stop()
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