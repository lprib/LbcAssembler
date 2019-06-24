class ProgramAssembler(private val program: String) {
    private fun doFunctionNamePass(): Map<String, Int> {
        //TODO optimisation where the most used function gets assigned index 0
        //so as to take advantage of a call_0 command or similar (when implemented)

        val functionIndexMap = mutableMapOf<String, Int>()
        var functionIndex = 0;
        for (line in program.split('\n')) {
            if (line.startsWith(".function")) {
                functionIndexMap[line.split(Regex("\\s"))[1]] = functionIndex
                functionIndex++
            }
        }

        return functionIndexMap
    }

    fun getAssemblyInfo(): ProgramAssemblyInfo {
        val functionUnits = mutableListOf<FunctionUnit>()

        val functionIndexMap = doFunctionNamePass()
        var remainingProgram = program
        for (i in 0 until functionIndexMap.size) {
            val (functionUnit, consumedChars) = FunctionUnitAssembler(
                remainingProgram,
                functionIndexMap
            ).getFunctionUnit()
            functionUnits.add(functionUnit)
            remainingProgram = remainingProgram.substring(consumedChars)
        }

        val functionOffsets = arrayListOf<UShort>()

        //adjust this accordingly if adding a global table, etc
        //it is the offset of the first function
        //currently set to # of funcs *2 because 2 bytes are used in each
        //entry of the function table
        val initialOffset = functionUnits.size * 2
        functionOffsets.add(initialOffset.toUShort())

        for (functionUnit in functionUnits.take(functionUnits.size - 1)) {
            functionOffsets.add((functionOffsets.last() + totalFunctionUnitSize(functionUnit)).toUShort())
        }

        return ProgramAssemblyInfo(functionOffsets.toTypedArray(), functionUnits)
    }

    private fun totalFunctionUnitSize(functionUnit: FunctionUnit): UShort {
        //add 2 for the locals allocation size declaration
        return (functionUnit.byteCode.size + 2).toUShort()
    }
}

class ProgramAssemblyInfo(private val functionOffsets: Array<UShort>, private val functionUnits: List<FunctionUnit>) {
    fun getFullCDeclarations(): String {
        return "{" + getFunctionTableCString() + functionUnits.joinToString(", \n") { getCString(it) } + "\n}"
    }

    private fun getCString(functionUnit: FunctionUnit): String {
        var out = ""
        out += "\n//function: ${functionUnit.functionName}\n//locals allocation size (bytes):\n"
        out += functionUnit.localsAllocationSize.toUByteArray().joinToString("") { getCHexString(it) + ", " }
        out += "\n//bytecode:\n"
        var columnIter = 0
        for (i in 0 until functionUnit.byteCode.size) {
            out += getCHexString(functionUnit.byteCode[i])

            //no comma after last entry
            if (i != functionUnit.byteCode.size - 1) {
                out += ", "
            }

            columnIter++
            if (columnIter == 8) {
                out += "\n"
                columnIter = 0
            }
        }

        return out
    }

    private fun getFunctionTableCString(): String {
        var out = "\n//Number of functions:\n"
        out += functionUnits.size.toUShort().toUByteArray().joinToString("") { getCHexString(it) + ", " }
        out += "\n//function offsets table:\n"
        out += functionOffsets.joinToString("") { it.toUByteArray().joinToString("") { uByte -> getCHexString(uByte) + ", " } + "\n" }

        return out
    }

    private fun getCHexString(byte: UByte): String = "0x" + byte.toString(16).toUpperCase()
}
