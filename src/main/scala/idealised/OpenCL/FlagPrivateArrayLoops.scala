package idealised.OpenCL

import idealised.DPIA._
import idealised.DPIA.Phrases._
import idealised.DPIA.Types._
import idealised.DPIA.ImperativePrimitives.{For, ForNat, IdxAcc}
import idealised.DPIA.FunctionalPrimitives.Idx
import idealised.DPIA.Semantics.OperationalSemantics.ArrayData
import idealised.OpenCL.ImperativePrimitives.OpenCLNew

import scala.collection.mutable

object FlagPrivateArrayLoops {
  def apply(p: Phrase[CommType]): Phrase[CommType] = {
    val vs = varsToEliminate(p)
    val p2 = eliminateLoopVars(p, vs)
    if (vs.nonEmpty) {
      println(s"WARNING: could not eliminate variables $vs")
    }
    p2
  }

  private def varsToEliminate(p: Phrase[CommType]): mutable.Set[String] = {
    var eliminateVars = mutable.Set[String]()

    case class Visitor(privateVars: Set[Identifier[_]],
                       indexVars: Set[String])
      extends VisitAndRebuild.Visitor
    {
      override def phrase[T <: PhraseType](p: Phrase[T]): Result[Phrase[T]] = p match {
        case OpenCLNew(AddressSpace.Private, _, Lambda(i: Identifier[_], _)) =>
          Continue(p, this.copy(privateVars = privateVars + i))
        case Idx(_, _, i, _) =>
          Continue(p, this.copy(indexVars = indexVars ++ collectVars(i)))
        case IdxAcc(_, _, i, _) =>
          Continue(p, this.copy(indexVars = indexVars ++ collectVars(i)))
        case i: Identifier[_] if privateVars(i) =>
          eliminateVars ++= indexVars
          Stop(p)
        case Literal(ArrayData(_)) =>
          eliminateVars ++= indexVars
          Stop(p)
        case _ =>
          Continue(p, this)
      }
    }

    VisitAndRebuild(p, Visitor(Set(), Set()))
    eliminateVars
  }

  private def eliminateLoopVars(p: Phrase[CommType],
                                eliminateVars: mutable.Set[String]): Phrase[CommType] = {
    VisitAndRebuild(p, new VisitAndRebuild.Visitor {
      override def phrase[T <: PhraseType](p: Phrase[T]): Result[Phrase[T]] = p match {
        case For(n, body @ Lambda(i: Identifier[_], _), _) if eliminateVars(i.name) =>
          eliminateVars -= i.name
          Continue(For(n, body, unroll = true), this)
        case ForNat(n, body @ DepLambda(i: NatIdentifier, _), _) if eliminateVars(i.name) =>
          eliminateVars -= i.name
          Continue(ForNat(n, body, unroll = true), this)
        case _ =>
          Continue(p, this)
      }
    })
  }

  private def collectVars[T <: PhraseType](p: Phrase[T]): Set[String] = {
    var vars = mutable.Set[String]()

    VisitAndRebuild(p, new VisitAndRebuild.Visitor {
      override def nat[N <: Nat](n: N): N = {
        vars ++= n.varList.map(_.name)
        n
      }

      override def phrase[T2 <: PhraseType](p: Phrase[T2]): Result[Phrase[T2]] = {
        p match {
          case i: Identifier[_] => vars += i.name
          case _ =>
        }
        Continue(p, this)
      }
    })

    vars.toSet
  }
}
