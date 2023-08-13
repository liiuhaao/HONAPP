package com.example.honapp

import android.os.Parcel
import android.os.Parcelable

data class HONConfig(
    var dropRate: Int,
    var parityRate: Int,
    var maxRXNum: Int,
    var maxTXNum: Int,
    var encodeTimeout: Long,
    var decodeTimeout: Long,
    var rxTimeout: Long,
    var primaryProbability: Int,
    var ipAddress: String?,
    var port: String?,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readString(),
        parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(dropRate)
        parcel.writeInt(parityRate)
        parcel.writeInt(maxRXNum)
        parcel.writeInt(maxTXNum)
        parcel.writeLong(encodeTimeout)
        parcel.writeLong(encodeTimeout)
        parcel.writeLong(rxTimeout)
        parcel.writeInt(primaryProbability)
        parcel.writeString(ipAddress)
        parcel.writeString(port)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<HONConfig> {
        override fun createFromParcel(parcel: Parcel): HONConfig {
            return HONConfig(parcel)
        }

        override fun newArray(size: Int): Array<HONConfig?> {
            return arrayOfNulls(size)
        }
    }

    override fun toString(): String {
        return "HONConfig={" +
                "dropRate=$dropRate," +
                "parityRate=$parityRate," +
                "maxRXNum=$maxRXNum," +
                "maxTXNum=$maxTXNum," +
                "encodeTimeout=$encodeTimeout," +
                "decodeTimeout=$decodeTimeout," +
                "rxTimeout=$rxTimeout" +
                "primaryProbability=$primaryProbability" +
                "ipAddress='$ipAddress'," +
                "port='$port'," +
                "}"
    }

    fun toJson(): String {
        return """
            {
                "dropRate": $dropRate,
                "parityRate": $parityRate,
                "maxRXNum": $maxRXNum,
                "maxTXNum": $maxTXNum,
                "encodeTimeout": $encodeTimeout,
                "decodeTimeout": $decodeTimeout,
                "rxTimeout": $rxTimeout,
                "primaryProbability": $primaryProbability,
                "ipAddress": "$ipAddress",
                "port": "$port"
            }
        """.trimIndent()
    }
}