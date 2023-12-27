package com.example.honapp

import android.net.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.microseconds

class HONFecService(
    private val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
    private val connectivityManager: ConnectivityManager,
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    companion object {
        private const val TAG = "HONFecService"

        init {
            System.loadLibrary("rs")
        }
    }

    val outputChannel = Channel<IpV4Packet>()

    var config: HONConfig? = null
    var primaryChannel = "Cellular"


    private var txID: UInt = 0u
    private val txMutex = Mutex()

    // 接收端等包
    private var rxGroupId: UInt = 0u
    private var rxIndex: Int = 0
    private var rxNum: Int = 0
    private var timeoutNum: Int = 0
    private val rxMutex = Mutex()
    private var rxList: LinkedList<Triple<IpV4Packet, Pair<UInt, Int>, Long>> = LinkedList()

    // 统计接收端等包的时延情况
    private var rxTime: Double = 0.0
    private var rxCount: Long = 0
    private var rxMin: Double = 1e18
    private var rxMax: Double = -1.0

    // 统计编码的时延情况
    private var encTime: Double = -1.0
    private var encMin: Double = 1e18
    private var encMax: Double = -1.0

    // 统计解码的时延情况
    private var decTime: Double = -1.0
    private var decMin: Double = 1e18
    private var decMax: Double = -1.0

    private var alive = true

    // 双路，两个Channel
    private var cellularUdpChannel: DatagramChannel? = null
    private var wifiUdpChannel: DatagramChannel? = null
//    private var defaultUdpChannel: DatagramChannel? = null

    // Channel的选择器
    private val selector = Selector.open()

    // 统计两种时延
    private var cellularDelay: Long = 0
    private var wifiDelay: Long = 0

    private val maxBlockSize = 1200 - 20 - 8 - 24 // 1,448
    private val maxDataNum = 64
    private val maxPacketBuf = maxBlockSize * maxDataNum // 92,672

    private val bufferSize = 131072

    private val cache = mutableMapOf<UInt, Channel<Pair<String, ByteBuffer>>>()
    private val cacheMutex = Mutex()

    val isCellularAvailable = AtomicBoolean(false)
    val isWifiAvailable = AtomicBoolean(false)

    fun start(inetAddress: InetAddress, port: Int) {
        launch {
            if (setupFec(inetAddress, port)) {

                // fec初始化
                fecInit()

                // 各种统计参数初始化
                rxTime = 0.0
                rxCount = 0
                rxMin = 1e18
                rxMax = -1.0
                timeoutNum = 0

                encTime = -1.0
                encMin = 1e18
                encMax = -1.0

                decTime = -1.0
                decMin = 1e18
                decMax = -1.0

                // 启动！
                alive = true
                launch { outputLoop() }
                launch { inputLoop() }
                launch { monitorRxTimeout() }
                Log.i(TAG, "Start service")
            }
        }
    }

    fun stop() {
        alive = false
        wifiUdpChannel?.close()
        cellularUdpChannel?.close()
        selector.keys().forEach {
            it.cancel()
        }
        Log.i(TAG, "Stop service")
    }

    private suspend fun setupFec(inetAddress: InetAddress, port: Int): Boolean {
//        defaultUdpChannel = withContext(Dispatchers.IO) {
//            DatagramChannel.open().apply {
//                configureBlocking(false)
//            }
//        }
//        tunnel.protect(defaultUdpChannel!!.socket())

        cellularUdpChannel = withContext(Dispatchers.IO) {
            DatagramChannel.open().apply {
                configureBlocking(false)
            }
        }
        tunnel.protect(cellularUdpChannel!!.socket())

        wifiUdpChannel = withContext(Dispatchers.IO) {
            DatagramChannel.open().apply {
                configureBlocking(false)
            }
        }
        tunnel.protect(wifiUdpChannel!!.socket())

        val requestCellular =
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
        connectivityManager.requestNetwork(requestCellular,
            object : ConnectivityManager.NetworkCallback() {
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    try {
                        network.bindSocket(cellularUdpChannel!!.socket())
                        cellularUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
                        isCellularAvailable.set(true)
                        Log.d(TAG, "Cellular Channel has established.")
                    } catch (e: IOException) {
                        Log.e(TAG, "Cellular Channel error!!!", e)
                    }
                }

            })

        val requestWifi =
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivityManager.requestNetwork(requestWifi,
            object : ConnectivityManager.NetworkCallback() {
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onAvailable(network: Network) {
                    try {
                        network.bindSocket(wifiUdpChannel!!.socket())
                        wifiUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
                        isWifiAvailable.set(true)
                        Log.d(TAG, "WiFi Channel has established.")
                    } catch (e: IOException) {
                        Log.e(TAG, "WiFi Channel error!!!", e)
                    }
                }
            })
        Log.d("TAG", "setupFec, requestWifi")

//        try {
//            Log.d(TAG, "Set up $inetAddress, $port")
//            withContext(Dispatchers.IO) {
//                defaultUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
//            }
//            Log.d(TAG, "Default Channel has established.")
//        } catch (e: IOException) {
//            Log.e(TAG, "Default Channel error!!!", e)
//        }


        selector.wakeup()
        withContext(Dispatchers.IO) {
            wifiUdpChannel!!.register(selector, SelectionKey.OP_READ, "wifi")
            cellularUdpChannel!!.register(selector, SelectionKey.OP_READ, "cellular")
//            defaultUdpChannel!!.register(selector, SelectionKey.OP_READ, "default")
        }
        return true
    }

    private external fun fecInit()

    private external fun encode(
        dataNum: Int, blockNum: Int, packetBuffers: Array<ByteArray>, blockSize: Int
    ): Array<ByteArray>

    private external fun decode(
        dataNum: Int, blockNum: Int, encodeData: Array<ByteArray?>, blockSize: Int
    ): Array<ByteArray>

    private suspend fun outputLoop() = coroutineScope {
        val blockNum = config!!.dataNum + config!!.parityNum
        var packetBuffers: Array<ByteArray> = Array(config!!.dataNum) { ByteArray(0) }
        var index = -1
        val outputMutex = Mutex()
        var groupId = 0u

        loop@ while (true) {
            val packet = outputChannel.receive()
            Log.d(TAG, "output try lock outputMUTEX")
            outputMutex.lock()
            Log.d(TAG, "output locked outputMUTEX")

            Log.d(TAG,"primaryChannel=$primaryChannel, ${isCellularAvailable.get()}, ${primaryChannel=="Cellular"}")
            val dataChannel =
                if ((primaryChannel=="Cellular" && isCellularAvailable.get()) || !isWifiAvailable.get()) {
                    Log.d(TAG, "dataChannel is Cellular")
                    cellularUdpChannel
                } else {
                    Log.d(TAG, "dataChannel is Wifi")
                    wifiUdpChannel
                }


//            val buffer = ByteBuffer.allocate(bufferSize)
//            buffer.put(packet.rawData!!)
//            buffer.flip()
//            if (dataChannel!!.isConnected) {
//                withContext(Dispatchers.IO) {
//                    try {
//                        dataChannel.write(buffer)
//                    } catch (e: PortUnreachableException) {
//                        Log.e("Error", "PortUnreachableException")
//                    } catch (e: IOException) {
//                        Log.e("Error", "IOException")
//                    }
//                }
//            }
//            outputMutex.unlock()
//            continue@loop

            index += 1

            packetBuffers[index] = packet.rawData!!

            if (index == 0) {
                groupId = getGroupId()
            }
            Log.d(TAG, "output index=$index")



            outputSend(
                groupId,
                index,
                packet.rawData!!,
                dataChannel
            )

            Log.d(TAG, "output start encode things.")
            if (index + 1 == config!!.dataNum) {
                if (config!!.parityNum > 0) {
                    val blockSize = packetBuffers.maxOf { it.size }
                    for (i in packetBuffers.indices) {
                        val currentSize = packetBuffers[i].size
                        if (currentSize < blockSize) {
                            val paddedBuffer = ByteArray(blockSize)
                            System.arraycopy(packetBuffers[i], 0, paddedBuffer, 0, currentSize)
                            packetBuffers[i] = paddedBuffer
                        }
                    }


                    val beforeEnc = System.nanoTime()

                    val encodedData = encode(config!!.dataNum, blockNum, packetBuffers, blockSize)

                    val afterEnc = System.nanoTime()
                    val timeDelta = afterEnc - beforeEnc
                    encTime = if (encTime < 0) {
                        timeDelta.toDouble()
                    } else {
                        encTime * 0.9 + timeDelta * 0.1
                    }
                    encMax = max(encMax, timeDelta.toDouble())
                    encMin = min(encMin, timeDelta.toDouble())
                    Log.d(
                        TAG,
                        "encode timeDelta=$timeDelta, encTime=$encTime, encMin=$encMin, encMax=$encMax"
                    )

                    val parityChannel =
                        if (isWifiAvailable.get() && primaryChannel == "Cellular" || !isCellularAvailable.get()) {
                            Log.d(TAG, "parityChannel is Wifi")
                            wifiUdpChannel
                        } else {
                            Log.d(TAG, "parityChannel is Cellular")
                            cellularUdpChannel
                        }

//                    val parityChannel = if (isWifiAvailable.get()) {
//                        wifiUdpChannel
//                    } else {
//                        cellularUdpChannel
//                    }

                    for (parityIndex in 0 until config!!.parityNum) {
                        outputSend(
                            groupId,
                            config!!.dataNum + parityIndex,
                            encodedData[parityIndex],
                            parityChannel
                        )
                    }
                }
                packetBuffers = Array(blockNum) { ByteArray(0) }
                index = -1
            }

            Log.d(TAG, "output start encode things done.")
            outputMutex.unlock()
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
                    val channelType = key.attachment() as String
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
//                        val packet = IpV4Packet(readBuf)
//                        inputChannel.send(packet)
                        launch { serveInput(readBuf, channelType) }
                    }
                }
            }
        }
    }


    private suspend fun getGroupId(): UInt {
        txMutex.lock()
        val groupId = txID
        txID += 1u
        txMutex.unlock()
        return groupId
    }


    private suspend fun outputSend(
        groupID: UInt,
        index: Int,
        block: ByteArray,
        udpChannel: DatagramChannel?,
    ) {
        if (udpChannel == null) {
            return
        }
//        if (index == config!!.dataNum - 1) {
//            Log.d(TAG, "outputSend dropout")
//            return
//        }
        if ((1..100).random() <= config!!.dropRate) {
            Log.d(TAG, "outputSend dropout")
            return
        }

        val buffer = ByteBuffer.allocate(bufferSize)

        val packetSendTime = System.currentTimeMillis()
        buffer.putInt(groupID.toInt())
        buffer.putInt(index)
        buffer.putLong(packetSendTime)
        buffer.put(block)


        buffer.flip()
        if (udpChannel.isConnected) {
            withContext(Dispatchers.IO) {
                try {
                    Log.d(
                        TAG,
                        "sendBlock: groupId=$groupID, index=$index, block=$block, size=${buffer.remaining()}"
                    )
                    udpChannel.write(buffer)
                } catch (e: PortUnreachableException) {
                    Log.e("Error", "PortUnreachableException")
                } catch (e: IOException) {
                    Log.e("Error", "IOException")
                }
            }
        }

        buffer.flip()


    }

    private suspend fun serveInput(inputBuf: ByteBuffer, channelType: String) = coroutineScope {
        val groupId = inputBuf.int.toUInt()
        cacheMutex.lock()
        val channel = cache.getOrPut(
            groupId
        ) {
            val newChannel = Channel<Pair<String, ByteBuffer>>()
            launch {
                handleDecode(
                    groupId, newChannel
                )
            }
            newChannel
        }
        channel.send(Pair(channelType, inputBuf))
        cacheMutex.unlock()

    }

    private suspend fun handleDecode(
        groupId: UInt,
        channel: Channel<Pair<String, ByteBuffer>>
    ) {
        val blockNum = config!!.dataNum + config!!.parityNum
        val dataBlocks = Array<ByteArray?>(blockNum) { null }

//        var packetBuffers: Array<ByteArray> = Array(config!!.dataNum) { ByteArray(0) }
        val marks = Array(blockNum) { 1 }
        var receiveNum = 0
        while (true) {
            val inputPair = withTimeoutOrNull(config!!.decodeTimeout.microseconds) {
                channel.receive()
            }
            if (inputPair == null) {
                cacheMutex.lock()
                cache.remove(groupId)
                cacheMutex.unlock()
                break
            } else {
                val channelType = inputPair.first
                val inputBuf = inputPair.second

                val index = inputBuf.int
                val packetSendTime = inputBuf.long

                if (index >= config!!.dataNum + config!!.parityNum) {
                    continue
                }
//                if(index==config!!.dataNum-1){
//                    continue
//                }
                val randomNum = (1..100).random()
                if (randomNum <= (config!!.dropRate)) {
                    Log.d(TAG, "handleDecode: groupId=$groupId, index=$index dropout $randomNum")
                    continue
                }

                val packetReceiveTime = System.currentTimeMillis()
                if (channelType == "wifi") {
                    wifiDelay = packetReceiveTime - packetSendTime
                } else {
                    cellularDelay = packetReceiveTime - packetSendTime
                }

                if (marks[index] == 0) {
                    continue
                }

                receiveNum += 1
                marks[index] = 0

                Log.d(
                    TAG,
                    "handleDecode: groupId=$groupId, index=$index, receiveNum=$receiveNum, packetSendTime=$packetSendTime"
                )


                if (index < config!!.dataNum) {
                    val duplicateBuf = inputBuf.duplicate()
                    val packet = IpV4Packet(duplicateBuf)
//                    Log.d(TAG, "groupId=$groupId, index=$index, packet=$packet")
                    rxMutex.lock()
                    rxInsert(packet, groupId, index)
                    rxSend()
                    rxMutex.unlock()
                }


                dataBlocks[index] = ByteArray(inputBuf.remaining())
                inputBuf.get(dataBlocks[index]!!)

                if (receiveNum == config!!.dataNum) {
                    val blockSize = dataBlocks.filterNotNull().maxOf { it.size }
                    for (i in dataBlocks.indices) {
                        if (dataBlocks[i] != null && dataBlocks[i]!!.size < blockSize) {
                            val newArray = ByteArray(blockSize)
                            System.arraycopy(
                                dataBlocks[i]!!,
                                0,
                                newArray,
                                0,
                                dataBlocks[i]!!.size
                            )
                            dataBlocks[i] = newArray
                        }
                    }

                    val beforeDec = System.nanoTime()

                    val decodeBlocks =
                        decode(config!!.dataNum, blockNum, dataBlocks, blockSize)

                    val afterDec = System.nanoTime()
                    val timeDelta = afterDec - beforeDec
                    decTime = if (encTime < 0) {
                        timeDelta.toDouble()
                    } else {
                        decTime * 0.9 + timeDelta * 0.1
                    }
                    decMax = max(decMax, timeDelta.toDouble())
                    decMin = min(decMin, timeDelta.toDouble())
                    Log.d(
                        TAG,
                        "decode timeDelta=$timeDelta, decTime=$decTime, decMin=$decMin, decMax=$decMax"
                    )


                    rxMutex.lock()
                    for (i in 0 until config!!.dataNum) {
                        if (marks[i] == 1) {
                            val blockBuf = ByteBuffer.allocate(blockSize)
                            blockBuf.put(decodeBlocks[i])
                            blockBuf.flip()
                            val packet = IpV4Packet(blockBuf)
                            Log.d(TAG, "decode groupId=$groupId, index=$i, packet=$packet")
                            marks[i] = 0
                            rxInsert(packet, groupId, i)
                            rxSend()
                        }
                    }
                    rxMutex.unlock()
                }

            }
        }
    }

    private suspend fun rxInsert(packet: IpV4Packet, groupID: UInt, index: Int) = coroutineScope {
        val rxNew: Triple<IpV4Packet, Pair<UInt, Int>, Long> =
            Triple(packet, Pair(groupID, index), System.nanoTime())

//        Log.d(TAG, " rxInsert: rxGroupId=$rxGroupId, rxIndex=$rxIndex, groupId=$groupID, index=$index, rxNew time=${rxNew.third}")
        if (rxGroupId > groupID || (rxGroupId == groupID && rxIndex > index)) {
            return@coroutineScope
        }


        val rxIter = rxList.listIterator()
        while (rxIter.hasNext()) {
            val rx = rxIter.next()
            val rxPair = rx.second
            if (rxPair.first > groupID || (rxPair.first == groupID && rxPair.second > index)) {
                rxIter.previous()
                break
            }
        }
        rxNum += 1
        rxIter.add(rxNew)
        rxPrint()
    }

    private suspend fun rxSend() = coroutineScope {
        while ((rxList.isNotEmpty())) {
            val now = System.nanoTime()
            val isTimeout = (now - rxList.first.third > config!!.rxTimeout * 1000)
            val rx = rxList.first()
            val rxPair = rx.second
            if (rxNum < config!!.rxNum && !isTimeout) {
                if (rxGroupId < rxPair.first || (rxGroupId == rxPair.first && rxIndex < rxPair.second)) {
                    break
                }
            }
            if (isTimeout) {
                timeoutNum += 1
            }
            val timeDelta = now - rxList.first.third
            if (rxCount == 0.toLong()) {
                rxTime = timeDelta.toDouble()
                rxCount += 1
            } else {
                rxTime += timeDelta.toDouble()
                rxCount += 1
            }
            rxMax = max(rxMax, timeDelta.toDouble())
            rxMin = min(rxMin, timeDelta.toDouble())
            Log.d(
                TAG,
                "rxSend, timeDelta=$timeDelta, rxTime=${rxTime / rxCount}, " +
                        "rxMin=$rxMin, rxMax=$rxMax, " +
                        "timeoutRate=${timeoutNum.toDouble() / rxCount.toDouble()}, " +
                        "rxRate=${rxCount.toDouble() / (rxPair.first * config!!.dataNum.toUInt() + rxPair.second.toUInt() + 1u).toDouble()}"
            )
            inputChannel.send(rx.first)
            if (rxPair.second == config!!.dataNum - 1) {
                rxGroupId = rxPair.first + 1u
                rxIndex = 0
            } else {
                rxGroupId = rxPair.first
                rxIndex = rxPair.second + 1
            }
            rxList.removeFirst()
            rxNum--
        }
    }

    private suspend fun rxPrint() = coroutineScope {
        if (rxList.isNotEmpty()) {
            var rxLog = ""
            rxLog += "Left $rxNum in rxList: rxGroupId=$rxGroupId, rxIndex=$rxIndex. leftId={"
            for (rxIter in rxList) {
                rxLog += "${rxIter.second},"
            }
            rxLog += "}"
            Log.d(TAG, rxLog)
        }
    }

    private suspend fun monitorRxTimeout() = coroutineScope {
        while (true) {
            delay(1)
            rxMutex.lock()
            val now = System.nanoTime()
            if (rxList.isNotEmpty() && now - rxList.first.third > config!!.rxTimeout * 1000) {
                Log.d(
                    TAG,
                    "delta = ${now - rxList.first.third}, thread = ${config!!.rxTimeout * 1000}"
                )
                rxSend()
            }
            rxMutex.unlock()
        }
    }


}


