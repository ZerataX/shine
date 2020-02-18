package shine.cuda

import arithexpr.arithmetic.{ArithExpr, BigSum, Cst}
import yacx.{ByteArg, DoubleArg, FloatArg, IntArg, KernelArg}
// import opencl.executor.{Executor, GlobalArg, KernelArg, LocalArg, ValueArg}
import shine.C.AST.{Node, ParamDecl}
import shine.DPIA.Phrases.Identifier
import shine.DPIA.Types.{AccType, AddressSpaceIdentifier, ArrayType, DataType, DataTypeIdentifier, DepArrayType, ExpType, IndexType, NatToDataApply, NatToDataIdentifier, NatToDataLambda, PairType, ScalarType, VectorType, int}
import shine.DPIA.{Nat, NatIdentifier, VarType}
import shine.{C, OpenCL}
import shine.OpenCL.{AddressSpace, FunctionHelper, GlobalSize, HList, LocalSize, NDRange, get_global_size, get_local_size, get_num_groups}
import util.{Time, TimeSpan}
import yacx.Program

import scala.collection.Seq
import scala.collection.immutable.List
import scala.util.{Failure, Success, Try}

//noinspection ScalaDocParserErrorInspection
case class Kernel(decls: Seq[C.AST.Decl],
                  kernel: OpenCL.AST.KernelDecl,
                  outputParam: Identifier[AccType],
                  inputParams: Seq[Identifier[ExpType]],
                  intermediateParams: Seq[Identifier[VarType]],
                  printer: Node => String
                 ) {

  def code: String = decls.map(printer(_)).mkString("\n") +
    "\n\n" +
    printer(kernel)

  /** This method will return a Scala function which executed the kernel via cuda and returns its
  // result and the time it took to execute the kernel.
  //
  // A type annotation `F` has to be provided which specifies the type of the Scala function.
  // The following syntax is used for this (which implementation can be found in
  // cuda/Types.scala):
  //
  // <kernel>.as[ScalaFunction `(` <Arg1Type> `,` <Arg2Type> `,` <...> `)=>` <ReturnType> ]
  //
  // where all text in < > is to be replaced with the appropriate Scala terms.
  // An example for the dot product which takes two arrays of floats and returns an array of float
  // would be:
  //
  // dotKernel.as[ScalaFunction `(` Array[Float] `,` Array[Float] `)=>` Array[Float]]

    NB: If the kernel takes a single argument, then the invocation must use this syntax
    (Example with a kernel of type Array[Float] => Array[Float]

    val kernelF = kernel.as[ScalaFunction`(`Array[Float]`)=>`Array[Float]]
    val (result, time) = kernelF(xs `;`)
    */
  def as[F <: FunctionHelper](localSize: LocalSize, globalSize: GlobalSize)
                             (implicit ev: F#T <:< HList): F#T => (F#R, TimeSpan[Time.ms]) = {
    hArgs: F#T => {
      val args: List[Any] = hArgs.toList
      assert(inputParams.length == args.length)

      //Match up corresponding dpia parameter/intermediate parameter/scala argument (when present) in a covenience
      //structure
      val arguments = constructArguments(inputParams zip args, intermediateParams, this.kernel.params.tail)

      //First, we want to find all the parameter mappings
      val numGroups: NDRange = (
        globalSize.size.x /^ localSize.size.x,
        globalSize.size.y /^ localSize.size.y,
        globalSize.size.z /^ localSize.size.z)
      val sizeVarMapping = collectSizeVars(arguments, Map(
        get_num_groups(0) -> numGroups.x,
        get_num_groups(1) -> numGroups.y,
        get_num_groups(2) -> numGroups.z,
        get_local_size(0) -> localSize.size.x,
        get_local_size(1) -> localSize.size.y,
        get_local_size(2) -> localSize.size.z,
        get_global_size(0) -> globalSize.size.x,
        get_global_size(1) -> globalSize.size.y,
        get_global_size(2) -> globalSize.size.z
      ))

      val (outputArg, inputArgs) = createKernelArgs(arguments, sizeVarMapping)
      val kernelArgs = outputArg :: inputArgs

      val kernelJNI = Program.create(code, "KERNEL").compile()

      List(localSize match {
        case LocalSize(x) if !x.isEvaluable => Some(s"OpenCL local size is not evaluable (currently set to $x)")
        case _ => None
      }, globalSize match {
        case GlobalSize(x) if !x.isEvaluable => Some(s"OpenCL local size is not evaluable (currently set to $x)")
        case _ => None
      }).filter(_.isDefined).map(_.get) match {
        case Nil =>
        case problems =>
          val errorMessage = "Cannot run kernel:\n" ++ problems.reduce(_ ++ _)
          throw new Exception(errorMessage)
      }

      val runtime = kernelJNI.launch(
        ArithExpr.substitute(localSize.size.x, sizeVarMapping).eval,
        ArithExpr.substitute(localSize.size.y, sizeVarMapping).eval,
        ArithExpr.substitute(localSize.size.z, sizeVarMapping).eval,
        ArithExpr.substitute(globalSize.size.x, sizeVarMapping).eval,
        ArithExpr.substitute(globalSize.size.y, sizeVarMapping).eval,
        ArithExpr.substitute(globalSize.size.z, sizeVarMapping).eval,
        kernelArgs.toArray:_*
      )

      val output = castToOutputType[F#R](outputParam.`type`.dataType, outputArg)

      (output, TimeSpan.inMilliseconds(runtime.getTotal.asInstanceOf[Double]))
    }
  }

  /**
    * A helper class to group together the various bits of related information that make up a parameter
    * @param identifier The identifier representing the parameter in the dpia source
    * @param parameter The OpenCL parameter as appearing in the generated kernel
    * @param argValue For non-intermediate input parameters, this carries the scala value to pass in the executor
    */
  private case class Argument(identifier: Identifier[ExpType], parameter: ParamDecl, argValue: Option[Any])

  private def constructArguments(inputs: Seq[(Identifier[ExpType], Any)],
                                 intermediateParams: Seq[Identifier[VarType]],
                                 oclParams: Seq[ParamDecl]): List[Argument] = {
    // For each input ...
    inputs.headOption match {
      // ... if we have no more, we look at the intermediates ...
      case None => intermediateParams.headOption match {
        // ... if we have no more, we should also be out of ParamDecls
        case None =>
          assert(oclParams.isEmpty)
          Nil
        case Some(param) =>
          // We must have an OpenCl parameter
          assert(oclParams.nonEmpty, "Not enough opencl parameters")
          Argument(Identifier(param.name, param.t.t1), oclParams.head, None) ::
            constructArguments(inputs, intermediateParams.tail, oclParams.tail)
      }
      case Some((param, arg)) =>
        // We must have an OpenCl parameter
        assert(oclParams.nonEmpty, "Not enough opencl parameters")
        Argument(param, oclParams.head, Some(arg)) :: constructArguments(inputs.tail, intermediateParams, oclParams.tail)
    }
  }

  /**
    * From the dpia paramters and the scala arguments, returns a Map of identifiers to sizes for "size vars",
    * the output kernel argument, and all the input kernel arguments
    */
  private def createKernelArgs(arguments: Seq[Argument],
                               sizeVariables: Map[Nat, Nat]): (KernelArg, List[KernelArg]) = {
    //Now generate the input kernel args
    println(s"Create input arguments")
    val kernelArguments = createInputKernelArgs(arguments, sizeVariables)
    //Finally, create the output
    println(s"Create output argument")
    val kernelOutput = createOutputKernelArg(sizeVariables)

    (kernelOutput, kernelArguments)
  }

  /**
    * Iterates through all the input arguments, collecting the values of all int-typed input parameters, which may appear
    * in the sizes of the other arguments.
    */
  private def collectSizeVars(arguments: List[Argument], sizeVariables: Map[Nat, Nat]): Map[Nat, Nat] = {
    def recordSizeVariable(sizeVariables:Map[Nat, Nat], arg:Argument) = {
      arg.identifier.t match {
        case ExpType(shine.DPIA.Types.int, read) =>
          arg.argValue match {
            case Some(i:Int) => sizeVariables + ((NatIdentifier(arg.identifier.name), Cst(i)))
            case Some(num) =>
              throw new Exception(s"Int value for kernel argument ${arg.identifier.name} expected but $num (of type ${num.getClass.getName} found")
            case None =>
              throw new Exception("Int kernel parameter needs a value")
          }
        case _ => sizeVariables
      }
    }

    arguments.foldLeft(sizeVariables)(recordSizeVariable)
  }

  private def createLocalArg(sizeInByte: Long): KernelArg = {
    println(s"Allocated local argument with $sizeInByte bytes")
    ByteArg.create(sizeInByte)
  }

  private def createGlobalArg(sizeInByte: Long): KernelArg = {
    println(s"Allocated global argument with $sizeInByte bytes")
    ByteArg.createOutput(sizeInByte)
  }

  private def createGlobalArg(array: Array[Float]): FloatArg = {
    println(s"Allocated global argument with ${array.length * 4} bytes")
    FloatArg.create(array:_*)
  }

  private def createGlobalArg(array: Array[Int]): IntArg = {
    println(s"Allocated global argument with ${array.length * 4} bytes")
    IntArg.create(array:_*)
  }

  private def createGlobalArg(array: Array[Double]): DoubleArg = {
    println(s"Allocated global argument with ${array.length * 8} bytes")
    DoubleArg.create(array:_*)
  }

  private def createValueArg(value: Float): KernelArg = {
    println(s"Allocated value argument with 4 bytes")
    FloatArg.createValue(value)
  }

  private def createValueArg(value: Int): KernelArg = {
    println(s"Allocated value argument with 4 bytes")
    IntArg.createValue(value)
  }

  private def createValueArg(value: Double): KernelArg = {
    println(s"Allocated value argument with 8 bytes")
    DoubleArg.createValue(value)
  }

  /**
    * Generates the input kernel args
    * @param arguments
    * @param sizeVariables
    * @return
    */
  private def createInputKernelArgs(arguments: Seq[Argument], sizeVariables: Map[Nat, Nat]):List[KernelArg] = {

    //Helper for the creation of intermdiate arguments
    def createIntermediateArg(arg: Argument, sizeVariables: Map[Nat, Nat]): yacx.KernelArg = {
      //Get the size of bytes, potentially with free variables
      val rawSize = sizeInByte(arg.identifier.t.dataType)
      //Try to substitue away all the free variables
      val cleanSize = ArithExpr.substitute(rawSize.value, sizeVariables)
      Try(cleanSize.evalLong) match {
        case Success(actualSize) =>
          arg.parameter.t match {
            case OpenCL.AST.PointerType(a, _, _) => a match {
              case AddressSpace.Private => throw new Exception ("'Private memory' is an invalid memory for opencl parameter")
              case AddressSpace.Local => createLocalArg(actualSize)
              case AddressSpace.Global =>  createGlobalArg(actualSize)
              case AddressSpace.Constant => ???
              case AddressSpaceIdentifier(_) => throw new Exception ("This shouldn't happen")
            }
            case _ => throw new Exception ("This shouldn't happen")
          }
        case Failure(_) => throw new Exception(s"Could not evaluate $cleanSize")
      }
    }

    arguments match {
      case Nil => Nil
      case arg :: remainingArgs =>
        val kernelArg = arg.argValue match {
          //We have a scala value - this is an input argument
          case Some(scalaValue) => createInputArgFromScalaValue(scalaValue)
          //No scala value - this is an intermediate argument
          case None => createIntermediateArg(arg, sizeVariables)
        }
        kernelArg::createInputKernelArgs(remainingArgs, sizeVariables)
    }
  }

  private def createOutputKernelArg(sizeVariables:Map[Nat,Nat]):yacx.KernelArg = {
    val rawSize = sizeInByte(this.outputParam.t.dataType).value
    val cleanSize = ArithExpr.substitute(rawSize, sizeVariables)
    Try(cleanSize.evalLong) match {
      case Success(actualSize) => createGlobalArg(actualSize)
      case Failure(_) => throw new Exception(s"Could not evaluate $cleanSize")
    }
  }

  private def createInputArgFromScalaValue(arg: Any): yacx.KernelArg = {
    arg match {
      case  f: Float => createValueArg(f)
      case af: Array[Float] => createGlobalArg(af)
      case af: Array[Array[Float]] => createGlobalArg(af.flatten)
      case af: Array[Array[Array[Float]]] => createGlobalArg(af.flatten.flatten)
      case af: Array[Array[Array[Array[Float]]]] => createGlobalArg(af.flatten.flatten.flatten)

      case  i: Int => createValueArg(i)
      case ai: Array[Int] => createGlobalArg(ai)
      case ai: Array[Array[Int]] => createGlobalArg(ai.flatten)
      case ai: Array[Array[Array[Int]]] => createGlobalArg(ai.flatten.flatten)
      case ai: Array[Array[Array[Array[Int]]]] => createGlobalArg(ai.flatten.flatten.flatten)

      case  d: Double => createValueArg(d)
      case ad: Array[Double] => createGlobalArg(ad)
      case ad: Array[Array[Double]] => createGlobalArg(ad.flatten)
      case ad: Array[Array[Array[Double]]] => createGlobalArg(ad.flatten.flatten)
      case ad: Array[Array[Array[Array[Double]]]] => createGlobalArg(ad.flatten.flatten.flatten)

      case p: Array[(_, _)] => p.head match {
        case (_: Int, _: Float) =>
          IntArg.create(flattenToArrayOfInts(p.asInstanceOf[Array[(Int, Float)]]):_*)
        case _ => ???
      }
      case pp: Array[Array[(_, _)]] => pp.head.head match {
        case (_: Int, _: Float) =>
          IntArg.create(pp.flatMap(a => flattenToArrayOfInts(a.asInstanceOf[Array[(Int, Float)]])):_*)
        case _ => ???
      }

      case _ => throw new IllegalArgumentException("Kernel argument is of unsupported type: " +
        arg.getClass.getName)
    }
  }

  private def flattenToArrayOfInts(a: Array[(Int, Float)]): Array[Int] = {
    a.flatMap{ case (x,y) => Iterable(x, java.lang.Float.floatToIntBits(y)) }
  }

  private def castToOutputType[R](dt: DataType, output: KernelArg): R = {
    assert(dt.isInstanceOf[ArrayType] || dt.isInstanceOf[DepArrayType])
    (getOutputType(dt) match {
      case shine.DPIA.Types.int => output.asIntArray()
      case shine.DPIA.Types.f32 => output.asFloatArray()
      case shine.DPIA.Types.f64 => output.asDoubleArray()
      case _ => throw new IllegalArgumentException("Return type of the given lambda expression " +
        "not supported: " + dt.toString)
    }).asInstanceOf[R]
  }

  private def getOutputType(dt: DataType): DataType = dt match {
    case _: ScalarType => dt
    case _: IndexType => int
    case _: DataTypeIdentifier => dt
    case VectorType(_, elem) => elem
    case PairType(fst, snd) =>
      val fstO = getOutputType(fst)
      val sndO = getOutputType(snd)
      if (fstO != sndO) {
        throw new IllegalArgumentException("no supported output type " +
          s"for heterogeneous pair: ${dt}")
      }
      fstO
    case ArrayType(_, elemType) => getOutputType(elemType)
    case DepArrayType(_, NatToDataLambda(_, elemType)) =>
      getOutputType(elemType)
    case DepArrayType(_, _) | _: NatToDataApply =>
      throw new Exception("This should not happen")
  }

  private def sizeInByte(dt: DataType): SizeInByte = dt match {
    case s: ScalarType => s match {
      case shine.DPIA.Types.bool => SizeInByte(1)
      case shine.DPIA.Types.int | shine.DPIA.Types.NatType => SizeInByte(4)
      case shine.DPIA.Types.u8 | shine.DPIA.Types.i8 =>
        SizeInByte(1)
      case shine.DPIA.Types.u16 | shine.DPIA.Types.i16 | shine.DPIA.Types.f16 =>
        SizeInByte(2)
      case shine.DPIA.Types.u32 | shine.DPIA.Types.i32 | shine.DPIA.Types.f32 =>
        SizeInByte(4)
      case shine.DPIA.Types.u64 | shine.DPIA.Types.i64 | shine.DPIA.Types.f64 =>
        SizeInByte(8)
    }
    case _: IndexType => SizeInByte(4) // == sizeof(int)
    case v: VectorType => sizeInByte(v.elemType) * v.size
    case r: PairType => sizeInByte(r.fst) + sizeInByte(r.snd)
    case a: ArrayType => sizeInByte(a.elemType) * a.size
    case a: DepArrayType =>
      a.elemFType match {
        case NatToDataLambda(x, body) =>
          SizeInByte(BigSum(Cst(0), a.size - 1, `for`=x, in=sizeInByte(body).value))
        case _: NatToDataIdentifier =>
          throw new Exception("This should not happen")
      }
    case _: NatToDataApply =>  throw new Exception("This should not happen")
    case _: DataTypeIdentifier => throw new Exception("This should not happen")
  }

  case class SizeInByte(value: Nat) {
    def *(rhs: Nat) = SizeInByte(value * rhs)
    def +(rhs: SizeInByte) = SizeInByte(value + rhs.value)

    override def toString = s"$value bytes"
  }
}

sealed case class KernelWithSizes(kernel: Kernel,
                                  localSize: LocalSize,
                                  globalSize: GlobalSize) {
  def as[F <: FunctionHelper](implicit ev: F#T <:< HList): F#T => (F#R, TimeSpan[Time.ms]) =
    kernel.as[F](localSize, globalSize)

  def code: String= kernel.code
}

sealed case class KernelNoSizes(kernel: Kernel) {
  //noinspection TypeAnnotation
  def as[F <: FunctionHelper](implicit ev: F#T <:< HList) = new {
    def apply(localSize: LocalSize, globalSize: GlobalSize): F#T => (F#R, TimeSpan[Time.ms]) =
      kernel.as[F](localSize, globalSize)

    def withSizes(localSize: LocalSize, globalSize: GlobalSize): F#T => (F#R, TimeSpan[Time.ms]) =
      kernel.as[F](localSize, globalSize)
  }

  def code: String= kernel.code
}

object Kernel {
  implicit def forgetSizes(k: KernelWithSizes): KernelNoSizes =
    KernelNoSizes(k.kernel)
}