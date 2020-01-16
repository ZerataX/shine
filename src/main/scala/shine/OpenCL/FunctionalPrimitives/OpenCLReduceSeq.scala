package shine.OpenCL.FunctionalPrimitives

import shine.DPIA.Compilation._
import shine.DPIA.DSL._
import shine.DPIA.Phrases._
import shine.DPIA.Semantics.OperationalSemantics._
import shine.DPIA.Types._
import shine.DPIA._
import shine.OpenCL.IntermediatePrimitives.OpenCLReduceSeqI

import scala.xml.Elem

final case class OpenCLReduceSeq(n: Nat,
                                 initAddrSpace: shine.DPIA.Types.AddressSpace,
                                 dt1: DataType,
                                 dt2: DataType,
                                 f: Phrase[ExpType ->: ExpType ->: ExpType],
                                 init: Phrase[ExpType],
                                 array: Phrase[ExpType],
                                 unroll: Boolean)
  extends ExpPrimitive
{
  override val t: ExpType =
    (n: Nat) ->: (dt1: DataType) ->: (dt2: DataType) ->:
      (f :: t"exp[$dt2, $read] -> exp[$dt1, $read] -> exp[$dt2, $write]") ->:
        (init :: exp"[$dt2, $write]") ->: (initAddrSpace : AddressSpace) ->:
          (array :: exp"[$n.$dt1, $read]") ->: exp"[$dt2, $read]"

  override def visitAndRebuild(fun: VisitAndRebuild.Visitor): Phrase[ExpType] = {
    OpenCLReduceSeq(fun.nat(n), fun.addressSpace(initAddrSpace), fun.data(dt1), fun.data(dt2),
      VisitAndRebuild(f, fun), VisitAndRebuild(init, fun), VisitAndRebuild(array, fun), unroll)
  }

  override def eval(s: Store): Data = ???

  override def prettyPrint: String =
    s"${this.getClass.getSimpleName} (${initAddrSpace}) (${PrettyPhrasePrinter(f)}) " +
      s"(${PrettyPhrasePrinter(init)}) (${PrettyPhrasePrinter(array)})"

  override def acceptorTranslation(A: Phrase[AccType])
                                  (implicit context: TranslationContext): Phrase[CommType] = {
    import TranslationToImperative._

    //TODO This is wrong!
    println("WARNING: opencl reduce seq acceptor translation is deprecated, implicit copies might happen")
    con(array)(λ(exp"[$n.$dt1, $read]")(X =>
      OpenCLReduceSeqI(n, initAddrSpace, dt1, dt2,
        λ(exp"[$dt2, $read]")(x => λ(exp"[$dt1, $read]")(y => λ(acc"[$dt2]")(o => acc( f(x)(y) )( o )))),
        init, X, λ(exp"[$dt2, $read]")(r => acc(r)(A)), unroll)(context)))
  }

  override def continuationTranslation(C: Phrase[ExpType ->: CommType])
                                      (implicit context: TranslationContext): Phrase[CommType] = {
    import TranslationToImperative._

    //TODO same for ReduceSeq/AbstractReduce
    con(array)(λ(exp"[$n.$dt1, $read]")(X =>
      OpenCLReduceSeqI(n, initAddrSpace, dt1, dt2,
        λ(exp"[$dt2, $read]")(x => λ(exp"[$dt1, $read]")(y => λ(acc"[$dt2]")(o => acc( f(x)(y) )( o )))),
        init, X, C, unroll)(context)))
  }

  override def xmlPrinter: Elem =
    <reduce n={ToString(n)} addrSpace={ToString(initAddrSpace)} dt1={ToString(dt1)} dt2={ToString(dt2)}>
      <f type={ToString(ExpType(dt1, read) ->: (ExpType(dt2, read) ->: ExpType(dt2, write)))}>
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
