package LowLevelCombinators

import Core.OperationalSemantics._
import Core._
import apart.arithmetic.ArithExpr

import scala.xml.Elem

case class TruncAcc(n: ArithExpr,
                    m: ArithExpr,
                    dt: DataType,
                    array: Phrase[AccType])
  extends LowLevelAccCombinator {

  override lazy val `type` = acc"[$m.$dt]"

  override def typeCheck(): Unit = {
    import TypeChecker._
    array checkType acc"[$n.$dt]"
  }

  override def eval(s: Store): AccIdentifier = ???

  override def visitAndRebuild(fun: VisitAndRebuild.fun): Phrase[AccType] = {
    TruncAcc(fun(n), fun(m), fun(dt), VisitAndRebuild(array, fun))
  }

  override def prettyPrint: String = s"(truncAcc ${PrettyPrinter(array)})"

  override def xmlPrinter: Elem =
    <truncAcc n={ToString(n)} m={ToString(m)} dt={ToString(dt)}>
      {Core.xmlPrinter(array)}
    </truncAcc>
}