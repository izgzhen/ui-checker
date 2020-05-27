package com.research.nomad.markii.dataflow

import java.util

import com.research.nomad.markii.Constants
import presto.android.Configs
import soot.SootMethod
import soot.toolkits.graph.{DirectedGraph, ExceptionalUnitGraph}
import vasco.ProgramRepresentation
import vasco.soot.DefaultJimpleRepresentation

import scala.collection.mutable
import scala.jdk.CollectionConverters._

class AFTProgramRepresentation(entryPoints: List[SootMethod]) extends ProgramRepresentation[SootMethod, soot.Unit] {
  private val default = DefaultJimpleRepresentation.v
  private val cfgCache = mutable.Map[SootMethod, DirectedGraph[soot.Unit]]()

  override def getControlFlowGraph(m: SootMethod): DirectedGraph[soot.Unit] = {
    if (!cfgCache.contains(m)) {
      cfgCache.put(m, new ExceptionalUnitGraph(m.getActiveBody))
    }
    cfgCache(m)
  }

  def refreshCallgraph(m: SootMethod): Unit = {
    cfgCache.put(m, new ExceptionalUnitGraph(m.getActiveBody))
  }

  override def resolveTargets(m: SootMethod, n: soot.Unit): util.List[SootMethod] =
    default.resolveTargets(m, n).asScala.filter(target => {
      target.hasActiveBody && (!Configs.isLibraryClass(target.getDeclaringClass.getName) || Constants.isWhitelistedMethod(target))
    }).asJava

  override def getEntryPoints: util.List[SootMethod] = entryPoints.asJava

  override def isCall(node: soot.Unit): Boolean = default.isCall(node)

  override def isPhantomMethod(method: SootMethod): Boolean = default.isPhantomMethod(method)
}