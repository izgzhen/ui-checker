package vasco

import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.collection.parallel.CollectionConverters._

object Helper {
  def getMeetOverValidPathsSolutionPar[M, N, A](analysis: InterProceduralAnalysis[M, N, A]): DataFlowSolution[N, A] = {
    val inValues = new ConcurrentHashMap[N, A]
    val outValues = new ConcurrentHashMap[N, A]
    // Merge over all contexts

    for (method <- analysis.contexts.keySet.asScala.par) {
      for (node <- analysis.programRepresentation.getControlFlowGraph(method).asScala) {
        var in = analysis.topValue
        var out = analysis.topValue

        for (context <- analysis.contexts.get(method).asScala.par) {
          val in2 = context.getValueBefore(node)
          val out2 = context.getValueAfter(node)
          if (in2 != null) in = analysis.meet(in, in2)
          if (out2 != null) out = analysis.meet(out, out2)
        }
        inValues.put(node, in)
        outValues.put(node, out)
      }
    }
    // Return data flow solution
    new DataFlowSolution[N, A](inValues, outValues)
  }

  def getMeetOverValidPathsSolution[M, N, A](analysis: InterProceduralAnalysis[M, N, A]): DataFlowSolution[N, A] = {
    val inValues = mutable.Map[N, A]()
    val outValues = mutable.Map[N, A]()
    // Merge over all contexts

    for (method <- analysis.contexts.keySet.asScala) {
      for (node <- analysis.programRepresentation.getControlFlowGraph(method).asScala) {
        var in = analysis.topValue
        var out = analysis.topValue

        for (context <- analysis.contexts.get(method).asScala) {
          val in2 = context.getValueBefore(node)
          val out2 = context.getValueAfter(node)
          if (in2 != null) in = analysis.meet(in, in2)
          if (out2 != null) out = analysis.meet(out, out2)
        }
        inValues.put(node, in)
        outValues.put(node, out)
      }
    }
    // Return data flow solution
    new DataFlowSolution[N, A](inValues.asJava, outValues.asJava)
  }
}
