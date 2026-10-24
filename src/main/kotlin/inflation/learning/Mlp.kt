package inflation.learning

import java.io.File
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A small fully connected network with tanh hidden layers and one linear output: plain Kotlin,
 * no dependencies. All weights live in one array ([w]) so that trainers can perturb, average and
 * save them as a whole. [forward] needs a per-thread [Buffers]; the network itself is read-only
 * while deciding, so many threads can share it.
 *
 * In [w], each layer's weights come input by input (the weight from input i to unit j at [at]),
 * then its biases ([biasAt]): so the loop over a layer's units is innermost and the JIT runs it as
 * vector operations, and each unit's sum still takes its terms one at a time in a fixed order, the
 * same on every machine. Files keep each unit's weights together, as before ([save], [load]).
 */
class Mlp(val sizes: IntArray, val w: FloatArray = FloatArray(count(sizes))) {
    init {
        require(w.size == count(sizes))
    }

    /** Where each layer's weights start in [w] (layer l reads layer l - 1; index 0 unused). */
    private val offs = IntArray(sizes.size).also { for (l in 2 until sizes.size) it[l] = it[l - 1] + sizes[l - 2] * sizes[l - 1] + sizes[l - 1] }

    /** The index in [w] of layer [l]'s weight from input [i] to unit [j] (layers from 1). */
    fun at(l: Int, i: Int, j: Int) = offs[l] + i * sizes[l] + j
    /**
     * A step scale for each weight of [w] that gives the first layer's weights from an input that has hardly been trained (its mean
     * absolute weight below the inputs' average) up to [max] times the step, in proportion to how far below it is: an input that
     * comes in at zero weight (see Schema) then catches up in rounds rather than in weeks of Adam's small steps. Others 1.
     */
    fun smallInputRates(max: Float): FloatArray {
        val rates = FloatArray(w.size) { 1f }
        val h = sizes[1]
        val means = FloatArray(sizes[0]) { i -> var s = 0f; for (j in 0 until h) s += kotlin.math.abs(w[at(1, i, j)]); s / h }
        val avg = means.average().toFloat()
        if (avg <= 0f) return rates
        for (i in means.indices) {
            val r = if (means[i] <= 0f) max else (avg / means[i]).coerceIn(1f, max)
            if (r > 1f) for (j in 0 until h) rates[at(1, i, j)] = r
        }
        return rates
    }

    /** The index in [w] of layer [l]'s bias of unit [j]. */
    fun biasAt(l: Int, j: Int) = offs[l] + sizes[l - 1] * sizes[l] + j

    /** Activations of every layer, input first; one per thread. */
    inner class Buffers {
        val a = Array(sizes.size) { FloatArray(sizes[it]) }
        internal val d = Array(sizes.size) { FloatArray(sizes[it]) }
        /** The first layer's sums over the inputs shared by a batch (see [forwardShared]). */
        internal val shared = FloatArray(sizes[1])
    }

    fun buffers() = Buffers()

    /**
     * Adds to [y] (from [from] on) layer [l]'s weights times inputs [x] [hi] - 1 down to [lo], last first. Every sum
     * is taken in that order, here and in [forwardShared], so that both give the same bits.
     */
    private fun addInputs(l: Int, x: FloatArray, xAt: Int, lo: Int, hi: Int, y: FloatArray) {
        val nOut = sizes[l]
        for (i in hi - 1 downTo lo) {
            val xi = x[xAt + i]
            if (xi == 0f) continue
            val row = offs[l] + i * nOut
            for (j in 0 until nOut) y[j] += w[row + j] * xi
        }
    }

    /** Layers [from] on, from the activations of the layer below already in `b.a`; returns the (first) output. */
    private fun upFrom(from: Int, b: Buffers): Float {
        for (l in from until sizes.size) {
            val y = b.a[l]
            w.copyInto(y, 0, biasAt(l, 0), biasAt(l, 0) + sizes[l])
            addInputs(l, b.a[l - 1], 0, 0, sizes[l - 1], y)
            if (l < sizes.size - 1) squash(y)
        }
        return b.a.last()[0]
    }

    /** Runs the network on the input already written to `b.a[0]` and returns the output. */
    @IgnorableReturnValue fun forward(b: Buffers): Float = upFrom(1, b)

    /** Runs the network on the input already written to `b.a[0]`; all its outputs are in `b.a.last()`. */
    fun forwardAll(b: Buffers): FloatArray { forward(b); return b.a.last() }

    /**
     * Scores [n] inputs that differ only in their first [varying] numbers: input k is `rows[k * varying until (k + 1) * varying]`
     * followed by `shared[varying until sizes[0]]`. The first layer's part from the shared numbers is summed once. Writes the
     * (first) output of each into [out]; each is the same, to the bit, as [forward] on that whole input.
     */
    fun forwardShared(rows: FloatArray, n: Int, varying: Int, shared: FloatArray, out: FloatArray, b: Buffers) {
        val base = b.shared; val h = sizes[1]
        w.copyInto(base, 0, biasAt(1, 0), biasAt(1, 0) + h)
        addInputs(1, shared, 0, varying, sizes[0], base)
        val y = b.a[1]
        for (k in 0 until n) {
            base.copyInto(y)
            addInputs(1, rows, k * varying, 0, varying, y)
            if (sizes.size > 2) squash(y)
            out[k] = if (sizes.size > 2) upFrom(2, b) else y[0]
        }
    }

    /** Adds to [grad] the gradient of the (first) output times [dOut], after a [forward] on [b]. */
    fun backward(b: Buffers, dOut: Float, grad: FloatArray, dInput: FloatArray? = null) {
        b.d.last().fill(0f); b.d.last()[0] = dOut
        backprop(b, grad, dInput)
    }

    /** Adds to [grad] the gradient of the outputs, each times its [dOut], after a [forward] on [b]. */
    fun backwardAll(b: Buffers, dOut: FloatArray, grad: FloatArray, dInput: FloatArray? = null) {
        dOut.copyInto(b.d.last())
        backprop(b, grad, dInput)
    }

    /**
     * Adds to [grad] the gradient of hidden layer [l]'s activations, each times its [dAct], after a [forward] on [b]: the layers
     * below it learning for another network that reads that layer.
     */
    fun backwardFrom(l: Int, b: Buffers, dAct: FloatArray, grad: FloatArray) {
        require(l in 1 until sizes.size - 1) { "layer $l is not a hidden layer" }
        val a = b.a[l]; val d = b.d[l]
        for (i in 0 until sizes[l]) d[i] = dAct[i] * (1f - a[i] * a[i])
        backprop(b, grad, null, l)
    }

    private fun backprop(b: Buffers, grad: FloatArray, dInput: FloatArray?, top: Int = sizes.size - 1) {
        for (l in top downTo 1) {
            val nIn = sizes[l - 1]; val nOut = sizes[l]
            val x = b.a[l - 1]; val dy = b.d[l]
            val dx = if (l > 1) b.d[l - 1] else dInput
            val bias = biasAt(l, 0)
            for (j in 0 until nOut) grad[bias + j] += dy[j]
            for (i in 0 until nIn) {
                val row = offs[l] + i * nOut; val xi = x[i]
                if (xi != 0f) for (j in 0 until nOut) grad[row + j] += dy[j] * xi
                if (dx != null) { var s = 0f; for (j in 0 until nOut) s += w[row + j] * dy[j]; dx[i] = s }
            }
            // Through the tanh of the layer below.
            if (l > 1) for (i in 0 until nIn) b.d[l - 1][i] *= 1f - x[i] * x[i]
        }
    }

    fun copy() = Mlp(sizes, w.copyOf())

    /** Zeros the last layer's weights and biases, so that the network's output is 0 whatever it reads; returns this. */
    @IgnorableReturnValue fun zeroOutputLayer(): Mlp { w.fill(0f, count(sizes.copyOf(sizes.size - 1)), w.size); return this }

    /** The weights in the order files keep them: each layer unit by unit, its weight on each input in turn, then the biases. */
    private fun unitMajor(): FloatArray {
        val f = FloatArray(w.size)
        for (l in 1 until sizes.size) {
            val nIn = sizes[l - 1]; val nOut = sizes[l]
            for (j in 0 until nOut) for (i in 0 until nIn) f[offs[l] + j * nIn + i] = w[at(l, i, j)]
            w.copyInto(f, biasAt(l, 0), biasAt(l, 0), biasAt(l, 0) + nOut)
        }
        return f
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(sizes.joinToString(" ") + "\n" + unitMajor().joinToString(" ") + "\n")
    }

    companion object {
        fun count(sizes: IntArray) = (1 until sizes.size).sumOf { sizes[it - 1] * sizes[it] + sizes[it] }

        /** Small random weights scaled to each layer's fan-in. */
        fun random(sizes: IntArray, rnd: Random): Mlp {
            val m = Mlp(sizes)
            for (l in 1 until sizes.size) {
                val nIn = sizes[l - 1]; val nOut = sizes[l]
                val scale = 1f / sqrt(nIn.toFloat())
                for (j in 0 until nOut) for (i in 0 until nIn) m.w[m.at(l, i, j)] = scale * (2f * rnd.nextFloat() - 1f)
            }
            return m
        }

        /** A network from weights in the order files keep them (see [unitMajor]). */
        fun fromUnitMajor(sizes: IntArray, f: FloatArray): Mlp {
            val m = Mlp(sizes)
            for (l in 1 until sizes.size) {
                val nIn = sizes[l - 1]; val nOut = sizes[l]
                for (j in 0 until nOut) for (i in 0 until nIn) m.w[m.at(l, i, j)] = f[m.offs[l] + j * nIn + i]
                f.copyInto(m.w, m.biasAt(l, 0), m.biasAt(l, 0), m.biasAt(l, 0) + nOut)
            }
            return m
        }

        fun load(text: String): Mlp {
            val lines = text.trim().lines()
            val sizes = lines[0].trim().split(" ").map { it.toInt() }.toIntArray()
            val w = lines[1].trim().split(" ").map { it.toFloat() }.toFloatArray()
            return fromUnitMajor(sizes, w)
        }

        /** Applies [tanh] to every number of [y]. */
        private fun squash(y: FloatArray) { for (j in y.indices) y[j] = tanh(y[j]) }

        private const val A1 = 4.89352455891786e-03f; private const val A3 = 6.37261928875436e-04f; private const val A5 = 1.48572235717979e-05f
        private const val A7 = 5.12229709037114e-08f; private const val A9 = -8.60467152213735e-11f; private const val A11 = 2.00018790482477e-13f
        private const val A13 = -2.76076847742355e-16f
        private const val B0 = 4.89352518554385e-03f; private const val B2 = 2.26843463243900e-03f; private const val B4 = 1.18534705686654e-04f
        private const val B6 = 1.19825839466702e-06f
        private const val CLAMP = 7.90531110763549805f

        /**
         * tanh, to within 4.2e-7 of the true value (the ratio of two polynomials, as Eigen computes it), at about a
         * twentieth of the cost of [kotlin.math.tanh]. Plain float arithmetic, so the same bits on every machine and JVM.
         */
        fun tanh(v: Float): Float {
            val x = max(-CLAMP, min(CLAMP, v))
            val x2 = x * x
            val p = ((((((A13 * x2 + A11) * x2 + A9) * x2 + A7) * x2 + A5) * x2 + A3) * x2 + A1) * x
            val q = ((B6 * x2 + B4) * x2 + B2) * x2 + B0
            return p / q
        }
    }
}

/** Adam optimiser over a flat weight array. */
class Adam(n: Int, private val rate: Float, private val b1: Float = 0.9f, private val b2: Float = 0.999f) {
    private val m = FloatArray(n)
    private val v = FloatArray(n)
    private var t = 0

    /** One step; [rates], if given, scales the step of each weight (1: as is). */
    fun step(w: FloatArray, grad: FloatArray, rates: FloatArray? = null) {
        t++
        val c1 = 1f - Math.pow(b1.toDouble(), t.toDouble()).toFloat()
        val c2 = 1f - Math.pow(b2.toDouble(), t.toDouble()).toFloat()
        for (i in w.indices) {
            m[i] = b1 * m[i] + (1 - b1) * grad[i]
            v[i] = b2 * v[i] + (1 - b2) * grad[i] * grad[i]
            w[i] -= rate * (rates?.get(i) ?: 1f) * (m[i] / c1) / (sqrt(v[i] / c2) + 1e-8f)
        }
    }
}
