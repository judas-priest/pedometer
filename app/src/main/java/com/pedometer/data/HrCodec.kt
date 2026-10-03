package com.pedometer.data

/**
 * Lossless codec for one day of heart-rate samples: delta timestamps + bpm bytes,
 * Deflate-packed. Decompress restores rows bit-in-bit (reversible — unlike
 * downsampling). Guarded against empty inputs on both sides.
 */
object HrCodec {
    data class Sample(val timestamp: Long, val bpm: Int)

    fun compress(samples: List<Sample>): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val raw = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(raw).use { out ->
            out.writeLong(samples.first().timestamp)
            var prev = samples.first().timestamp
            for (s in samples) {
                out.writeInt((s.timestamp - prev).toInt())
                out.writeByte(s.bpm)
                prev = s.timestamp
            }
        }
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val buf = ByteArray(64 * 1024); val out = java.io.ByteArrayOutputStream()
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    fun decompress(blob: ByteArray): List<Sample> {
        if (blob.isEmpty()) return emptyList()
        val inflater = java.util.zip.Inflater()
        inflater.setInput(blob)
        val buf = ByteArray(64 * 1024); val raw = java.io.ByteArrayOutputStream()
        while (!inflater.finished()) raw.write(buf, 0, inflater.inflate(buf))
        inflater.end()
        java.io.DataInputStream(raw.toByteArray().inputStream()).use { inp ->
            val t0 = inp.readLong()
            val out = ArrayList<Sample>(2048)
            var prev = t0
            while (true) {
                val dt = try { inp.readInt() } catch (_: Exception) { break }
                val bpm = inp.readByte().toInt() and 0xFF
                prev += dt
                out.add(Sample(prev, bpm))
            }
            return out
        }
    }
}
