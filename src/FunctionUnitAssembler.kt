import java.util.*

/**
 * reads program from string and assembles a FunctionUnit class
 */
class FunctionUnitAssembler(private val full_program: String, private val functionIndexMap: Map<String, Int>) {
    private var index = 0
    private val program get() = full_program.substring(index until full_program.length)

    private val localVars = mutableMapOf<String, Int>()
    private val labels = mutableMapOf<String, UShort>()

    private val intermediateCode = mutableListOf<IntermediateCode>()
    private var currentCodeLength = 0

    /**
     * Appends [code] to the intermediate code representation. Also updates the [currentCodeLength] variable.
     */
    private fun appendIntermediateCode(code: IntermediateCode) {
        intermediateCode.add(code)
        currentCodeLength += if (code.isLabelRef) BYTES_PER_LABEL_REFERENCE else code.bytes!!.size
    }

    /**
     * returns if the next characters in the program are [expected]
     */
    private fun lookahead(expected: String): Boolean {
        return program.startsWith(expected)
    }

    /**
     *  Consumes the [expected] string from the program. Throws [ParseError] if the expected string is not next in the program.
     */
    private fun consume(expected: String): String {
        if (program.startsWith(expected)) {
            index += expected.length
            return expected
        }

        throw ParseError("expected [$expected], got $program")
    }

    /**
     * Consumes an identifier from the program, throwing a [ParseError] if one is not found
     */
    private fun parseIdent(): String {
        var ident = ""
        while (program[0].isLetterOrDigit()) {
            ident += program[0]
            index++
        }
        if (ident != "") {
            return ident
        }

        throw ParseError("expected identifier, got $program")
    }

    private fun parseWhitespace() {
        while (program[0] in WHITESPACE_CHARS) {
            index++
        }
    }

    private fun parseNewline() {
        parseWhitespace()
        do {
            consume("\n")
            parseWhitespace()
        } while (lookahead("\n"))
    }

    private fun parseMaybeNewline() {
        parseWhitespace()
        do {
            if (lookahead("\n"))
                consume("\n")
            parseWhitespace()
        } while (lookahead("\n"))
    }

    /**
     * Consumes a string that defines a bytecode operation.
     */
    private fun parseOperation(): Operation {
        val operationMapEntry = OpCodes.entries.filter { program.startsWith(it.key) }.maxBy { it.key.length }
        if (operationMapEntry != null) {
            index += operationMapEntry.key.length
            return operationMapEntry.value
        } else {
            throw ParseError("expected operation, got $program")
        }
    }

    /**
     * @return the name of the parsed function
     */
    private fun parseFunction(): String {
        parseMaybeNewline()
        consume(FUNCTION_DECLARATION_STRING)
        parseWhitespace()
        val functionName = parseIdent()
        parseNewline()
        parseLocals()
        parseFunctionCode()

        return functionName
    }

    /**
     * Parses and consumes local declarations.
     */
    private fun parseLocals() {
        parseWhitespace()

        var localsIndex = 0

        while (lookahead(LOCAL_DECLARATION_STRING)) {
            consume(LOCAL_DECLARATION_STRING)
            parseWhitespace()
            val varName = parseIdent()
            parseNewline()
            localVars[varName] = localsIndex
            localsIndex++
        }
    }

    /**
     * Parses the code inside a function.
     * This doesn't include local declarations.
     */
    private fun parseFunctionCode() {
        while (!lookahead(FUNCTION_END_STRING)) {
            if (lookahead(LABEL_DECLARATION_STRING)) {
                consume(LABEL_DECLARATION_STRING)
                parseWhitespace()
                val labelName = parseIdent()
                parseNewline()
                labels[labelName] = currentCodeLength.toUShort()
            } else {
                // must be an opcode
                val operation = parseOperation()
                appendIntermediateCode(IntermediateCode(operation.opcode))
                for (i in 0 until operation.expectedArguments) {
                    parseWhitespace()
                    val argument = parseArgument()
                    appendIntermediateCode(argument)
                }
                parseNewline()
            }
        }

        consume(FUNCTION_END_STRING)
    }

    /**
     * Parses an argument to a bytecode operation.
     */
    private fun parseArgument(): IntermediateCode {
        when {
            lookahead(VARIABLE_REFERENCE_PREFIX) -> {
                // variable references
                consume(VARIABLE_REFERENCE_PREFIX)
                val varName = parseIdent()
                return IntermediateCode(checkNotNull(localVars[varName]?.toUShort()) { "Undeclared variable $varName" })
            }

            lookahead(LABEL_REFERENCE_PREFIX) -> {
                // label references
                consume(LABEL_REFERENCE_PREFIX)
                val name = parseIdent()
                return IntermediateCode(name)
            }

            lookahead(HEX_LITERAL_PREFIX) -> {
                //hex literals
                consume(HEX_LITERAL_PREFIX)
                var numberLength = 0
                while (program[numberLength] in "0123456789ABCDEF") {
                    numberLength++
                }
                val num = program.substring(0 until numberLength).toUShort(16)
                index += numberLength
                return IntermediateCode(num)
            }

            else -> {
                //decimal literals
                var numberLength = 0
                while (program[numberLength] in "0123456789") {
                    numberLength++
                }
                val num = program.substring(0 until numberLength).toUShort()
                index += numberLength
                return IntermediateCode(num)
            }
        }
    }

    /**
     * @return [FunctionUnit] and how many characters were consumed
     */
    fun getFunctionUnit(): Pair<FunctionUnit, Int> {
        val name = parseFunction()
        //each local gets allocated 2 bytes, hence multiplying the number of locals by 2
        val localsAllocationSize = (localVars.size * BYTES_ALLOCATED_PER_LOCAL_VAR).toUShort()
        return Pair(FunctionUnit(localsAllocationSize, flattenIntermediateCode(), name), index)
    }

    private fun flattenIntermediateCode(): UByteArray =
        //Null ptr exception here likely means an unrecognised label
        intermediateCode
            .flatMap { if (it.isLabelRef) labels[it.labelName]!!.toUByteArray() else it.bytes!! }
            .toUByteArray()

    companion object {
        private const val BYTES_ALLOCATED_PER_LOCAL_VAR = 2
        private const val BYTES_PER_LABEL_REFERENCE = 2
        private const val FUNCTION_DECLARATION_STRING = ".function"
        private const val FUNCTION_END_STRING = ".endfunction"
        private const val LOCAL_DECLARATION_STRING = ".local"
        private const val LABEL_DECLARATION_STRING = ".label"
        private const val LABEL_REFERENCE_PREFIX = "@"
        private const val VARIABLE_REFERENCE_PREFIX = "#"
        private const val HEX_LITERAL_PREFIX = "0x"
        private const val WHITESPACE_CHARS = " \r\t"
    }
}

/**
 * Maps operation name to an Operation object.
 * loaded from opcodes.properties
 */
object OpCodes : HashMap<String, Operation>() {
    init {
        val prop = Properties()
        prop.load(javaClass.getResourceAsStream("opcodes.properties"))
        putAll(prop.entries.map {
            it.key as String to getOperation(it.key as String, it.value as String)
        })
    }

    private fun getOperation(propName: String, propValue: String): Operation {
        val split = propValue.split(" ")
        return when {
            split.size == 2 -> Operation(propName, split[0].toUByte(16), split[1].toInt())
            split.size == 1 -> Operation(propName, split[0].toUByte(16), 0)
            else -> throw IllegalArgumentException("malformed opcode specification in opcodes.properties")
        }
    }
}

data class Operation(val name: String, val opcode: UByte, val expectedArguments: Int)

class ParseError(message: String) : Error(message)

/**
 * Intermediate representation bytecode that can be either bare bytecode or a label referee that will be baked in to bytecode later.
 */
data class IntermediateCode private constructor(
    val isLabelRef: Boolean,
    val labelName: String?,
    val bytes: UByteArray?
) {
    constructor(labelName: String) : this(true, labelName, null)
    constructor(vararg bytes: UByte) : this(false, null, bytes)

    /**
     * Split short in to high and low bytes and make an array of both of them.
     */
    constructor(short: UShort) : this(false, null, short.toUByteArray())
}

data class FunctionUnit(
    val localsAllocationSize: UShort,
    val byteCode: UByteArray,
    val functionName: String
)

/**
 * Short to array of 2 bytes. Big endian.
 */
fun UShort.toUByteArray() = ubyteArrayOf(
    (this.and(0xFF00u).toInt() shr 8).toUByte(),
    this.and(0x00FFu).toUByte()
)