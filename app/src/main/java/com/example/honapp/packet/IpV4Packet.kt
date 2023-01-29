package com.example.honapp.packet

import com.example.honapp.packet.header.IpV4Header
import java.nio.ByteBuffer

class IpV4Packet : Packet {
    var header: IpV4Header? = null
    var payload: Packet? = null

    constructor(buffer: ByteBuffer, length: Int) : this(buffer, 0, length)
    constructor(buffer: ByteBuffer, offset: Int, length: Int) : super(buffer, offset, length) {
        header = IpV4Header(buffer)
        payload = Packet(buffer, offset + header!!.headerLength!!, length)
    }

    override fun toString(): String {
        val sb = StringBuilder("IpV4Packet{")
        sb.append("header=").append(header);
        sb.append(", payload").append(payload);
        sb.append('}');
        return sb.toString();
    }
}
