package shine.DPIA.FunctionalPrimitives


import shine.DPIA.Compilation.{TranslationContext, TranslationToImperative}
import shine.DPIA.DSL._
import shine.DPIA.Phrases._
import shine.DPIA.Semantics.OperationalSemantics
import shine.DPIA.Semantics.OperationalSemantics._
import shine.DPIA.Types._
import shine.DPIA._

import scala.xml.Elem

abstract class AbstractReduce(n: Nat,
                              dt1: DataType,
                              dt2: DataType,
                              f: Phrase[ExpType ->: ExpType ->: ExpType],
                              init: Phrase[ExpType],
                              array: Phrase[ExpType])
  extends ExpPrimitive {

  def makeReduce: (Nat, DataType, DataType,
    Phrase[ExpType ->: ExpType ->: ExpType], Phrase[ExpType], Phrase[ExpType]) => AbstractReduce

  def makeReduceI(n: Nat,
                  dt1: DataType,
                  dt2: DataType,
                  f: Phrase[ExpType ->: ExpType ->: AccType ->: CommType],
                  init: Phrase[ExpType],
                  array: Phrase[ExpType],
                  out: Phrase[ExpType ->: CommType])
                 (implicit context: TranslationContext): Phrase[CommType]

  override val t: ExpType =
    (n: Nat) ->: (dt1: DataType) ->: (dt2: DataType) ->:
      (f :: t"exp[$dt2, $read] -> exp[$dt1, $read] -> exp[$dt2, $write]") ->:
        (init :: exp"[$dt2, $write]") ->:
          (array :: exp"[$n.$dt1, $read]") ->: exp"[$dt2, $read]"

  override def visitAndRebuild(fun: VisitAndRebuild.Visitor): Phrase[ExpType] = {
    makeReduce(fun.nat(n), fun.data(dt1), fun.data(dt2),
      VisitAndRebuild(f, fun), VisitAndRebuild(init, fun), VisitAndRebuild(array, fun))
  }

  override def eval(s: Store): Data = {
    val fE = OperationalSemantics.eval(s, f)(BinaryFunctionEvaluator)
    val initE = OperationalSemantics.eval(s, init)
    OperationalSemantics.eval(s, array) match {
      case ArrayData(xs) =>
        ArrayData(Vector(xs.fold(initE) {
          (x, y) => OperationalSemantics.eval(s,
            fE(Literal(x))(Literal(y)))
        }))
      case _ => throw new Exception("This should not happen")
    }
  }

  override def prettyPrint: String =
    s"${this.getClass.getSimpleName} (${PrettyPhrasePrinter(f)}) " +
      s"(${PrettyPhrasePrinter(init)}) (${PrettyPhrasePrinter(array)})"


  override def acceptorTranslation(A: Phrase[AccType])
                                  (implicit context: TranslationContext): Phrase[CommType] = {
    import TranslationToImperative._

    con(this)(λ(exp"[$dt2, $read]")(r => acc(r)(A)))
  }

  override def continuationTranslation(C: Phrase[ExpType ->: CommType])
                                      (implicit context: TranslationContext): Phrase[CommType] = {
    import TranslationToImperative._

    con(array)(λ(exp"[$n.$dt1, $read]")(X =>
      con(init)(λ(exp"[$dt2, $read]")(Y =>
        makeReduceI(n, dt1, dt2,
          λ(exp"[$dt2, $read]")(x => λ(exp"[$dt1, $read]")(y => λ(acc"[$dt2]")(o => acc( f(x)(y) )( o ) ))),
          Y, X, C)))))
  }

  override def xmlPrinter: Elem =
    <reduce n={ToString(n)} dt1={ToString(dt1)} dt2={ToString(dt2)}>
      <f type={ToString(ExpType(dt1, read) ->: ExpType(dt2, read) ->: ExpType(dt2, write))}>
        {Phrases.xmlPrinter(f)}
      </f>
      <init type={ToString(ExpType(dt2, write))}>
        {Phrases.xmlPrinter(init)}
      </init>
      <input type={ToString(ExpType(ArrayType(n, dt1), read))}>
        {Phrases.xmlPrinter(array)}
      </input>
    </reduce>.copy(label = {
      val name = this.getClass.getSimpleName
      Character.toLowerCase(name.charAt(0)) + name.substring(1)
    })
}
