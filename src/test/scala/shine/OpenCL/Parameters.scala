package shine.OpenCL

import rise.core.DSL._
import rise.core.types._
import util.gen

class Parameters extends test_util.Tests {
  val m = 4 // vector width

  test("Output scalar") {
    gen.OpenCLKernel(fun(float)(vs => vs))
  }

  test("Output vector") {
    gen.OpenCLKernel(fun(VectorType(m, float))(vs => vs))
  }

  test("Output array") {
    gen.OpenCLKernel(nFun(n => fun(ArrayType(n, float))(vs => vs |> mapSeq (fun(x => x)))))
  }
}