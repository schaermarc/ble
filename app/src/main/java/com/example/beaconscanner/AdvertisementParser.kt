package com.example.beaconscanner

/**
 * Walks the raw BLE advertisement payload (AD structures: length/type/value)
 * and extracts the Eddystone service-data payload, if present.
 *
 * This is used as a fallback for ScanRecord.getServiceData(EDDYSTONE_UUID),
 * which returns null on some Android OEM stacks even when the raw bytes
 * clearly contain a 0x16 Service-Data AD with UUID 0xFEAA.
 */
object AdvertisementParser {

    private const val AD_TYPE_SERVICE_DATA_16 = 0x16
    private const val EDDYSTONE_UUID_LO: Int = 0xAA
    private const val EDDYSTONE_UUID_HI: Int = 0xFE

    fun findEddystoneServiceData(raw: ByteArray?): ByteArray? {
        if (raw == null) return null
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xff
            if (len == 0) return null
            if (i + len >= raw.size) return null
            val type = raw[i + 1].toInt() and 0xff
            if (type == AD_TYPE_SERVICE_DATA_16 && len >= 3) {
                val lo = raw[i + 2].toInt() and 0xff
                val hi = raw[i + 3].toInt() and 0xff
                if (lo == EDDYSTONE_UUID_LO && hi == EDDYSTONE_UUID_HI) {
                    return raw.copyOfRange(i + 4, i + 1 + len)
                }
            }
            i += len + 1
        }
        return null
    }

    fun toHex(bytes: ByteArray?, max: Int = Int.MAX_VALUE): String {
        if (bytes == null) return ""
        val limit = minOf(bytes.size, max)
        val sb = StringBuilder(limit * 2)
        for (i in 0 until limit) sb.append("%02x".format(bytes[i]))
        if (bytes.size > limit) sb.append("…")
        return sb.toString()
    }
}
