package elevate.lift.strategies

import elevate.core.{Failure, Strategy}
import lift.core.{Apply, Lambda}
import elevate.lift.rules.algorithmic._
import elevate.core.strategies.traversal._
import elevate.lift.strategies.algorithmic._
import elevate.lift.strategies.normalForm._

object traversal {

  def body: Strategy => Strategy =
    s => {
      case Lambda(x, f) => s(f).mapSuccess(Lambda(x, _))
      case _ => Failure(s)
    }

  def function: Strategy => Strategy =
    s => {
      case Apply(f, e) => s(f).mapSuccess(Apply(_, e))
      case _ => Failure(s)
    }

  def argument: Strategy => Strategy =
    s => {
      case Apply(f, e) => s(e).mapSuccess(Apply(f, _))
      case x => Failure(s)
    }

  // applying a strategy to an expression applied to a lift `map`. Example:
  // ((map λe14. (transpose ((map (map e12)) e14))) e13) // input expr
  //  (map λe14. (transpose ((map (map e12)) e14)))      // result of `function`
  //       λe14. (transpose ((map (map e12)) e14))       // result of `argument`
  //             (transpose ((map (map e12)) e14))       // result of 'body' -> here we can apply s
  def fmap: Strategy => Strategy = s => function(argument(body(s)))

  // fmap applied for expressions in rewrite normal form:
  // fuse -> fmap -> fissio
  def fmapRNF: Strategy => Strategy =
    s =>
      mapFusion `;` reductionNormalForm `;`
      fmap(s) `;` reductionNormalForm `;`
      one(mapFullFission)

  // applying a strategy to an expression nested in one or multiple lift `map`s
  def mapped: Strategy => Strategy =
    s => s <+ (e => fmapRNF(mapped(s))(e))
}
