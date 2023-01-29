package com.example.honapp.packet.header

import java.nio.ByteBuffer

class UdpHeader() {
    var sourcePort: Int? = null
    var destinationPort: Int? = null

    var length: Int? = null
    var checksum: Int? = null

    constructor(buffer: ByteBuffer) : this() {
        sourcePort = BitUtils.getUnsignedShort(buffer.short);
        destinationPort = BitUtils.getUnsignedShort(buffer.short);

        length = BitUtils.getUnsignedShort(buffer.short);
        checksum = BitUtils.getUnsignedShort(buffer.short);
    }

    override fun toString(): String {
        val sb = StringBuilder("UDPHeader{")
        sb.append("sourcePort=").append(sourcePort);
        sb.append(", destinationPort=").append(destinationPort);
        sb.append(", length=").append(length);
        sb.append(", checksum=").append(checksum);
        sb.append('}');
        return sb.toString();
    }
}