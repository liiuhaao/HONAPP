package com.example.honapp.packet.header

import kotlin.experimental.and

class BitUtils {
    companion object {
        fun getUnsignedByte(value: Byte): Short {
            return value.toShort().and((0xFF))
        }

        fun getUnsignedShort(value: Short): Int {
            return value.toInt().and((0xFFFF))
        }

        fun getUnsignedInt(value: Int): Long {
            return value.toLong().and((0xFFFFFFFFL))
        }
    }
}