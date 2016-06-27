package CommandPatterns

import AccPatterns.TruncAcc
import Core._
import Core.PhraseType._
import Core.OperationalSemantics._
import apart.arithmetic._
import Compiling.SubstituteImplementations
import DSL._
import ExpPatterns.TruncExp

import scala.xml.Elem

case class IterateIAcc(n: ArithExpr,
                       m: ArithExpr,
                       k: ArithExpr,
                       dt: DataType,
                       out: Phrase[AccType],
                       f: Phrase[`(nat)->`[AccType -> (ExpType -> CommandType)]],
                       in: Phrase[ExpType])
  extends IntermediateCommandPattern {

  override def typeCheck(): CommandType = {
    import TypeChecker._
    (TypeChecker(out), TypeChecker(in)) match {
      case (AccType(ArrayType(m_, dt1_)), ExpType(ArrayType(nkm_, dt2_)))
        if m_ == m && nkm_ == (n.pow(k) * m) && dt1_ == dt && dt2_ == dt =>

        f match {
          case NatDependentLambdaPhrase(l, body) =>
            setParamType(body, AccType(ArrayType(l /^ n, dt)))
            setSecondParamType(body, ExpType(ArrayType(l, dt)))
            TypeChecker(body) match {
              case FunctionType(AccType(ArrayType(l_n, dt3_)),
              FunctionType(ExpType(ArrayType(l_, dt4_)), CommandType())) =>
                if (l_n == l /^ n && dt3_ == dt && l_ == l && dt4_ == dt) {
                  CommandType()
                } else {
                  error(s"[$l_n.$dt3_] -> [$l_.$dt4_] -> CommandType",
                        s"[${l /^ n}.$dt] -> [$l.$dt] -> CommandType")
                }
              case ft => error(ft.toString, "FunctionType")
            }
          case _ => error(f.toString, "NatDependentLambdaPhrase")
        }

      case t_ => error(t_.toString, "ArrayType")
    }
  }

  override def eval(s: Store): Store = ???

  override def visitAndRebuild(fun: VisitAndRebuild.fun): Phrase[CommandType] = {
    IterateIAcc(fun(n), fun(m), fun(k), fun(dt),
      VisitAndRebuild(out, fun),
      VisitAndRebuild(f, fun),
      VisitAndRebuild(in, fun))
  }

  override def substituteImpl(env: SubstituteImplementations.Environment): Phrase[CommandType] = {
    // infer the address space from the output
    val identifier = ToOpenCL.acc(out, new ToOpenCL(?, ?))
    val addressSpace = env.addressspace(identifier.name)

    val sEnd = n.pow(k)*m

    val iterateLoop = (start: ArithExpr,
                       end: ArithExpr,
                       buf1: Phrase[VarType],
                       buf2: Phrase[VarType]) => {
      val s = (l: ArithExpr) => n.pow(end - l - start) * m

      end - start match {
        case Cst(x) if x > 2 =>
          // unrolling the last iteration
          dblBufFor(sEnd, dt, addressSpace, buf1, buf2, end - start - 1,
            _Λ_(l => {
              val s_l = s(l)
              val s_l1 = s(l + 1)
              λ(AccType(ArrayType(sEnd, dt))) { o =>
                λ(ExpType(ArrayType(sEnd, dt))) { x =>
                  SubstituteImplementations(
                    f(s_l)(TruncAcc(sEnd, s_l1, dt, o))(TruncExp(sEnd, s_l, dt, x)),
                    env)
                }
              }
            }),
            λ(ExpType(ArrayType(sEnd, dt)))(x =>
              SubstituteImplementations(
                f(s(end - start - 1))(TruncAcc(m, s(end - start), dt, out))(TruncExp(sEnd, s(end - start - 1), dt, x))
                , env))
          )

        case _ =>
          // extra copy to output
          dblBufFor(sEnd, dt, addressSpace, buf1, buf2, end - start,
            _Λ_(l => {
              val s_l = s(l)
              val s_l1 = s(l + 1)
              λ(AccType(ArrayType(sEnd, dt))) { o =>
                λ(ExpType(ArrayType(sEnd, dt))) { x =>
                  SubstituteImplementations(
                    f(s_l)(TruncAcc(sEnd, s_l1, dt, o))(TruncExp(sEnd, s_l, dt, x)),
                    env)
                }
              }
            }),
            λ(ExpType(ArrayType(sEnd, dt)))(x =>
              SubstituteImplementations(MapI(m, dt, dt, out,
                λ(AccType(dt)) { o => λ(ExpType(dt)) { x => o `:=` x } }, x), env))
          )
      }
    }

    val s = (l: ArithExpr) => n.pow(k-l)*m

    k match {
      case Cst(x) if x > 2 =>
        `new`(ArrayType(sEnd, dt), addressSpace, buf1 => {
          `new`(ArrayType(sEnd, dt), addressSpace, buf2 => {
            SubstituteImplementations(
              f(s(0))(TruncAcc(sEnd, s(1), dt, buf1.wr))(TruncExp(sEnd, s(0), dt, in))
              , env) `;`
              iterateLoop(1, k, buf1, buf2)
          })
        })

      case _ =>
        `new`(ArrayType(sEnd, dt), addressSpace, buf1 => {
          `new`(ArrayType(sEnd, dt), addressSpace, buf2 => {
            SubstituteImplementations(MapI(sEnd, dt, dt, buf1.wr,
              λ(AccType(dt)) { o => λ(ExpType(dt)) { x => o `:=` x } }, in), env) `;`
              iterateLoop(0, k, buf1, buf2)
          })
        })
    }
  }

  override def prettyPrint: String = s"(iterateIAcc ${PrettyPrinter(out)} ${PrettyPrinter(f)} ${PrettyPrinter(in)})"

  override def xmlPrinter: Elem =
    <iterateIAcc n={n.toString} m={m.toString} k={k.toString} dt={dt.toString}>
      <output>{Core.xmlPrinter(out)}</output>
      <f>{Core.xmlPrinter(f)}</f>
      <input>{Core.xmlPrinter(in)}</input>
    </iterateIAcc>
}