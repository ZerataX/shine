package idealised.DPIA.FunctionalPrimitives

import idealised.DPIA.Compilation.RewriteToImperative
import idealised.DPIA._
import idealised.DPIA.DSL._
import idealised.DPIA.Types._
import idealised.DPIA.Phrases._
import idealised.DPIA.Semantics.OperationalSemantics.{Data, Store}

final case class Cycle(n: Nat,
                       m: Nat,
                       dt: DataType,
                       input: Phrase[ExpType])
  extends ExpPrimitive
{
  override val `type`: ExpType =
    (n: Nat) -> (m: Nat) -> (dt: DataType) -> (input :: exp"[$m.$dt]") -> exp"[$n.$dt]"

  override def eval(s: Store): Data = ???

  override def visitAndRebuild(v: VisitAndRebuild.Visitor): Phrase[ExpType] =
    Cycle(v(n), v(m), v(dt), VisitAndRebuild(input, v))

  override def acceptorTranslation(A: Phrase[AccType]): Phrase[CommandType] = ???

  override def continuationTranslation(C: Phrase[->[ExpType, CommandType]]): Phrase[CommandType] = {
    import RewriteToImperative._
    con(input)(fun(exp"[$m.$dt")(x => C(Cycle(n, m, dt, x))))
  }

  override def xmlPrinter: xml.Elem =
    <cycle n={ToString(n)} m={ToString(m)} dt={ToString(dt)}>
      {Phrases.xmlPrinter(input)}
    </cycle>

  override def prettyPrint: String = s"(cycle $input)"
}
