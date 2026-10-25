package harness

import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.TimeFilter

/** Controlla adjust(): lunghezza giusta, campioni ripetuti o tolti uno alla volta e sparsi. */
fun main() {
    val engine = AudioEngine({ _, _ -> null }, { null }, TimeFilter())
    val m = AudioEngine::class.java.getDeclaredMethod("adjust", ShortArray::class.java, Int::class.java, Int::class.java, Int::class.java)
    m.isAccessible = true
    var failures = 0
    for (frames in listOf(201, 960, 1199, 1200, 4096)) for (channels in listOf(1, 2)) {
        val step = maxOf(1, frames / 200)
        for (delta in listOf(step, -step, 1, -1)) {
            val pcm = ShortArray(frames * channels) { (it / channels).toShort() }
            val out = m.invoke(engine, pcm, frames, channels, delta) as ShortArray
            val n = out.size / channels
            val ok = n == frames + delta && run {
                var repeats = 0; var skips = 0; var lastEvent = -1; var minGap = Int.MAX_VALUE; var good = out[0].toInt() == 0
                for (i in 0 until n) for (c in 0 until channels) if (out[i * channels + c] != out[i * channels]) good = false
                for (i in 1 until n) {
                    val d = out[i * channels] - out[(i - 1) * channels]
                    when (d) {
                        1 -> Unit
                        0 -> { repeats++; if (lastEvent >= 0) minGap = minOf(minGap, i - lastEvent); lastEvent = i }
                        2 -> { skips++; if (lastEvent >= 0) minGap = minOf(minGap, i - lastEvent); lastEvent = i }
                        else -> good = false
                    }
                }
                val last = out[(n - 1) * channels].toInt() == frames - 1
                good && last && (if (delta > 0) repeats == delta && skips == 0 else skips == -delta && repeats == 0) &&
                    (minGap == Int.MAX_VALUE || minGap >= frames / (kotlin.math.abs(delta) + 1) - 1)
            }
            if (!ok) { failures++; println("FAIL frames=$frames ch=$channels delta=$delta n=$n") }
        }
    }
    println(if (failures == 0) "adjust OK" else "adjust: $failures errori")
}
