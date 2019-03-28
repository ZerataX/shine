package idealised.SurfaceLanguage.Primitives

import idealised.SurfaceLanguage.Types._
import idealised.SurfaceLanguage.{PrimitiveExpr, _}

final case class AsIndex(n: Nat, e: Expr, override val t: Option[DataType] = None)
  extends PrimitiveExpr {

  override def inferType(subs: TypeInference.SubstitutionMap): AsIndex = {
    import TypeInference._
    TypeInference(e, subs) |> (e =>
      e.t match {
        //TODO lift e to n: Nat and use n.max to create IndexType or at least to check bounds?
        case Some(NatType) => AsIndex(n, e, Some(IndexType(n)))
        case x => error(expr = s"AsIndex($e)", found = s"`${x.toString}'", expected = "expr[nat]")
      }
    )
  }

  override def children: Seq[Any] = Seq(n, e, t)

  override def rebuild: Seq[Any] => Expr = {
    case Seq(n: Nat, e: Expr, t: Option[DataType]) => AsIndex(n, e, t)
  }
}