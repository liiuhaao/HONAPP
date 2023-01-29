package com.example.honapp.packet

import java.nio.ByteBuffer

open class Packet() {
    var rawData: ByteArray? = null
    var length = 0

    constructor(buffer: ByteBuffer, length: Int) : this(buffer, 0, length)
    constructor(buffer: ByteBuffer, offset: Int, length: Int) : this() {
        this.rawData = ByteArray(length)
        System.arraycopy(buffer.array(), offset, rawData!!, 0, length)
        this.length = length - offset
    }

    override fun toString(): String {
        val sb = StringBuilder("Packet{")
        sb.append("length=").append(length);
        sb.append('}');
        return sb.toString();
    }
}

