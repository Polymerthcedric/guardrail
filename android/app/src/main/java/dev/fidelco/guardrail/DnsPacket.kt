package dev.fidelco.guardrail

object DnsPacket {

    const val PROTO_UDP = 17
    const val DNS_PORT = 53

    class IpMeta(val ipLen: Int, val proto: Int, val ver: Int)

    fun version(buf: ByteArray): Int = (buf[0].toInt() ushr 4) and 0x0F

    fun headerLen(buf: ByteArray): Int = (buf[0].toInt() and 0x0F) * 4

    fun meta(buf: ByteArray, n: Int): IpMeta? {
        return when (version(buf)) {
            4 -> {
                val ihl = headerLen(buf)
                if (ihl < 20 || ihl + 28 > n) return null
                IpMeta(ihl, buf[9].toInt() and 0xFF, 4)
            }
            6 -> {
                var off = 40
                var nh = buf[6].toInt() and 0xFF
                while (nh == 0 || nh == 43 || nh == 44 || nh == 51 ||
                    nh == 60 || nh == 135 || nh == 139 || nh == 140
                ) {
                    if (nh == 44) return null
                    if (off + 2 > n) return null
                    off += ((buf[off + 1].toInt() and 0xFF) + 1) * 8
                    if (off + 8 > n) return null
                    nh = buf[off].toInt() and 0xFF
                }
                if (off + 28 > n) return null
                IpMeta(off, nh, 6)
            }
            else -> null
        }
    }

    fun protocol(buf: ByteArray, ipLen: Int): Int = buf[ipLen + 9].toInt() and 0xFF

    fun isFragment(buf: ByteArray, ipLen: Int): Boolean {
        val offset = ((buf[6].toInt() and 0x1F) shl 8) or (buf[7].toInt() and 0xFF)
        return (buf[6].toInt() and 0x20) != 0 || offset != 0
    }

    fun dstPort(buf: ByteArray, ipLen: Int): Int =
        ((buf[ipLen + 2].toInt() and 0xFF) shl 8) or (buf[ipLen + 3].toInt() and 0xFF)

    /** Returns the end of a validated UDP DNS datagram, or null for bad lengths. */
    fun udpPacketEnd(buf: ByteArray, packetLength: Int, ipLen: Int, ver: Int): Int? {
        if (packetLength > buf.size || ipLen + 8 > packetLength) return null
        val ipPacketEnd = if (ver == 4) {
            ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        } else {
            40 + (((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF))
        }
        if (ipPacketEnd > packetLength || ipPacketEnd < ipLen + 8) return null
        val udpLen = ((buf[ipLen + 4].toInt() and 0xFF) shl 8) or (buf[ipLen + 5].toInt() and 0xFF)
        if (udpLen < 25 || udpLen > ipPacketEnd - ipLen) return null
        return ipLen + udpLen
    }

    class Question(val name: String, val section: ByteArray)

    fun readQuestion(buf: ByteArray, ipLen: Int, packetLength: Int): Question? {
        if (packetLength > buf.size || packetLength < ipLen + 20) return null
        val dnsStart = ipLen + 8
        if (dnsStart + 12 > packetLength) return null
        // Only a single, uncompressed question is supported by this local
        // filter. Reject malformed or multi-question packets rather than
        // accidentally parsing bytes outside the received datagram.
        if ((buf[dnsStart + 2].toInt() and 0x80) != 0) return null
        if ((buf[dnsStart + 4].toInt() and 0xFF) != 0 ||
            (buf[dnsStart + 5].toInt() and 0xFF) != 1
        ) return null
        var pos = ipLen + 20
        val sectionStart = pos
        val sb = StringBuilder()
        while (pos < packetLength) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            if ((len and 0xC0) == 0xC0 || len > 63) return null
            if (pos + 1 + len > packetLength) return null
            val label = String(buf, pos + 1, len).lowercase()
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(label)
            pos += 1 + len
        }
        if (pos + 4 > packetLength || sb.isEmpty()) return null
        val section = ByteArray(pos + 4 - sectionStart)
        System.arraycopy(buf, sectionStart, section, 0, section.size)
        return Question(sb.toString(), section)
    }

    fun nxdomainReply(request: ByteArray, ipLen: Int, ver: Int, q: Question): ByteArray {
        val dns = ByteArray(12 + q.section.size)
        dns[0] = request[ipLen + 8]
        dns[1] = request[ipLen + 9]
        dns[2] = 0x81.toByte()
        dns[3] = 0x83.toByte()
        dns[5] = 0x01
        System.arraycopy(q.section, 0, dns, 12, q.section.size)
        return buildReply(request, ipLen, ver, dns)
    }

    fun forwardReply(request: ByteArray, ipLen: Int, ver: Int, reply: ByteArray): ByteArray =
        buildReply(request, ipLen, ver, reply)

    private fun buildReply(request: ByteArray, ipLen: Int, ver: Int, payload: ByteArray): ByteArray =
        if (ver == 4) buildReply4(request, ipLen, payload) else buildReply6(request, ipLen, payload)

    private fun buildReply4(request: ByteArray, ipLen: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = ipLen + udpLen
        val out = ByteArray(total)

        System.arraycopy(request, 0, out, 0, ipLen)

        val src = request.copyOfRange(12, 16)
        val dst = request.copyOfRange(16, 20)
        System.arraycopy(dst, 0, out, 12, 4)
        System.arraycopy(src, 0, out, 16, 4)

        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()

        out[10] = 0
        out[11] = 0
        val ipSum = checksum(out, ipLen)
        out[10] = ((ipSum ushr 8) and 0xFF).toByte()
        out[11] = (ipSum and 0xFF).toByte()

        val u = ipLen
        out[u] = ((DNS_PORT ushr 8) and 0xFF).toByte()
        out[u + 1] = (DNS_PORT and 0xFF).toByte()
        out[u + 2] = request[ipLen]
        out[u + 3] = request[ipLen + 1]
        out[u + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        out[u + 5] = (udpLen and 0xFF).toByte()

        System.arraycopy(payload, 0, out, ipLen + 8, payload.size)

        val udpSum = udpChecksum4(out, ipLen, udpLen)
        out[u + 6] = ((udpSum ushr 8) and 0xFF).toByte()
        out[u + 7] = (udpSum and 0xFF).toByte()
        return out
    }

    private fun buildReply6(request: ByteArray, ipLen: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = ipLen + udpLen
        val out = ByteArray(total)

        System.arraycopy(request, 0, out, 0, ipLen)

        val src = request.copyOfRange(8, 24)
        val dst = request.copyOfRange(24, 40)
        System.arraycopy(dst, 0, out, 8, 16)
        System.arraycopy(src, 0, out, 24, 16)

        val payloadLen = total - 40
        out[4] = ((payloadLen ushr 8) and 0xFF).toByte()
        out[5] = (payloadLen and 0xFF).toByte()

        val u = ipLen
        out[u] = 0
        out[u + 1] = DNS_PORT.toByte()
        out[u + 2] = request[ipLen + 2]
        out[u + 3] = request[ipLen + 3]
        out[u + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        out[u + 5] = (udpLen and 0xFF).toByte()

        System.arraycopy(payload, 0, out, ipLen + 8, payload.size)

        val udpSum = udpChecksum6(out, ipLen, udpLen)
        out[u + 6] = ((udpSum ushr 8) and 0xFF).toByte()
        out[u + 7] = (udpSum and 0xFF).toByte()
        return out
    }

    fun checksum(data: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i + 1 < length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun udpChecksum4(pkt: ByteArray, ipLen: Int, udpLen: Int): Int {
        var sum = 0L
        var i = 12
        while (i < 20) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += PROTO_UDP
        sum += udpLen
        i = ipLen
        val end = ipLen + udpLen
        while (i + 1 < end) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (pkt[i].toInt() and 0xFF) shl 8
        var s: Int = ((sum and 0xFFFF) + (sum ushr 16)).toInt()
        s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    private fun udpChecksum6(pkt: ByteArray, ipLen: Int, udpLen: Int): Int {
        var sum = 0L
        var i = 8
        while (i < 40) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += PROTO_UDP
        sum += udpLen
        i = ipLen
        val end = ipLen + udpLen
        while (i + 1 < end) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (pkt[i].toInt() and 0xFF) shl 8
        var s: Int = ((sum and 0xFFFF) + (sum ushr 16)).toInt()
        s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }
}
