package inflation.learning

import java.io.File
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A small fully connected network with tanh hidden layers and one linear output: plain Kotlin,
 * no dependencies. All weights live in one array ([w]) so that trainers can perturb, average and
 * save them as a whole. [forward] needs a per-thread [Buffers]; the network itself is read-only
 * while deciding, so many threads can share it.
 */
class Mlp(val sizes: IntArray, val w: FloatArray = FloatArray(count(sizes))) {
    init {
        require(w.size == count(sizes))
    }

    /** Activations of every layer, input first; one per thread. */
    inner class Buffers {
        val a = Array(sizes.size) { FloatArray(sizes[it]) }
        internal val d = Array(sizes.size) { FloatArray(sizes[it]) }
    }

    fun buffers() = Buffers()

    /** Runs the network on the input already written to `b.a[0]` and returns the output. */
    fun forward(b: Buffers): Float {
        var off = 0
        for (l in 1 until sizes.size) {
            val nIn = sizes[l - 1]; val nOut = sizes[l]
            val x = b.a[l - 1]; val y = b.a[l]
            val last = l == sizes.size - 1
            for (j in 0 until nOut) {
                var s = w[off + nIn * nOut + j] // bias
                val row = off + j * nIn
                for (i in 0 until nIn) s += w[row + i] * x[i]
                y[j] = if (last) s else tanh(s)
            }
            off += nIn * nOut + nOut
        }
        return b.a.last()[0]
    }

    /** Runs the network on the input already written to `b.a[0]`; all its outputs are in `b.a.last()`. */
    fun forwardAll(b: Buffers): FloatArray { forward(b); return b.a.last() }

    /** Adds to [grad] the gradient of the (first) output times [dOut], after a [forward] on [b]. */
    fun backward(b: Buffers, dOut: Float, grad: FloatArray, dInput: FloatArray? = null) {
        val offs = IntArray(sizes.size)
        for (l in 1 until sizes.size) offs[l] = offs[l - 1] + (if (l > 1) sizes[l - 2] * sizes[l - 1] + sizes[l - 1] else 0)
        b.d.last()[0] = dOut
        for (l in sizes.size - 1 downTo 1) {
            val nIn = sizes[l - 1]; val nOut = sizes[l]
            val off = offs[l]
            val x = b.a[l - 1]; val dy = b.d[l]; val dx = b.d[l - 1]
            if (l > 1) dx.fill(0f) else dInput?.fill(0f)
            for (j in 0 until nOut) {
                val g = dy[j]
                if (g == 0f) continue
                grad[off + nIn * nOut + j] += g
                val row = off + j * nIn
                for (i in 0 until nIn) {
                    grad[row + i] += g * x[i]
                    if (l > 1) dx[i] += g * w[row + i] else if (dInput != null) dInput[i] += g * w[row + i]
                }
            }
            // Through the tanh of the layer below.
            if (l > 1) for (i in 0 until nIn) dx[i] *= 1f - x[i] * x[i]
        }
    }

    fun copy() = Mlp(sizes, w.copyOf())

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(sizes.joinToString(" ") + "\n" + w.joinToString(" ") + "\n")
    }

    companion object {
        fun count(sizes: IntArray) = (1 until sizes.size).sumOf { sizes[it - 1] * sizes[it] + sizes[it] }

        /** Small random weights scaled to each layer's fan-in. */
        fun random(sizes: IntArray, rnd: Random): Mlp {
            val m = Mlp(sizes)
            var off = 0
            for (l in 1 until sizes.size) {
                val nIn = sizes[l - 1]; val nOut = sizes[l]
                val scale = 1f / sqrt(nIn.toFloat())
                for (k in 0 until nIn * nOut) m.w[off + k] = scale * (2f * rnd.nextFloat() - 1f)
                off += nIn * nOut + nOut
            }
            return m
        }

        fun load(text: String): Mlp {
            val lines = text.trim().lines()
            val sizes = lines[0].trim().split(" ").map { it.toInt() }.toIntArray()
            val w = lines[1].trim().split(" ").map { it.toFloat() }.toFloatArray()
            return Mlp(sizes, w)
        }
    }
}

/** Adam optimiser over a flat weight array. */
class Adam(n: Int, private val rate: Float, private val b1: Float = 0.9f, private val b2: Float = 0.999f) {
    private val m = FloatArray(n)
    private val v = FloatArray(n)
    private var t = 0

    fun step(w: FloatArray, grad: FloatArray) {
        t++
        val c1 = 1f - Math.pow(b1.toDouble(), t.toDouble()).toFloat()
        val c2 = 1f - Math.pow(b2.toDouble(), t.toDouble()).toFloat()
        for (i in w.indices) {
            m[i] = b1 * m[i] + (1 - b1) * grad[i]
            v[i] = b2 * v[i] + (1 - b2) * grad[i] * grad[i]
            w[i] -= rate * (m[i] / c1) / (sqrt(v[i] / c2) + 1e-8f)
        }
    }
}
