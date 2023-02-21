package com.example.honapp

import android.net.VpnService
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.math.ceil
import kotlin.math.max

class HONFecService(
    private val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
    private val address: InetAddress,
    private val port: Int,
    private val drop: Int = 0,
    private val timeOut: Long = 100L,
) {
    companion object {
        private const val TAG = "HONFecService"

        init {
            System.loadLibrary("rs")
        }
    }

    val outputChannel = Channel<IpV4Packet>()

    private val selector = Selector.open()
    private var alive = true

    private val maxBlockSize = 1500 - 20 - 8 - 24 // 1448
    private val maxK = 32
    private val maxSendBufSize = maxBlockSize * maxK // 46336

    private var channel: DatagramChannel? = null

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        GlobalScope.launch {
            if (setUp()) {
                fecInit()
                GlobalScope.launch { outputLoop() }
                GlobalScope.launch { readLoop() }
                Log.i(TAG, "Start service")
            }
        }
    }

    private fun setUp(): Boolean {
        channel = DatagramChannel.open()
        tunnel.protect(channel!!.socket())
        channel!!.configureBlocking(false)
        try {
            channel!!.connect(InetSocketAddress(address, port))
            Log.d(TAG, "Channel has established.")
        } catch (e: IOException) {
            Log.e(TAG, "Channel error!!!", e)
            return false
        }
        selector.wakeup()
        channel!!.register(selector, SelectionKey.OP_READ, this)
        return true
    }

    private external fun fecInit()

    private external fun encode(
        dataNum: Int, blockNum: Int, sendBuffer: ByteBuffer, blockSize: Int
    ): Array<ByteArray>

    private external fun decode(
        dataNum: Int, blockNum: Int, encodeData: Array<ByteArray?>, blockSize: Int
    ): Array<ByteArray>

    private suspend fun outputLoop() = coroutineScope {
        val sendBuf = ByteBuffer.allocateDirect(65536)
        var sendNum = 0
        loop@ while (alive) {
            val packet = withTimeoutOrNull(timeOut / (sendNum + 1)) {
                outputChannel.receive()
            }
            if (packet == null) {
                if (sendBuf.position() > 0) {
                    Log.d(
                        TAG,
                        "TIMEOUT: send sendBuf.position=${sendBuf.position()}, sendNum=$sendNum"
                    )

                    val buf = ByteBuffer.allocateDirect(65536)
                    sendBuf.flip()
                    buf.put(sendBuf)
                    sendBuf.flip()
                    launch { serveOutput(buf) }
                    sendBuf.clear()
                    sendNum = 0

                }
            } else {
                if (packet.header!!.totalLength!! <= 0) {
                    continue@loop
                }
                if (sendBuf.position() + packet.rawData!!.size >= maxSendBufSize) {
                    Log.d(
                        TAG, "FULL: send sendBuf.position=${sendBuf.position()}, sendNum=$sendNum"
                    )

                    val buf = ByteBuffer.allocateDirect(65536)
                    sendBuf.flip()
                    buf.put(sendBuf)
                    sendBuf.flip()

                    launch { serveOutput(sendBuf) }

                    sendBuf.clear()
                    sendNum = 0
                }
                sendBuf.put(packet.rawData!!)
                sendNum++
            }
        }
    }


    private suspend fun readLoop() {
        loop@ while (alive) {
            val n = withContext(Dispatchers.IO) {
                selector.selectNow()
            }
            if (n <= 0) {
                delay(100)
                continue@loop
            }
            val keys = selector.selectedKeys()
            val it = keys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                if (key.isValid && key.isReadable) {
                    it.remove()
                    val channel = key.channel() as DatagramChannel
                    val buffer = ByteBuffer.allocate(65536)

                    val readBytes = withContext(Dispatchers.IO) {
                        channel.read(buffer)
                    }
                    if (readBytes > 0) {
                        // TODO: Decode
                        // TODO: Aggregate
                        val packet = IpV4Packet(buffer, readBytes)
                        inputChannel.send(packet)
                    }
                }
            }
        }
    }

    private suspend fun serveOutput(sendBuf: ByteBuffer) = coroutineScope {
        val dataSize = sendBuf.position()
        val blockSize = if (dataSize > maxBlockSize) maxBlockSize else dataSize
        val dataNum = (dataSize + blockSize - 1) / blockSize
        val blockNum = max(dataNum + 1, ceil(dataNum * 0.2).toInt())

        val dataBlocks = encode(dataNum, blockNum, sendBuf, blockSize)
        launch { sendDataBlocks(dataBlocks, blockNum, dataNum, blockSize, dataSize) }
    }

    private suspend fun sendDataBlocks(
        dataBlocks: Array<ByteArray>, blockNum: Int, dataNum: Int, blockSize: Int, dataSize: Int
    ) = coroutineScope {
        val hashCode = dataBlocks.hashCode()
        for (index in 0 until blockNum) {
            if ((1..100).random() <= drop) {
                continue
            }
            launch {
                sendBlock(
                    dataBlocks[index], dataNum, blockNum, blockSize, dataSize, hashCode, index
                )
            }

        }
    }

    private suspend fun sendBlock(
        block: ByteArray,
        data_num: Int,
        block_num: Int,
        blockSize: Int,
        dataSize: Int,
        hashCode: Int,
        index: Int,
    ) {
        val buffer = ByteBuffer.allocate(65536)

        buffer.putInt(hashCode)
        buffer.putInt(dataSize)
        buffer.putInt(blockSize)
        buffer.putInt(data_num)
        buffer.putInt(block_num)
        buffer.putInt(index)
        buffer.put(block)

        buffer.flip()

//        Log.d(
//            TAG,
//            "hashCode=$hashCode, dataSize=$dataSize, blockSize=$blockSize, data_num=$data_num, block_num=$block_num, index=$index"
//        )

        while (buffer.hasRemaining()) {
            if (!(channel!!.isOpen)) {
                break
            }
            withContext(Dispatchers.IO) {
                channel!!.write(buffer)
            }
        }
    }

    fun stop() {
        alive = false
        channel?.close()
        Log.i(TAG, "Stop service")
    }

}