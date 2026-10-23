package com.sukhansh.canonview

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpException(msg: String) : Exception(msg)

class PtpResult(val code: Int, val data: ByteArray?)

/** Minimal PTP-over-USB transport (bulk endpoints only, no events). */
class PtpUsb(
    private val conn: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) {
    private var txId = 0
    private var rx = ByteArray(0)
    private val chunk = ByteArray(16384)

    private fun container(type: Int, code: Int, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(12 + payload.size)
        b.putShort(type.toShort())
        b.putShort(code.toShort())
        b.putInt(txId)
        b.put(payload)
        return b.array()
    }

    private fun write(bytes: ByteArray, timeoutMs: Int) {
        val n = conn.bulkTransfer(bulkOut, bytes, bytes.size, timeoutMs)
        if (n != bytes.size) throw PtpException("bulk write failed ($n/${bytes.size})")
    }

    private fun fill(need: Int, timeoutMs: Int) {
        while (rx.size < need) {
            val n = conn.bulkTransfer(bulkIn, chunk, chunk.size, timeoutMs)
            if (n < 0) throw PtpException("bulk read failed/timeout")
            if (n > 0) rx += chunk.copyOf(n) // n == 0 is a zero-length packet, keep reading
        }
    }

    /** Returns (type, code, payload) of the next PTP container. */
    private fun readContainer(timeoutMs: Int): Triple<Int, Int, ByteArray> {
        fill(12, timeoutMs)
        val hdr = ByteBuffer.wrap(rx, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val len = hdr.int.toLong() and 0xFFFFFFFFL
        val type = hdr.short.toInt() and 0xFFFF
        val code = hdr.short.toInt() and 0xFFFF
        if (len < 12 || len > (64L * 1024 * 1024)) throw PtpException("bad container length $len")
        fill(len.toInt(), timeoutMs)
        val payload = rx.copyOfRange(12, len.toInt())
        rx = rx.copyOfRange(len.toInt(), rx.size)
        return Triple(type, code, payload)
    }

    fun transact(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 3000,
    ): PtpResult {
        txId++
        val p = ByteBuffer.allocate(params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        params.forEach { p.putInt(it) }
        write(container(1, opcode, p.array()), timeoutMs)
        dataOut?.let { write(container(2, opcode, it), timeoutMs) }

        var data: ByteArray? = null
        while (true) {
            val (type, code, payload) = readContainer(timeoutMs)
            when (type) {
                2 -> data = payload
                3 -> return PtpResult(code, data)
                // type 1/4 shouldn't show up here; ignore
            }
        }
    }
}