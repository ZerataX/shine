package shine.cuda.codegen

import arithexpr.arithmetic
import arithexpr.arithmetic._
import shine.C.AST.Decl
import shine._
import shine.C.CodeGeneration.{CodeGenerator => CCodeGen}
import shine.OpenCL.CodeGeneration.{CodeGenerator => OclCodeGen}
import shine.cuda.primitives.imperative._
import shine.DPIA.DSL._
import shine.DPIA.ImperativePrimitives._
import shine.DPIA.Phrases._
import shine.DPIA.Types._
import shine.DPIA._

import scala.collection.{immutable, mutable}

object CodeGenerator {
  def apply(): CodeGenerator =
    new CodeGenerator(
      mutable.ListBuffer[Decl](), immutable.Map[String, arithmetic.Range]())
}

class CodeGenerator(override val decls: CCodeGen.Declarations,
                    override val ranges: CCodeGen.Ranges)
  extends OclCodeGen(decls, ranges) {

  override def updatedRanges(
    key: String,
    value: arithexpr.arithmetic.Range
  ): CodeGenerator =
    new CodeGenerator(decls, ranges.updated(key, value))

  override def cmd(phrase: Phrase[CommType], env: Environment): Stmt = {
    phrase match {
      case f@CudaParFor(n, dt, a, Lambda(i, Lambda(o, p)), _, _, _) =>
        CudaCodeGen.codeGenCudaParFor(f, n, dt, a, i, o, p, env)

      case _: New =>
        throw new Exception(
          "New without address space found in OpenCL program.")

      case SyncThreads() => cuda.ast.Synchronize()

      case _ => super.cmd(phrase, env)
    }
  }

  protected object CudaCodeGen {
    def codeGenCudaParFor(f: CudaParFor,
                            n: Nat,
                            dt: DataType,
                            a: Phrase[AccType],
                            i: Identifier[ExpType],
                            o: Phrase[AccType],
                            p: Phrase[CommType],
                            env: Environment): Stmt = {
      assert(!f.unroll)
      val cI = C.AST.DeclRef(f.name)
      val range = RangeAdd(f.init, n, f.step)
      val updatedGen = updatedRanges(cI.name, range)

      applySubstitutions(n, env.identEnv) |> (n => {

        val init =
          OpenCL.AST.VarDecl(
            cI.name, C.AST.Type.int, AddressSpace.Private,
            init = Some(C.AST.ArithmeticExpr(range.start)))
        val cond =
          C.AST.BinaryExpr(cI, C.AST.BinaryOperator.<, C.AST.ArithmeticExpr(n))
        val increment =
          C.AST.Assignment(cI, C.AST.ArithmeticExpr(NamedVar(cI.name, range) +
            range.step))

        Phrase.substitute(a `@` i, `for` = o, `in` = p) |> (p =>

          env.updatedIdentEnv(i -> cI) |> (env => {

            range.numVals match {
              // iteration count is 0 => skip body; no code to be emitted
              case Cst(0) =>
                C.AST.Comment("iteration count is 0, no loop emitted")
              // iteration count is 1 => no loop
              case Cst(1) =>
                C.AST.Stmts(C.AST.Stmts(
                  C.AST.Comment("iteration count is exactly 1, no loop emitted"),
                  C.AST.DeclStmt(C.AST.VarDecl(
                    cI.name,
                    C.AST.Type.int,
                    init = Some(C.AST.ArithmeticExpr(f.init))))),
                  updatedGen.cmd(p, env))
              /* FIXME?
            case _ if (range.start.min.min == Cst(0) && range.stop == Cst(1)) ||
                      (range.numVals.min == NegInf && range.numVals.max == Cst(1)) =>
              C.AST.Block(collection.Seq(
                C.AST.DeclStmt(init),
                C.AST.IfThenElse(cond, updatedGen.cmd(p, env) , None)
              ))
               */
              // default case
              case _ =>
                C.AST.ForLoop(C.AST.DeclStmt(init), cond, increment,
                  C.AST.Block(immutable.Seq(updatedGen.cmd(p, env))))
            }}))})
    }

  }
}
