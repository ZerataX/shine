package idealised.SurfaceLanguage.Primitives


import idealised.SurfaceLanguage.Types.TypeInference.SubstitutionMap
import idealised.SurfaceLanguage.Types._
import idealised.SurfaceLanguage._

abstract class AbstractReduce(val f: Expr, val init: Expr, val array: Expr, override val t: Option[DataType])
  extends PrimitiveExpr {

  def makeReduce: (Expr, Expr, Expr, Option[DataType]) => AbstractReduce

  override def inferType(subs: SubstitutionMap): AbstractReduce = {
    import TypeInference._
    TypeInference(array, subs) |> (array =>
      TypeInference(init , subs) |> (init =>
        (init.t, array.t) match {
          case (Some(dt2: DataType), Some(ArrayType(_, dt1))) =>
            setParamsAndInferTypes(f, dt1, dt2, subs) |> (f =>
              f.t match {
                case Some(FunctionType(t1, FunctionType(t2, t3))) =>
                  if (dt1 == t1 && dt2 == t2 && dt2 == t3) {
                    makeReduce(f, init, array, Some(dt2))
                  } else {
                    error(this.toString,
                      dt1.toString + ", " + t1.toString + " as well as " +
                        dt2.toString + ", " + t2.toString + " and " + t3.toString,
                      expected = "them to match")
                  }
                case x => error(expr = s"${this.getClass.getSimpleName}($f, $init, $array)",
                  found = s"`${x.toString}'", expected = "dt1 -> (dt2 -> dt3)")
              })
          case x => error(expr = s"${this.getClass.getSimpleName}($f, $init, $array)",
            found = s"`${x.toString}'", expected = "(dt1, n.dt2)")
        }))
  }

  override def children: Seq[Any] = Seq(f, init, array, t)

  override def rebuild: Seq[Any] => Expr = {
    case Seq(f: Expr, init: Expr, array: Expr, t: Option[DataType]@unchecked) =>
      makeReduce(f, init, array, t)
  }
}
