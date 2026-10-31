package app.getaudiobridge.output

/** Queue through Android's reported presentation point, not acoustic/headphone latency. */
internal object PresentationEstimate {
    fun milliseconds(written: Long, head: Long, position32: Long, timestampNs: Long, nowNs: Long): Double? {
        val age = nowNs - timestampNs
        if (timestampNs <= 0 || age !in -2_000_000_000L..2_000_000_000L) return null
        val wrap = 1L shl 32
        var position = (head / wrap) * wrap + (position32 and 0xffffffffL)
        if (position - head > wrap / 2) position -= wrap
        if (head - position > wrap / 2) position += wrap
        if (position < 0 || position > written) return null
        // A timestamp can describe an earlier frame or a frame committed for a future time.
        val remaining = (written - position).toDouble() / 48.0 - age.toDouble() / 1_000_000.0
        return if (remaining > 2000.0) null else maxOf(0.0, remaining)
    }
}
