package inflation.ecology

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The JIT does not compile a method of more than 8000 bytes of bytecode (HotSpot's HugeMethodLimit):
 * it runs interpreted, many times slower. The planner once grew 1 byte past it and the weekly choosing
 * ran 4.5 times slower on the big worlds. The step's methods must stay under it.
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
                    if (attr == "Code") { inp.readUnsignedShort(); inp.readUnsignedShort(); val code = inp.readInt(); out[name] = maxOf(out[name] ?: 0, code); inp.skipBytes(len - 8) }
                    else inp.skipBytes(len)
                }
            }
            return out
        }
    }

    @Test
    fun theStepsMethodsAreSmallEnoughToCompile() {
        for (c in listOf(HexGroups::class.java, Class.forName("inflation.ecology.HexMeetingsKt"))) {
            val huge = codeLengths(c).filter { it.value > limit && it.key != "<clinit>" }
            assertTrue(huge.isEmpty(), "${c.simpleName}: methods over $limit bytes run interpreted: $huge")
        }
        val plan = codeLengths(HexGroups::class.java).getValue("planInner")
        println("planInner: $plan bytes of $limit")
    }
}
