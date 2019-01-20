package idealised.DPIA

import idealised.OpenCL.SurfaceLanguage.DSL.reorderWithStridePhrase
import idealised.SurfaceLanguage.DSL._
import idealised.SurfaceLanguage.Types._
import idealised.util.SyntaxChecker
import lift.arithmetic._

class Scatter extends idealised.Tests {

  test("Simple scatter example should generate syntactic valid C code with two one loops") {
    val slideExample = fun(ArrayType(SizeVar("N"), float))(xs => xs :>> mapSeq(fun(x => x)) :>> scatter(reorderWithStridePhrase(128)) )

    val p = idealised.C.ProgramGenerator.makeCode(TypeInference(slideExample, Map()).toPhrase)
    val code = p.code
    SyntaxChecker(code)
    println(code)

    "for".r.findAllIn(code).length shouldBe 1
  }

  test("Simple 2D scatter example should generate syntactic valid C code with two two loops") {
    val slideExample = fun(ArrayType(SizeVar("N"), ArrayType(SizeVar("M"), float)))(xs =>
      xs :>> mapSeq(mapSeq(fun(x => x))) :>> map(scatter(reorderWithStridePhrase(128))) )

    val p = idealised.C.ProgramGenerator.makeCode(TypeInference(slideExample, Map()).toPhrase)
    val code = p.code
    SyntaxChecker(code)
    println(code)

    "for".r.findAllIn(code).length shouldBe 2
  }

}