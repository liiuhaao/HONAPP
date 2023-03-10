package com.example.honapp

import android.net.VpnService
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class HONFecService(
    private val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    companion object {
        private const val TAG = "HONFecService"

        init {
            System.loadLibrary("rs")
        }
    }

    val outputChannel = Channel<IpV4Packet>()

    private var dropRate: Int = 0
    private var parityRate: Int = 0

    private val encodeTimeout: Duration = 1000.nanoseconds
    private val decodeTimeout: Duration = 1000.milliseconds

    private var alive = true

    private var udpChannel: DatagramChannel? = null
    private val selector = Selector.open()

    private val maxBlockSize = 1200 - 20 - 8 - 24 // 1,448
    private val maxDataNum = 64
    private val maxPacketBuf = maxBlockSize * maxDataNum // 92,672

    private val bufferSize = 131072
    private val maxPacketNum = 10

    private val cache =
        mutableMapOf<Pair<Int, Pair<Pair<Int, Int>, Pair<Int, Int>>>, Channel<ByteBuffer>>()
    private val cacheMutex = Mutex()

    fun start(inetAddress: InetAddress, port: Int) {
        launch {
            if (setupFec(inetAddress, port)) {
                fecInit()
                alive = true
                launch { outputLoop() }
                launch { inputLoop() }
                Log.i(TAG, "Start service")
            }
        }
    }

    fun stop() {
        alive = false
        udpChannel?.close()
        selector.keys().forEach {
            it.cancel()
        }
        Log.i(TAG, "Stop service")
    }

    private suspend fun setupFec(inetAddress: InetAddress, port: Int): Boolean {
        udpChannel = withContext(Dispatchers.IO) {
            DatagramChannel.open()
        }
        tunnel.protect(udpChannel!!.socket())
        withContext(Dispatchers.IO) {
            udpChannel!!.configureBlocking(false)
        }
        try {
            Log.d(TAG, "Set up $inetAddress, $port")
            withContext(Dispatchers.IO) {
                udpChannel!!.connect(InetSocketAddress(inetAddress, port))
            }
            Log.d(TAG, "Channel has established.")
        } catch (e: IOException) {
            Log.e(TAG, "Channel error!!!", e)
            return false
        }
        selector.wakeup()
        withContext(Dispatchers.IO) {
            udpChannel!!.register(selector, SelectionKey.OP_READ, this)
        }
        return true
    }

    fun setDropRate(value: Int) {
        dropRate = value
    }

    fun setParityRate(value: Int) {
        parityRate = value
    }

    private external fun fecInit()

    private external fun encode(
        dataNum: Int, blockNum: Int, sendBuffer: ByteBuffer, blockSize: Int
    ): Array<ByteArray>

    private external fun decode(
        dataNum: Int, blockNum: Int, encodeData: Array<ByteArray?>, blockSize: Int
    ): Array<ByteArray>

    private suspend fun outputLoop() = coroutineScope {
        val packetBuf = ByteBuffer.allocateDirect(bufferSize)
        var packetNum = 0
        loop@ while (alive) {
//            val packet = outputChannel.receive()
//            val buffer = ByteBuffer.allocate(bufferSize)
//            buffer.put(packet.rawData)
//            buffer.flip()
//            while (buffer.hasRemaining()) {
//                if (!(udpChannel!!.isOpen)) {
//                    break
//                }
//                withContext(Dispatchers.IO) {
//                    udpChannel!!.write(buffer)
//                }
//            }

            val packet = withTimeoutOrNull(encodeTimeout / (packetNum + 1)) {
                outputChannel.receive()
            }
            if (packet == null) {
                if (packetNum > 0) {
                    packetBuf.flip()
                    val outputBuf = ByteBuffer.allocateDirect(bufferSize)
                    outputBuf.put(packetBuf)
                    packetBuf.flip()

                    serveOutput(outputBuf, packetNum)

                    packetBuf.clear()
                    packetNum = 0

                }
            } else {
                if (packet.header!!.totalLength!! <= 0) {
                    continue@loop
                }

                Log.d(TAG, packet.toString())
                if (packetBuf.position() + packet.rawData!!.size >= maxPacketBuf) {
                    packetBuf.flip()
                    val outputBuffer = ByteBuffer.allocateDirect(bufferSize)
                    outputBuffer.put(packetBuf)
                    packetBuf.flip()

                    serveOutput(outputBuffer, packetNum)

                    packetBuf.clear()
                    packetNum = 0
                }
                packetBuf.put(packet.rawData!!)
                packetNum++
                if (packetNum >= maxPacketNum) {
                    packetBuf.flip()
                    val outputBuf = ByteBuffer.allocateDirect(bufferSize)
                    outputBuf.put(packetBuf)
                    packetBuf.flip()

                    serveOutput(outputBuf, packetNum)

                    packetBuf.clear()
                    packetNum = 0

                }
            }
        }
    }


    private suspend fun inputLoop() = coroutineScope {
        loop@ while (alive) {
            val n = withContext(Dispatchers.IO) {
                selector.selectNow()
            }
            if (n <= 0) {
                continue@loop
            }
            val keys = selector.selectedKeys()
            val it = keys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                if (key.isValid && key.isReadable) {
                    it.remove()
                    val channel = key.channel() as DatagramChannel
                    val readBuf = ByteBuffer.allocate(bufferSize)
                    val readBytes = withContext(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.IO) {
                                channel.read(readBuf)
                            }
                        } catch (e: IOException) {
                            e.printStackTrace();
                            -1
                        }
                    }
                    if (readBytes > 0) {
                        readBuf.flip()
                        val inputBuf = ByteBuffer.allocate(readBytes)
                        inputBuf.put(readBuf)
                        inputBuf.flip()
                        readBuf.flip()
                        launch { serveInput(readBuf) }
//                        val packet = IpV4Packet(inputBuf, readBytes)
//                        inputChannel.send(packet)
                    }
                }
            }
        }
    }

    private suspend fun serveInput(inputBuf: ByteBuffer) = coroutineScope {
        val hashCode = inputBuf.int
        val dataSize = inputBuf.int
        val blockSize = inputBuf.int
        val dataNum = inputBuf.int
        val blockNum = inputBuf.int
        cacheMutex.lock()
        val channel = cache.getOrPut(
            Pair(
                hashCode,
                Pair(Pair(dataSize, blockSize), Pair(dataNum, blockNum))
            )
        ) {
            val newChannel = Channel<ByteBuffer>()
            launch {
                handleDecode(
                    hashCode, dataSize, blockSize, dataNum, blockNum, newChannel
                )
            }
            newChannel
        }
        cacheMutex.unlock()
        if ((1..100).random() > dropRate) {
            channel.send(inputBuf)
        }
    }

    private suspend fun handleDecode(
        hashCode: Int,
        dataSize: Int,
        blockSize: Int,
        dataNum: Int,
        blockNum: Int,
        channel: Channel<ByteBuffer>
    ) {
        val dataBlocks = Array<ByteArray?>(blockNum) { null }
        val marks = Array(blockNum) { 1 }
        var receiveNum = 0
        while (true) {
            val inputBuf = withTimeoutOrNull(decodeTimeout / (receiveNum + 1)) {
                channel.receive()
            }
            if (inputBuf == null) {

                cacheMutex.lock()
                cache.remove(
                    Pair(
                        hashCode,
                        Pair(Pair(dataSize, blockSize), Pair(dataNum, blockNum))
                    )
                )
                cacheMutex.unlock()
                break
            } else {
                val index = inputBuf.int
                if (marks[index] == 0) {
                    continue
                }
                receiveNum += 1
                marks[index] = 0
                dataBlocks[index] = ByteArray(blockSize)
                inputBuf.get(dataBlocks[index]!!)
                if (receiveNum == dataNum) {
                    val decodeBlock = decode(dataNum, blockNum, dataBlocks, blockSize)
                    val decodeBuf = ByteBuffer.allocate(dataNum * blockSize)
                    for (block in decodeBlock) {
                        decodeBuf.put(block)
                    }
                    decodeBuf.flip()
                    while (decodeBuf.position() < dataSize) {
                        val packet = IpV4Packet(decodeBuf)
                        inputChannel.send(packet)
                    }
                }
            }
        }
    }

    private suspend fun serveOutput(outputBuffer: ByteBuffer, packetNum: Int) = coroutineScope {

        val dataSize = outputBuffer.position()

//        val dataNum = packetNum
//        val blockSize = (dataSize + dataNum - 1) / dataNum

        val blockSize = if (dataSize > maxBlockSize) maxBlockSize else dataSize
        val dataNum = (dataSize + blockSize - 1) / blockSize
//        val blockNum = max(dataNum + 1, dataNum + floor(dataNum * 0.2).toInt())

        var blockNum = dataNum
        for (i in 0 until dataNum) {
            if ((1..100).random() <= parityRate) {
                blockNum++
            }
        }
        val dataBlocks = encode(dataNum, blockNum, outputBuffer, blockSize)
        launch { sendDataBlocks(dataBlocks, blockNum, dataNum, blockSize, dataSize) }
    }

    private suspend fun sendDataBlocks(
        dataBlocks: Array<ByteArray>, blockNum: Int, dataNum: Int, blockSize: Int, dataSize: Int
    ) = coroutineScope {
        val hashCode = dataBlocks.hashCode()

        Log.d(
            TAG,
            "TIMEOUT: hashCode=$hashCode, dataSize=$dataSize, block_num=$blockNum, blockSize=$blockSize, dataNum=$dataNum, blockNum=$blockNum, rate=${parityRate}"
        )
        for (index in 0 until blockNum) {
            if ((1..100).random() <= dropRate) {
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
        dataNum: Int,
        blockNum: Int,
        blockSize: Int,
        dataSize: Int,
        hashCode: Int,
        index: Int,
    ) {
        val buffer = ByteBuffer.allocate(bufferSize)

        buffer.putInt(hashCode)
        buffer.putInt(dataSize)
        buffer.putInt(blockSize)
        buffer.putInt(dataNum)
        buffer.putInt(blockNum)
        buffer.putInt(index)
        buffer.put(block)

        buffer.flip()

        while (buffer.hasRemaining()) {
            if (!(udpChannel!!.isOpen)) {
                break
            }
            try {
                withContext(Dispatchers.IO) {
                    udpChannel!!.write(buffer)
                }
            } catch (e: IOException) {
                e.printStackTrace();
            }
        }
    }


}