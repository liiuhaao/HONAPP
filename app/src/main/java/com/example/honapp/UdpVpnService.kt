package com.example.honapp

import android.net.VpnService
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.whileSelect
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

class UdpVpnService(
    val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
    private val closeChannel: Channel<Unit>,
    private val address: InetAddress,
    private val port: Int,
) {
    companion object {
        private const val TAG = "UdpVpnService"
    }

    val outputChannel = Channel<IpV4Packet>()

    private val selector = Selector.open()
    private val selectorMux = Mutex()
    private val cache = mutableMapOf<String, Connection?>()
    private val mux = Mutex()

    fun start() {
        GlobalScope.launch {
            whileSelect {
                outputChannel.onReceive { value ->
                    // received package(local >> remote)
                    GlobalScope.launch { serveOutput(value) }
                    true
                }
                closeChannel.onReceiveCatching {
                    false
                }
            }
            Log.d(TAG, "exit main loop")
        }
        GlobalScope.launch { readLoop() }
        // receive data from the remote server.
        Log.i(TAG, "start service")
    }

    private suspend fun readLoop() {
        val readyChannel = Channel<Int>()
        var alive = true
        GlobalScope.launch {
            loop@ while (alive) {
                selectorMux.lock()
                val n = selector.selectNow()
                selectorMux.unlock()
                if (n == 0) {
                    delay(100)
                    continue@loop
                }
                readyChannel.send(n)
            }
        }
        whileSelect {
            readyChannel.onReceive {
                selectorMux.lock()
                val keys = selector.selectedKeys()
                val it = keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    if (key.isValid && key.isReadable) {
                        it.remove()
                        val channel = key.channel() as DatagramChannel
                        val buffer = ByteBuffer.allocate(65536)

                        val readBytes = channel.read(buffer)
                        if (readBytes > 0) {
                            // TODO: Decode
                            // TODO: Aggregate
                            val packet = IpV4Packet(buffer, readBytes)
                            inputChannel.send(packet)
                        }
                    }
                }
                selectorMux.unlock()
                true
            }
            closeChannel.onReceiveCatching {
                false
            }
        }
        alive = false
    }

    // TODO: Encode
    // TODO: Choose fd
    private suspend fun serveOutput(packet: IpV4Packet) {
        val destinationAddress = packet.header!!.destinationAddress
        val sourceAddress = packet.header!!.sourceAddress

        val sourceAndDestination = "${sourceAddress}:${destinationAddress}"
        var conn: Connection? = null
        mux.lock()
        conn = cache.getOrPut(sourceAndDestination) {
            val newConn = Connection(sourceAndDestination)
            newConn
        }
        if (!conn!!.isOpen && !conn.open()) {
            conn.close()
            cache.remove(sourceAndDestination)
            return
        }
        mux.unlock()
        val buffer = ByteBuffer.wrap(packet.rawData!!)
        while (buffer.hasRemaining()) {
            conn.channel!!.write(buffer)
        }
    }

    fun stop() {
        val it = cache.iterator()
        while (it.hasNext()) {
            it.next().value!!.close()
            it.remove()
        }
        Log.i(TAG, "stop service")
    }

    inner class Connection(private val sourceAndDestination: String) {
        var channel: DatagramChannel? = null
        var isOpen = false

        fun open(): Boolean {
            if (isOpen) {
                return true
            }
            channel = DatagramChannel.open()
            // protect a socket before connect to the remote server.
            tunnel.protect(channel!!.socket())
            channel!!.configureBlocking(false)
            try {
                channel!!.connect(InetSocketAddress(address, port))
                Log.d(TAG, "A connection has established: $sourceAndDestination")
            } catch (e: IOException) {
                Log.e(TAG, "Connection error: $sourceAndDestination", e)
                return false
            }

            selector.wakeup()
            channel!!.register(selector, SelectionKey.OP_READ, this)
            isOpen = true
            return true
        }

        fun close() {
            channel?.close()
            isOpen = false
        }

        override fun toString(): String {
            return sourceAndDestination
        }
    }
}