package inflation.ecology

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The JIT does not compile a method of more than 8000 bytes of bytecode (HotSpot's HugeMethodLimit):
 * it runs interpreted, many times slower. No method of the model may pass it.
 */
class HugeMethodTest {
    private val limit = 8000

    /** Bytecode length of each method of class [c], by name. */
    private fun codeLengths(c: Class<*>): Map<String, Int> {
        val res = c.getResourceAsStream("/" + c.name.replace('.', '/') + ".class") ?: error("no class file for ${c.name}")
        DataInputStream(res.buffered()).use { inp ->
            require(inp.readInt() == 0xCAFEBABE.toInt())
            inp.readUnsignedShort(); inp.readUnsignedShort()
            val n = inp.readUnsignedShort()
            val utf = arrayOfNulls<String>(n)
            var i = 1
            while (i < n) {
                when (val tag = inp.readUnsignedByte()) {
                    1 -> utf[i] = inp.readUTF()
                    3, 4 -> inp.readInt()
                    5, 6 -> { inp.readLong(); i++ }
                    7, 8, 16, 19, 20 -> inp.readUnsignedShort()
                    9, 10, 11, 12, 17, 18 -> inp.readInt()
                    15 -> { inp.readUnsignedByte(); inp.readUnsignedShort() }
                    else -> error("constant tag $tag")
                }
                i++
            }
            inp.readUnsignedShort(); inp.readUnsignedShort(); inp.readUnsignedShort()
            repeat(inp.readUnsignedShort()) { inp.readUnsignedShort() }
            fun skipAttributes() = repeat(inp.readUnsignedShort()) { inp.readUnsignedShort(); inp.skipBytes(inp.readInt()) }
            repeat(inp.readUnsignedShort()) { inp.readUnsignedShort(); inp.readUnsignedShort(); inp.readUnsignedShort(); skipAttributes() }
            val out = HashMap<String, Int>()
            repeat(inp.readUnsignedShort()) {
                inp.readUnsignedShort(); val name = utf[inp.readUnsignedShort()]!!; inp.readUnsignedShort()
                repeat(inp.readUnsignedShort()) {
                    val attr = utf[inp.readUnsignedShort()]; val len = inp.readInt()
                    if (attr == "Code") {
                        inp.readUnsignedShort()
                        inp.readUnsignedShort()
                        val code = inp.readInt()
                        out[name] = maxOf(out[name] ?: 0, code)
                        inp.skipBytes(len - 8)
                    }
                    else inp.skipBytes(len)
                }
            }
            return out
        }
    }

    /**
     * Every class compiled from src/main, as loaded from the same output as [HexGroups], except the
     * one-off checks in inflation.tools: their long mains run once a run and only call into the step.
     */
    private fun mainClasses(): List<Class<*>> {
        val root = java.io.File(HexGroups::class.java.protectionDomain.codeSource.location.toURI())
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
            .map { Class.forName(it.relativeTo(root).path.removeSuffix(".class").replace(java.io.File.separatorChar, '.'), false, javaClass.classLoader) }
            .filterNot { it.name.startsWith("inflation.tools.") }.toList()
    }

    @Test
    fun theStepsMethodsAreSmallEnoughToCompile() {
        val classes = mainClasses()
        val huge = classes.flatMap { c -> codeLengths(c).filter { it.value > limit && it.key != "<clinit>" }.map { "${c.name}.${it.key}: ${it.value}" } }
        assertTrue(huge.isEmpty(), "methods over $limit bytes run interpreted: $huge")
        val near = classes.flatMap { c -> codeLengths(c).filter { it.value > limit * 3 / 4 && it.key != "<clinit>" }
            .map { "${c.simpleName}.${it.key} ${it.value}" } }
        println("methods over 3/4 of $limit bytes: $near")
    }
}
