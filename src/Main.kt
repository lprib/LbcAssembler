fun main() {
    val program = object : Any() {}.javaClass.getResource("program.txt").readText()
    val a = FunctionUnitAssembler(program, mapOf())
    val f = a.getFunctionUnit()
    val x = ProgramAssembler(program).getAssemblyInfo()
    println(x.getFullCDeclarations())
}

