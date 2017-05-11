package idealised.Core

import java.io.{File, PrintWriter}

import scala.xml._

object ToString {
  def apply(a: Any): String = {
    if (a == null) { "null" } else { a.toString }
  }
}

object xmlPrinter {

  def toFile[T <: PhraseType](filename: String, p: Phrase[T]): Unit = {
    val pw = new PrintWriter(new File(filename))
    try pw.write(asString(p)) finally pw.close()
  }

  def asString[T <: PhraseType](p: Phrase[T]): String = {
    <expression p={PrettyPhrasePrinter(p)}>{apply(p)}</expression>.toString()
  }

  def apply[T <: PhraseType](p: Phrase[T]): xml.Elem = {
    val elem = p match {
      case app: Apply[a, T] =>
        <apply>
          <fun type={ToString(app.fun.t)}>{apply(app.fun)}</fun>
          <arg type={ToString(app.arg.t)}>{apply(app.arg)}</arg>
        </apply>

      case app: NatDependentApply[T] =>
        <natApply>
          <fun type={ToString(app.fun.t)}>{apply(app.fun)}</fun>
          <arg type="Nat">{app.arg}</arg>
        </natApply>

      case app: TypeDependentApply[T] =>
        <typeApply>
          <fun type={ToString(app.fun.t)}>{apply(app.fun)}</fun>
          <arg type="Nat">{app.arg}</arg>
        </typeApply>

      case p1: Proj1[a, b] =>
        <π1>{apply(p1.pair)}</π1>

      case p2: Proj2[a, b] =>
        <π2>{apply(p2.pair)}</π2>

      case IfThenElse(cond, thenP, elseP) =>
        <ifThenElse>
          <if type={ToString(cond.t)}>{apply(cond)}</if>
          <then type={ToString(thenP.t)}>{apply(thenP)}</then>
          <else type={ToString(elseP.t)}>{apply(elseP)}</else>
        </ifThenElse>

      case UnaryOp(op, x) =>
        <unary op={op.toString}>{apply(x)}</unary>

      case BinOp(op, lhs, rhs) =>
        <binary op={op.toString}>
          <lhs type={ToString(lhs.t)}>{apply(lhs)}</lhs>
          <rhs type={ToString(rhs.t)}>{apply(rhs)}</rhs>
        </binary>

      case Identifier(name, _) =>
        <identifier name={name} />

      case Lambda(param, body) =>
        <λ param={param.name}>
          {apply(body)}
        </λ>

      case NatDependentLambda(param, body) =>
        <Λ param={param.name + " : nat"}>
          {apply(body)}
        </Λ>

      case TypeDependentLambda(param, body) =>
        <Λ param={param.name + " : dt"}>
          {apply(body)}
        </Λ>

      case Literal(d, _) => <lit>{d}</lit>

      case Pair(fst, snd) =>
        <pair>
          <fst type={ToString(fst.t)}>{apply(fst)}</fst>
          <snd type={ToString(snd.t)}>{apply(snd)}</snd>
        </pair>

      case c: Primitive[_] => c.xmlPrinter

    }
    elem.copy(attributes =
      append(elem.attributes,
        Attribute("type", Text(ToString(p.t)), Null)))
  }

  def append(head: MetaData, node: MetaData): MetaData = {
    head match {
      case Null => node
      case _ => head.next match {
        case Null => head.copy(next = node)
        case _ => head.copy(next = append(head.next, node))
      }
    }
  }

}
