/**
 * Generates C #define statements for each opcode, defining their byte value
 */
fun main() {
    var out = ""
    for (code in OpCodes.values) {
        out += "#define OP_${code.name.toUpperCase()} 0x${code.opcode.toString(16).toUpperCase()}\n"
    }

    println(out)
}