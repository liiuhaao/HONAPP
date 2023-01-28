package com.example.honapp.packet

import com.example.honapp.packet.header.Header

abstract class Packet {
    abstract fun getHeader(): Header?
    abstract fun getPayload(): Packet?
    
}

