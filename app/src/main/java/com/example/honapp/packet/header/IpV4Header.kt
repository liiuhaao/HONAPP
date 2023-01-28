package com.example.honapp.packet.header

import com.example.honapp.packet.TransportProtocol
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.experimental.and

class IpV4Header() : Header() {
    var versionAndIHL: Byte? = null
    var version: Byte? = null
    var ihl: Byte? = null
    var headerLength: Int? = null

    var typeOfService: Short? = null
    var totalLength: Int? = null

    var identificationAndFlagsAndFragmentOffset: Int? = null

    var ttl: Short? = null
    var protocolNum: Int? = null
    var protocol: TransportProtocol? = null
    var headerChecksum: Int? = null

    var sourceAddress: InetAddress? = null
    var destinationAddress: InetAddress? = null

    constructor(buffer: ByteBuffer) : this() {
        versionAndIHL = buffer.get();
        version = (versionAndIHL!!.toInt() shr 4).toByte()
        ihl = (versionAndIHL!!.and((0x0F).toByte()))
        headerLength = ihl!!.toInt() shl 2;

        typeOfService = BitUtils.getUnsignedByte(buffer.get())
        totalLength = BitUtils.getUnsignedShort(buffer.short)

        identificationAndFlagsAndFragmentOffset = buffer.int

        ttl = BitUtils.getUnsignedByte(buffer.get())
        protocolNum = BitUtils.getUnsignedByte(buffer.get()).toInt()
        protocol = TransportProtocol.numberToEnum(protocolNum!!)
        headerChecksum = BitUtils.getUnsignedShort(buffer.short)

        val addressBytes = ByteArray(4)
        buffer.get(addressBytes, 0, 4);
        sourceAddress = InetAddress.getByAddress(addressBytes)
        buffer.get(addressBytes, 0, 4);
        destinationAddress = InetAddress.getByAddress(addressBytes)
    }

    override fun toString(): String {
        val sb = StringBuilder("IP4Header{")
        sb.append("version=").append(version?.toInt())
        sb.append(", IHL=").append(ihl?.toInt())
        sb.append(", typeOfService=").append(typeOfService?.toInt())
        sb.append(", totalLength=").append(totalLength)
        sb.append(", identificationAndFlagsAndFragmentOffset=")
            .append(identificationAndFlagsAndFragmentOffset)
        sb.append(", TTL=").append(ttl?.toInt())
        sb.append(", protocol=").append(protocolNum?.toInt()).append(":").append(protocol)
        sb.append(", headerChecksum=").append(headerChecksum)
        sb.append(", sourceAddress=").append(sourceAddress?.getHostAddress())
        sb.append(", destinationAddress=").append(destinationAddress?.getHostAddress())
        return sb.toString()
    }
}
