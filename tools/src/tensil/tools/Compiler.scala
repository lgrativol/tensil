/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.collection.mutable
import org.tensorflow.framework.graph.GraphDef
import onnx.onnx.ModelProto
import tensil.{
  Architecture,
  ArchitectureDataType,
  TablePrinter,
  TableLine,
  InstructionLayout
}
import tensil.tools.model.{Model, Program, ConstsEntry, InputOutputEntry}
import tensil.tools.compiler.{
  Backend,
  Frontend,
  TfFrontend,
  OnnxFrontend,
  EmitContext,
  MemoryManager,
  StrideStats,
  MemoryObject,
  MemoryTag,
  MemoryAddressHelper,
  SchedulerResult,
  BackendStats
}

class CompilerException(message: String) extends Exception(message) {}

case class CompilerStats(
    constsUsedSize: Long,
    varsUsedSize: Long,
    layersNumber: Int,
    programSizeBytes: Long,
    constsScalarSize: Long,
    constsUtilization: Float,
    cycles: Long,
    energy: Long,
    macs: Long,
    macEfficiency: Float
) {}

case class CompilerResult(
    arch: Architecture,
    inputObjects: Seq[MemoryObject],
    outputObjects: Seq[MemoryObject],
    stats: CompilerStats
) {}

object CompilerSourceType {
  val Tensorflow = TfFrontend.ModelFileNameExtension
  val ONNX       = OnnxFrontend.ModelFileNameExtension
}

object Compiler {
  def getModelSourceType(modelFileName: String): CompilerSourceType = {
    val i = modelFileName.lastIndexOf('.');

    if (i > 0)
      modelFileName.substring(i + 1)
    else
      ""
  }

  def compile(
      modelName: String,
      modelFileName: String,
      outputNames: Seq[String],
      options: CompilerOptions,
      traceContext: TraceContext = TraceContext.empty
  ): CompilerResult = {
    val modelStream     = new FileInputStream(modelFileName)
    val modelSourceType = getModelSourceType(modelFileName)

    compileStreamToFiles(
      modelName,
      modelSourceType,
      modelStream,
      outputNames,
      options,
      traceContext
    )
  }

  def compileStreamToFiles(
      modelName: String,
      modelSourceType: CompilerSourceType,
      modelStream: InputStream,
      outputNames: Seq[String],
      options: CompilerOptions,
      traceContext: TraceContext = TraceContext.empty
  ): CompilerResult = {
    val constsFileName  = s"$modelName.tdata"
    val programFileName = s"$modelName.tprog"

    val constsStream  = new FileOutputStream(constsFileName)
    val programStream = new FileOutputStream(programFileName)

    val result = compileStreamToStreams(
      modelName,
      modelSourceType,
      modelStream,
      outputNames,
      programStream,
      constsStream,
      options,
      traceContext
    )

    constsStream.close()
    programStream.close()

    def objectToEntries(obj: MemoryObject) = {
      require(obj.span.forall(_.tag == MemoryTag.Vars))

      var entries = mutable.ArrayBuffer.empty[InputOutputEntry]

      var base = obj.span.head.raw
      for (i <- 1 until obj.span.size) {
        val nextExpected = obj.span(i - 1).raw + 1
        val nextBase     = obj.span(i).raw

        if (nextExpected != nextBase) {
          entries += InputOutputEntry(
            name = obj.name,
            base = base,
            size = nextExpected - base
          )
          base = nextBase
        }
      }

      entries += InputOutputEntry(
        name = obj.name,
        base = base,
        size = obj.span.last.raw + 1 - base
      )

      entries.toSeq
    }

    def objectsToEntries(objs: Seq[MemoryObject]) =
      objs
        .map(objectToEntries(_))
        .flatten
        .toArray
        .sortBy(_.base)
        .toSeq

    val model = Model(
      name = modelName,
      program = Program(
        fileName = programFileName,
        size = result.stats.programSizeBytes
      ),
      consts = Seq(
        ConstsEntry(
          fileName = constsFileName,
          base = 0,
          size = result.stats.constsUsedSize
        )
      ),
      inputs = objectsToEntries(result.inputObjects),
      outputs = objectsToEntries(result.outputObjects),
      arch = options.arch
    )

    val manifestStream = new FileOutputStream(s"$modelName.tmodel")

    upickle.default.writeToOutputStream(model, manifestStream)
    manifestStream.close()

    result
  }

  def compileStreamToStreams(
      modelName: String,
      modelSourceType: CompilerSourceType,
      modelStream: InputStream,
      outputNames: Seq[String],
      programStream: OutputStream,
      constsStream: OutputStream,
      options: CompilerOptions,
      traceContext: TraceContext = TraceContext.empty
  ): CompilerResult = {
    val startTime = System.nanoTime()

    val graphStream = options.printGraphFileName.map(new FileOutputStream(_))
    val printProgramStream = if (options.printProgramFileName.isDefined) {
      Some(new FileOutputStream(options.printProgramFileName.get))
    } else None

    val frontend: Frontend =
      if (modelSourceType == CompilerSourceType.Tensorflow) {
        new TfFrontend(
          graphDef = util.protoFromStream(GraphDef, modelStream),
          arch = options.arch,
          graphStream = graphStream,
          options = options
        )
      } else if (modelSourceType == CompilerSourceType.ONNX) {
        new OnnxFrontend(
          modelProto = util.protoFromStream(ModelProto, modelStream),
          arch = options.arch,
          graphStream = graphStream,
          options = options
        )
      } else
        throw new CompilerException(
          s"No frontend to support ${modelSourceType}"
        )

    val mm = new MemoryManager(
      constsStream = constsStream,
      dataType = options.arch.dataType,
      arch = options.arch,
      mkConstsDimensions = frontend.mkConstsDimensions,
      traceContext = traceContext,
      tracepointConditions = options.tracepointConditions
    )

    val layout =
      InstructionLayout(options.arch)
    val backend = new Backend(
      programStream = programStream,
      layout = layout,
      dataType = options.arch.dataType,
      printProgramStream = printProgramStream.map(new DataOutputStream(_)),
      printComments = options.printProgramWithComments,
      tracepointConditions = options.tracepointConditions,
      tracepointResolveRefToObject = mm.resolveRefToObject(_),
      traceContext = traceContext
    )
    val backendStats =
      if (options.collectBackendStats) Some(new BackendStats()) else None

    var layerSchedulerResults: List[SchedulerResult] = Nil
    var macs                                         = 0L
    var macEfficiency                                = 0f

    try {
      if (options.printProgress)
        println(
          s"Traversing from output node(s): ${outputNames.mkString(",")} ..."
        )

      val flowNodeNames = frontend.traverse(outputNames)

      if (options.printProgress)
        println(s"Rewriting emitters ...")

      val flowEmitters = frontend.rewrite(flowNodeNames)

      val context = EmitContext(backend, backendStats, mm, outputNames)

      val emitResults = for (emitter <- flowEmitters) yield {
        val r = emitter(context)
        mm.freeConsumedObjects()
        r
      }

      layerSchedulerResults = emitResults.filter(_.isDefined).map(_.get).toList
      macs = layerSchedulerResults.map(_.macs).sum
      macEfficiency =
        if (backendStats.isDefined)
          BackendStats.macEfficiency(backendStats.get, options.arch, macs)
        else
          0f

      // TODO: fix leaks
      // mm.reportObjects()
      // mm.reportSpans()

      val programSizeBytes =
        backend.instructionsCount * layout.instructionSizeBytes
      val stats =
        CompilerStats(
          constsUsedSize = mm.constsMaxSize,
          varsUsedSize = mm.varsMaxSize,
          layersNumber = layerSchedulerResults.size,
          programSizeBytes = programSizeBytes,
          constsScalarSize = mm.constsScalarSize,
          constsUtilization = mm.constsUtilization,
          cycles = backendStats.map(_.totalCycles).getOrElse(0),
          energy = backendStats.map(_.totalEnergy).getOrElse(0),
          macs = macs,
          macEfficiency = macEfficiency
        )

      CompilerResult(
        arch = options.arch,
        inputObjects = mm.inputObjects,
        outputObjects = mm.outputObjects,
        stats = stats
      )
    } finally {
      val endTime = System.nanoTime()

      if (graphStream.isDefined) graphStream.get.close()

      if (printProgramStream.isDefined) printProgramStream.get.close()

      if (options.printSummary) {
        val tb = new TablePrinter(Some("COMPILER SUMMARY"))

        tb.addNamedLine("Model", modelName)
        layout.addTableLines(tb)
        tb.addNamedLine(
          "Consts memory maximum usage (vectors/scalars)",
          mm.constsMaxSize,
          mm.constsMaxSize * options.arch.arraySize
        )
        tb.addNamedLine(
          "Vars memory maximum usage (vectors/scalars)",
          mm.varsMaxSize,
          mm.varsMaxSize * options.arch.arraySize
        )
        tb.addNamedLine(
          "Consts memory aggregate usage (vectors/scalars)",
          mm.constsAggSize,
          mm.constsAggSize * options.arch.arraySize
        )
        tb.addNamedLine(
          "Vars memory aggregate usage (vectors/scalars)",
          mm.varsAggSize,
          mm.varsAggSize * options.arch.arraySize
        )
        tb.addNamedLine("Number of layers", layerSchedulerResults.size)
        if (backendStats.isDefined)
          BackendStats.printSummary(
            backendStats.get,
            tb,
            options.arch,
            Some(macs)
          )
        tb.addNamedLine(
          "Total number of instructions",
          backend.instructionsCount
        )
        tb.addNamedLine(
          "Compilation time (seconds)",
          (endTime - startTime).toFloat / 1e9f
        )
        tb.addNamedLine("True consts scalar size", mm.constsScalarSize)
        tb.addNamedLine("Consts utilization (%)", mm.constsUtilization * 100f)
        val (macsLetter, macsDivisor) =
          BackendStats.getUnitsLetterAndDivisor(macs)
        tb.addNamedLine(
          s"True MACs (${macsLetter}MAC)",
          macs.toFloat / macsDivisor
        )
        if (backendStats.isDefined)
          tb.addNamedLine("MAC efficiency (%)", macEfficiency * 100f)
        print(tb)
      }

      if (options.printLayersSummary) {
        val layerSchedulerResultsWithIndex =
          layerSchedulerResults.zipWithIndex

        for (
          groupResultsWithIndex <- layerSchedulerResultsWithIndex.grouped(32)
        ) {
          val tb = new TablePrinter(Some("LAYERS SUMMARY"), true)
          tb.addLine(
            new TableLine(
              List("Layer:") ++ groupResultsWithIndex.map(_._2)
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Number of stages:"
              ) ++ groupResultsWithIndex
                .map(_._1.numberOfStages)
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Number of combined stages:"
              ) ++ groupResultsWithIndex
                .map(_._1.numberOfCombinedStages)
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Number of partitions:"
              ) ++ groupResultsWithIndex.map(_._1.numberOfPartitions)
            )
          )
          val (macsLetter, macsDivisor) =
            BackendStats.getUnitsLetterAndDivisor(
              groupResultsWithIndex
                .map(_._1.macs)
                .filter(v => v > 0)
                .min
            )
          tb.addLine(
            new TableLine(
              List(
                s"True MACs (${macsLetter}MAC):"
              ) ++ groupResultsWithIndex
                .map(_._1.macs.toFloat)
                .map(_ / macsDivisor)
                .map(f => f"$f%.3f")
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "MAC efficiency (%):"
              ) ++ groupResultsWithIndex
                .map(_._1.macEfficiency)
                .map(_ * 100f)
                .map(f => f"$f%.1f")
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Accumulator utilization (%):"
              ) ++ groupResultsWithIndex
                .map(_._1.accumulatorUtilization)
                .map(_ * 100f)
                .map(f => f"$f%.1f")
            )
          )
          tb.addLine(
            new TableLine(
              List("Local utilization (%):") ++ groupResultsWithIndex
                .map(_._1.localUtilization)
                .map(_ * 100f)
                .map(f => f"$f%.1f")
            )
          )
          print(tb)
        }
      }

      if (backendStats.isDefined) {
        if (options.printInstructionsSummary) {
          BackendStats.printCompositionSummary("TOTAL", backendStats.get)
          BackendStats.printCyclesSummary("TOTAL", backendStats.get)
          BackendStats.printEnergySummary("TOTAL", backendStats.get)
        }

        if (options.printStridesSummary) {
          def printStrideStats(
              title: String,
              select: StrideStats => Any
          ): Unit = {
            val tb = new TablePrinter(Some(title), true)
            BackendStats.printStrideStats(
              options.arch.stride0Depth,
              options.arch.stride1Depth,
              backendStats.get,
              select,
              tb
            )
            print(tb)
          }

          printStrideStats(
            "TOTAL STRIDES COUNT SUMMARY",
            stats => stats.count
          )
          printStrideStats(
            "TOTAL STRIDES MAX SIZE SUMMARY",
            stats => stats.maxSize
          )
          printStrideStats(
            "TOTAL STRIDES AVERAGE SIZE SUMMARY",
            stats => Math.round(stats.totalSize.toFloat / stats.count.toFloat)
          )
        }

        options.arch.dataType.reportAndResetOverUnderflowStats()
      }
    }
  }
}
