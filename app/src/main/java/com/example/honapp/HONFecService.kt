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
    var dropMode = "主路丢包"


    private var txID: UInt = 0u
    private val txMutex = Mutex()


    private var ackList: LinkedList<Triple<Long, Pair<UInt, Int>, ByteArray>> = LinkedList()
    private var ackMutex = Mutex()

    // 接收端等包
    private var rxGroupId: UInt = 0u
    private var rxIndex: Int = 0
    private var rxNum: Int = 0
    private var timeoutNum: Int = 0
    private val rxMutex = Mutex()
    private var rxList: LinkedList<Triple<IpV4Packet, Pair<UInt, Int>, Long>> = LinkedList()
    private var rxTotal: Int = 1
    private var rxRate: Double = 0.0

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

    // 包数量统计
    private var vpnSendPacketNum: Long = 0
    private var vpnSendPacketSize: Double = 0.0
    private var vpnReceivePacketNum: Long = 0
    private var vpnReceivePacketSize: Double = 0.0
    private var cellularSendPacketNum: Long = 0
    private var cellularSendPacketSize: Double = 0.0
    private var wifiSendPacketNum: Long = 0
    private var wifiSendPacketSize: Double = 0.0
    private var cellularReceivePacketNum: Long = 0
    private var cellularReceivePacketSize: Double = 0.0
    private var wifiReceivePacketNum: Long = 0
    private var wifiReceivePacketSize: Double = 0.0

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
                if (config!!.mode == 3) {
                    launch { monitorACK() }
                }
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

    // 获取一些统计结果
    fun getInfo(): MutableMap<String, Any> {
        val res = mutableMapOf<String, Any>()
        res["rxTime"] = rxTime / rxCount
        res["rxMin"] = rxMin
        res["rxMax"] = rxMax
        res["timeoutRate"] = timeoutNum.toDouble() / rxCount.toDouble()
        res["rxRate"] = rxRate
        res["rxCount"] = rxCount
        res["rxTotal"] = rxTotal
        res["vpnSendPacketNum"] = vpnSendPacketNum
        res["vpnSendPacketSize"] = vpnSendPacketSize
        res["vpnReceivePacketNum"] = vpnReceivePacketNum
        res["vpnReceivePacketSize"] = vpnReceivePacketSize
        res["cellularSendPacketNum"] = cellularSendPacketNum
        res["cellularSendPacketSize"] = cellularSendPacketSize
        res["wifiSendPacketNum"] = wifiSendPacketNum
        res["wifiSendPacketSize"] = wifiSendPacketSize
        res["cellularReceivePacketNum"] = cellularReceivePacketNum
        res["cellularReceivePacketSize"] = cellularReceivePacketSize
        res["wifiReceivePacketNum"] = wifiReceivePacketNum
        res["wifiReceivePacketSize"] = wifiReceivePacketSize
        return res
    }

    private external fun fecInit()

    private external fun encode(
        dataNum: Int,
        blockNum: Int,
        packetBuffers: Array<ByteArray>,
        blockSize: Int,
        mode: Int,
    ): Array<ByteArray>

    private external fun decode(
        dataNum: Int, blockNum: Int, encodeData: Array<ByteArray?>, blockSize: Int, mode: Int,
    ): Array<ByteArray>


    // 配置蜂窝和Wifi的Channel
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
        connectivityManager.requestNetwork(
            requestCellular,
            object : ConnectivityManager.NetworkCallback() {
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    try {
                        network.bindSocket(cellularUdpChannel!!.socket())
                        cellularUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
                        isCellularAvailable.set(true)
//                        Log.d(TAG, "Cellular Channel has established.")
                    } catch (e: IOException) {
//                        Log.e(TAG, "Cellular Channel error!!!", e)
                    }
                }

            })

        val requestWifi =
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivityManager.requestNetwork(
            requestWifi,
            object : ConnectivityManager.NetworkCallback() {
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onAvailable(network: Network) {
                    try {
                        network.bindSocket(wifiUdpChannel!!.socket())
                        wifiUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
                        isWifiAvailable.set(true)
//                        Log.d(TAG, "WiFi Channel has established.")
                    } catch (e: IOException) {
//                        Log.e(TAG, "WiFi Channel error!!!", e)
                    }
                }
            })

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

    // 客户端->服务端
    private suspend fun outputLoop() = coroutineScope {
        val blockNum = config!!.dataNum + config!!.parityNum
        var packetBuffers: Array<ByteArray> = Array(config!!.dataNum) { ByteArray(0) }
        var index = -1
        val outputMutex = Mutex()
        var groupId = 0u

        loop@ while (true) {
            val packet = outputChannel.receive()
            outputMutex.lock()
            vpnSendPacketNum += 1
            vpnSendPacketSize =
                ((vpnSendPacketNum - 1).toDouble() / vpnSendPacketNum.toDouble() * vpnSendPacketSize) + packet.rawData!!.size / vpnSendPacketNum.toDouble()

            // 主路选择
            val dataChannel =
                if ((primaryChannel == "Cellular" && isCellularAvailable.get()) || !isWifiAvailable.get()) {
                    Log.d(TAG, "dataChannel is Cellular")
                    cellularUdpChannel
                } else {
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


            // 更新group下的index
            index += 1

            // 放进去
            packetBuffers[index] = packet.rawData!!

            // 如果index被更新成了0，那就是新的group
            if (index == 0) {
                groupId = getGroupId()
            }


            // 发送该数据包
            outputSend(
                groupId, index, packet.rawData!!, dataChannel
            )

            // 如果是多倍发包的模式，则再发一个数据包
            if (config!!.mode == 2) {
                val parityChannel =
                    if (isWifiAvailable.get() && primaryChannel == "Cellular" || !isCellularAvailable.get()) {
                        Log.d(TAG, "parityChannel is Wifi")
                        wifiUdpChannel
                    } else {
                        Log.d(TAG, "parityChannel is Cellular")
                        cellularUdpChannel
                    }
                outputSend(groupId, index + config!!.dataNum, packet.rawData!!, parityChannel)
            }

            if (config!!.mode == 3) {
                ackMutex.lock()
                ackList.add(
                    Triple(
                        System.currentTimeMillis(),
                        Pair(groupId, index),
                        packet.rawData!!
                    )
                )
                ackMutex.unlock()
            }

            // 如果累计了dataNum个数据包，就进行这一个group数据包的冗余包构建
            if (index + 1 == config!!.dataNum) {
                if ((config!!.mode == 0 || config!!.mode == 1) && config!!.parityNum > 0) {
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

                    val encodedData = encode(
                        config!!.dataNum, blockNum, packetBuffers, blockSize, config!!.mode
                    )

                    val afterEnc = System.nanoTime()
                    val timeDelta = afterEnc - beforeEnc
                    encTime = if (encTime < 0) {
                        timeDelta.toDouble()
                    } else {
                        encTime * 0.9 + timeDelta * 0.1
                    }
                    encMax = max(encMax, timeDelta.toDouble())
                    encMin = min(encMin, timeDelta.toDouble())

                    val parityChannel =
                        if (isWifiAvailable.get() && primaryChannel == "Cellular" || !isCellularAvailable.get()) {
                            Log.d(TAG, "parityChannel is Wifi")
                            wifiUdpChannel
                        } else {
                            Log.d(TAG, "parityChannel is Cellular")
                            cellularUdpChannel
                        }

                    for (parityIndex in 0 until config!!.parityNum) {
                        if (parityChannel == wifiUdpChannel) {

                        }
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

            outputMutex.unlock()
        }
    }

    // 服务端->客户端
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
                        vpnReceivePacketNum += 1
                        vpnReceivePacketSize =
                            ((vpnReceivePacketNum - 1).toDouble() / vpnReceivePacketNum.toDouble() * vpnReceivePacketSize) + readBytes / vpnReceivePacketNum.toDouble()
                        if (channel == wifiUdpChannel) {
                            wifiReceivePacketNum += 1
                            wifiReceivePacketSize =
                                ((wifiReceivePacketNum - 1).toDouble() / wifiReceivePacketNum.toDouble() * wifiReceivePacketSize) + readBytes / wifiReceivePacketNum.toDouble()
                        } else if (channel == cellularUdpChannel) {
                            cellularReceivePacketNum += 1
                            cellularReceivePacketSize =
                                ((cellularReceivePacketNum - 1).toDouble() / cellularReceivePacketNum.toDouble() * cellularReceivePacketSize) + readBytes / cellularReceivePacketNum.toDouble()
                        }
                        Log.d(
                            TAG,
                            "channel: wifi接收个数=$wifiReceivePacketNum, wifi接收字节=$wifiReceivePacketSize, 蜂窝接收个数=$cellularReceivePacketNum, 蜂窝接收字节=$cellularReceivePacketSize, $channelType"
                        )

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

    // 更新groupId
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
        Log.d(TAG, "outputSend: groupId=$groupID, index=$index")
//        if (index == config!!.dataNum - 1) {
//            Log.d(TAG, "outputSend dropout")
//            return
//        }
        if (dropMode == "主路随机丢包") {
            if ((udpChannel == wifiUdpChannel && primaryChannel == "Wifi") || (udpChannel == cellularUdpChannel && primaryChannel == "Cellular")) {
                if ((1..100).random() <= config!!.dropRate) {
                    return
                }
            }
        } else {
            if ((1..100).random() <= config!!.dropRate) {
                return
            }
        }

        val buffer = ByteBuffer.allocate(bufferSize)

        val packetSendTime = System.currentTimeMillis()
        buffer.putInt(0) // packet类别，0代表数据/冗余包
        buffer.putInt(groupID.toInt())
        buffer.putInt(index)
        buffer.putLong(packetSendTime)
        buffer.put(block)
        buffer.flip()

        if (udpChannel.isConnected) {
            withContext(Dispatchers.IO) {
                try {
//                    Log.d(
//                        TAG,
//                        "sendBlock: groupId=$groupID, index=$index, block=$block, size=${buffer.remaining()}"
//                    )
                    udpChannel.write(buffer)
                } catch (e: PortUnreachableException) {
                    Log.e("Error", "PortUnreachableException")
                } catch (e: IOException) {
                    Log.e("Error", "IOException")
                }
            }
        }
        buffer.flip()

        if (udpChannel == wifiUdpChannel) {
            wifiSendPacketNum += 1
            wifiSendPacketSize =
                ((wifiSendPacketNum - 1).toDouble() / wifiSendPacketNum.toDouble() * wifiSendPacketSize) + block.size / wifiSendPacketNum.toDouble()
            Log.d(
                TAG,
                "channel: wifi发送个数=$wifiSendPacketNum, wifi发送字节=$wifiSendPacketSize, 蜂窝发送个数=$cellularSendPacketNum, 蜂窝发送字节=$cellularSendPacketSize wifi"
            )
        } else if (udpChannel == cellularUdpChannel) {
            cellularSendPacketNum += 1
            cellularSendPacketSize =
                ((cellularSendPacketNum - 1).toDouble() / cellularSendPacketNum.toDouble() * cellularSendPacketSize) + block.size / cellularSendPacketNum.toDouble()
            Log.d(
                TAG,
                "channel: wifi发送个数=$wifiSendPacketNum, wifi发送字节=$wifiSendPacketSize, 蜂窝发送个数=$cellularSendPacketNum, 蜂窝发送字节=$cellularSendPacketSize cellular"
            )
        } else {
            Log.d(TAG, "channel: group_id=$groupID, index=$index, none")
        }
    }

    private suspend fun serveInput(inputBuf: ByteBuffer, channelType: String) = coroutineScope {
        val packetType = inputBuf.int
        val groupId = inputBuf.int.toUInt()
        if (packetType == 0) {
            Log.d(TAG, "serveInput: data, $packetType")
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
        } else {
            ackMutex.lock()
            val index = inputBuf.int
            val packetSendTime = inputBuf.long
            for (ack in ackList) {
                if (ack.second.first == groupId && ack.second.second == index) {
                    ackList.remove(ack)

                    Log.d(TAG, "ackList remove groupId=$groupId index=$index timeDelta=${System.nanoTime() - packetSendTime}")
                    break
                }
            }
            ackPrint()
            ackMutex.unlock()
        }

    }

    private suspend fun handleDecode(
        groupId: UInt, channel: Channel<Pair<String, ByteBuffer>>
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

                var index = inputBuf.int
                val packetSendTime = inputBuf.long

                if ((config!!.mode == 0 || config!!.mode == 1) && index >= config!!.dataNum + config!!.parityNum) {
                    continue
                }
                if (config!!.mode == 2 && index >= 2 * config!!.dataNum) {
                    continue
                }
//                if(index==config!!.dataNum-1){
//                    continue
//                }
                Log.d(
                    TAG,
                    "input: groupId=$groupId, index=$index, length=${inputBuf.remaining()}, channelType=$channelType, primaryChannel=$primaryChannel, dropMode=$dropMode"
                )
                val randomNum = (1..100).random()
                if (dropMode == "主路随机丢包") {
                    if ((channelType == "wifi" && primaryChannel == "Wifi") || (channelType == "cellular" && primaryChannel == "Cellular")) {
                        if (randomNum <= (config!!.dropRate)) {
                            continue
                        }
                    }
                } else {
                    if (randomNum <= (config!!.dropRate)) {
//                    Log.d(TAG, "handleDecode: groupId=$groupId, index=$index dropout $randomNum")
                        continue
                    }
                }
                Log.d(
                    TAG,
                    "nondrop input: groupId=$groupId, index=$index, length=${inputBuf.remaining()}"
                )

                val packetReceiveTime = System.currentTimeMillis()
                if (channelType == "wifi") {
                    wifiDelay = packetReceiveTime - packetSendTime
                } else {
                    cellularDelay = packetReceiveTime - packetSendTime
                }

                // 如果是多倍发包，那么该冗余包的index直接转成对应的数据包index
                if (index >= config!!.dataNum && config!!.mode == 2) {
                    index -= config!!.dataNum
                }

                // 如果该index的包已经接受过，就跳过（目前版本应该不会触发）
                if (marks[index] == 0) {
                    continue
                }

                receiveNum += 1
                marks[index] = 0

//                Log.d(
//                    TAG,
//                    "handleDecode: groupId=$groupId, index=$index, receiveNum=$receiveNum, packetSendTime=$packetSendTime"
//                )

                // 如果是数据包，直接发给接收队列
                if (index < config!!.dataNum) {
                    val duplicateBuf = inputBuf.duplicate()
                    val packet = IpV4Packet(duplicateBuf)
//                    Log.d(TAG, "groupId=$groupId, index=$index, packet=$packet")
                    rxMutex.lock()
                    rxInsert(packet, groupId, index)
                    rxSend()
                    rxMutex.unlock()
                }
                // 把包的内容复制到dataBlocks里面
                dataBlocks[index] = ByteArray(inputBuf.remaining())
                inputBuf.get(dataBlocks[index]!!)

                // 如果可以解码就解码
                if ((config!!.mode == 0 || config!!.mode == 1) && receiveNum == config!!.dataNum) {
                    var blockSize = dataBlocks.filterNotNull().maxOf { it.size }
                    if (config!!.mode == 0) {
                        blockSize += (4 - blockSize % 4) % 4
                    }
                    for (i in dataBlocks.indices) {
                        if (dataBlocks[i] != null && dataBlocks[i]!!.size < blockSize) {
                            val newArray = ByteArray(blockSize)
                            System.arraycopy(
                                dataBlocks[i]!!, 0, newArray, 0, dataBlocks[i]!!.size
                            )
                            dataBlocks[i] = newArray
                        }
                    }

                    val beforeDec = System.nanoTime()

                    // 解码！！！
                    Log.d("main.c", "decode groupId=$groupId, index=$index")
                    val decodeBlocks =
                        decode(config!!.dataNum, blockNum, dataBlocks, blockSize, config!!.mode)

                    val afterDec = System.nanoTime()
                    val timeDelta = afterDec - beforeDec
                    decTime = if (encTime < 0) {
                        timeDelta.toDouble()
                    } else {
                        decTime * 0.9 + timeDelta * 0.1
                    }
                    decMax = max(decMax, timeDelta.toDouble())
                    decMin = min(decMin, timeDelta.toDouble())
//                    Log.d(
//                        TAG,
//                        "decode timeDelta=$timeDelta, decTime=$decTime, decMin=$decMin, decMax=$decMax"
//                    )

                    // 解码后把没接收到的包发给接收队列
                    rxMutex.lock()
                    for (i in 0 until config!!.dataNum) {
                        if (marks[i] == 1) {
                            val blockBuf = ByteBuffer.allocate(blockSize)
                            blockBuf.put(decodeBlocks[i])
                            blockBuf.flip()
                            val packet = IpV4Packet(blockBuf)
//                            Log.d(TAG, "decode groupId=$groupId, index=$i, packet=$packet")
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

    // 把包放进接收队列
    private suspend fun rxInsert(packet: IpV4Packet, groupID: UInt, index: Int) = coroutineScope {
        val rxNew: Triple<IpV4Packet, Pair<UInt, Int>, Long> =
            Triple(packet, Pair(groupID, index), System.nanoTime())

//        Log.d(TAG, " rxInsert: rxGroupId=$rxGroupId, rxIndex=$rxIndex, groupId=$groupID, index=$index, rxNew time=${rxNew.third}")
        // 如果这个包不是我们期待的包（我们已经忽略了）就直接跳过
        if (rxGroupId > groupID || (rxGroupId == groupID && rxIndex > index)) {
            return@coroutineScope
        }

        // 按照顺序插到队列里面
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

    // 接收队列里面的包发给手机
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
            rxTotal = (rxPair.first.toInt() * config!!.dataNum + rxPair.second + 1)
            rxRate = rxCount.toDouble() / rxTotal.toDouble();

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

    // 打印接收队列里面的信息
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

    // 监听接收队列，如果队首的包规定时间内还在队列内，那就直接发给手机
    private suspend fun monitorRxTimeout() = coroutineScope {
        while (true) {
            delay(1)
            rxMutex.lock()
            val now = System.nanoTime()
            if (rxList.isNotEmpty() && now - rxList.first.third > config!!.rxTimeout * 1000) {
//                Log.d(
//                    TAG,
//                    "delta = ${now - rxList.first.third}, thread = ${config!!.rxTimeout * 1000}"
//                )
                rxSend()
            }
            rxMutex.unlock()
        }
    }

    private suspend fun ackPrint() = coroutineScope {

        // print ack list
        var ackLog = ""
        ackLog += "Left ${ackList.size} in ackList={"
        for (ack in ackList) {
            ackLog += "(${ack.second.first}, ${ack.second.second}), "
        }
        ackLog += "}"
        Log.d(TAG, ackLog)
    }

    private suspend fun monitorACK() = coroutineScope {
        while (true) {
            delay(100)
            ackMutex.lock()
            val now = System.nanoTime()
            while (ackList.isNotEmpty() && now - ackList.first.first > config!!.ackTimeout * 1000) {
                val timeDelta = now - ackList.first.first
                val ack = ackList.first()
                val groupId = ack.second.first
                val index = ack.second.second
                val packet = IpV4Packet(ByteBuffer.wrap(ack.third))
                val parityChannel =
                    if (isWifiAvailable.get() && primaryChannel == "Cellular" || !isCellularAvailable.get()) {
                        Log.d(TAG, "parityChannel is Wifi")
                        wifiUdpChannel
                    } else {
                        Log.d(TAG, "parityChannel is Cellular")
                        cellularUdpChannel
                    }
                outputSend(
                    groupId, index, packet.rawData!!, parityChannel
                )
                ackList.removeFirst()
                Log.d(TAG, "ackTimeout: groupId=${ack.second.first}, index=${ack.second.second}, timeDelta=$timeDelta")
            }
            ackMutex.unlock()
        }
    }


}


