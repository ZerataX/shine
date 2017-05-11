package idealised.Core

object PrettyPhrasePrinter {

  def apply[T <: PhraseType](p: Phrase[T]): String = {
    p match {
      case app: Apply[a, T] => s"(${apply(app.fun)} ${apply(app.arg)})"

      case app: NatDependentApply[T] => s"(${apply(app.fun)} ${app.arg})"

      case app: TypeDependentApply[T] => s"(${apply(app.fun)} ${app.arg})"

      case p1: Proj1[a, b] => s"${apply(p1.pair)}._1"

      case p2: Proj2[a, b] => s"${apply(p2.pair)}._2"

      case IfThenElse(cond, thenP, elseP) =>
        s"if(${apply(cond)}) { ${apply(thenP)} } else { ${apply(elseP)} }"

      case UnaryOp(op, x) => s"(${op.toString} ${apply(x)})"

      case BinOp(op, lhs, rhs) => s"(${apply(lhs)} ${op.toString} ${apply(rhs)})"

      case Identifier(name, _) => name

      case Lambda(param, body) => s"(λ ${apply(param)}: ${param.t} . ${apply(body)})"

      case NatDependentLambda(param, body) => s"(Λ ${param.name} : nat . ${apply(body)})"

      case TypeDependentLambda(param, body) => s"(Λ ${param.name} : dt . ${apply(body)})"

      case Literal(d, _) => d.toString

      case Pair(fst, snd) => s"(${apply(fst)}, ${apply(snd)})"

      case c: Primitive[_] => c.prettyPrint
    }
  }

}