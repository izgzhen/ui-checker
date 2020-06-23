package com.research.nomad.markii

import java.io.{File, PrintWriter}

import com.research.nomad.markii.dataflow.AbsNode.ViewNode
import com.research.nomad.markii.dataflow.{AFTDomain, AbstractValue, AbstractValueProp, AbstractValuePropVasco}
import heros.InterproceduralCFG
import heros.solver.IFDSSolver
import io.github.izgzhen.msbase.IOUtil
import presto.android.gui.IDNameExtractor
import presto.android.gui.wtg.util.WTGUtil
import presto.android.{Configs, Debug, Hierarchy, MethodNames}
import presto.android.xml.{AndroidView, XMLParser}
import soot.jimple.infoflow.android.iccta.{Ic3ResultLoader, Intent}
import soot.jimple.toolkits.callgraph.Edge
import soot.jimple.{EqExpr, IfStmt, InstanceInvokeExpr, IntConstant, Jimple, JimpleBody, LookupSwitchStmt, NullConstant, Stmt, StringConstant}
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG
import soot.jimple.toolkits.pointer.LocalMustAliasAnalysis
import soot.toolkits.graph.CompleteUnitGraph
import soot.toolkits.scalar.{SimpleLiveLocals, SmartLocalDefs}
import soot.{Body, Local, RefType, Scene, SootClass, SootMethod, Value}
import vasco.{DataFlowSolution, Helper}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

case class Runner(method: SootMethod, loopExit: soot.Unit, view: Local)

/* Created at 3/8/20 by zhen */
object GUIAnalysis extends IAnalysis {
  val hier: Hierarchy = Hierarchy.v()
  val xmlParser: XMLParser = XMLParser.Factory.getXMLParser
  private var writer: ConstraintWriter = _

  private val methodAndReachables = mutable.Map[SootMethod, mutable.Set[SootMethod]]()

  private def reachableMethods(m: SootMethod): Set[SootMethod] = {
    methodAndReachables.get(m) match {
      case Some(s) => return s.toSet
      case None =>
    }
    val reachables = mutable.Set[SootMethod](m)
    methodAndReachables.put(m, reachables)
    val worklist = mutable.Queue[SootMethod]()
    worklist.addOne(m)
    while (worklist.nonEmpty) {
      val source = worklist.dequeue()
      methodTargets.get(source) match {
        case Some(targets) =>
          for (target <- targets) {
            if (!reachables.contains(target)) {
              reachables.add(target)
              worklist.addOne(target)
            }
          }
        case None =>
      }
      // FIXME: refactor duplicated code
      extraEdgeOutMap.get(source) match {
        case Some(targets) =>
          for (target <- targets) {
            if (!reachables.contains(target)) {
              reachables.add(target)
              worklist.addOne(target)
            }
          }
        case None =>
      }
    }
    reachables.toSet
  }

  private val imageViewClass = Scene.v.getSootClass("android.widget.ImageView")
  private val buttonViewClass = Scene.v.getSootClass("android.widget.Button")

  def getIdName(id: Int): Option[String] = {
    val idName = IDNameExtractor.v.idName(id)
    if (idName != null && idName.length > 0) {
      Some(idName)
    } else {
      None
    }
  }

  def getIdName(node: ViewNode): Set[String] = {
    node.id.flatMap(i => getIdName(i))
  }

  def analyzeViewNode(viewNode: ViewNode, ownerActivities: Set[SootClass]): Unit = {
    viewNode.id.foreach(id => {
      getIdName(id) match {
        case Some(idName) =>
          writer.writeConstraint(ConstraintWriter.Constraint.idName, idName, viewNode.nodeID)
        case None =>
      }
    })
    // TODO: analyze the mutations of default values
    var hasContentDescription = false
    for ((attrib, value) <- viewNode.getAttrs) {
      if (value != null) {
        attrib match {
          case AndroidView.ViewAttr.layout_height =>
            writer.writeDimensionConstraint(ConstraintWriter.Constraint.layoutHeight, value, viewNode.nodeID)
          case AndroidView.ViewAttr.layout_width =>
            writer.writeDimensionConstraint(ConstraintWriter.Constraint.layoutWidth, value, viewNode.nodeID)
          case AndroidView.ViewAttr.textSize =>
            writer.writeDimensionConstraint(ConstraintWriter.Constraint.textSize, value, viewNode.nodeID)
          case AndroidView.ViewAttr.background =>
            writer.writeConstraint(ConstraintWriter.Constraint.background, value, viewNode.nodeID)
          case AndroidView.ViewAttr.text =>
            writer.writeConstraint(ConstraintWriter.Constraint.textContent, value, viewNode.nodeID)
            if (value.toLowerCase().contains("rec")) {
              writer.writeConstraint(ConstraintWriter.Constraint.recordButton, viewNode.nodeID)
            }
            if (value.toLowerCase().contains("continue")) {
              writer.writeConstraint(ConstraintWriter.Constraint.actionButton, viewNode.nodeID)
            }
          case AndroidView.ViewAttr.contentDescription => hasContentDescription = true
          case _ =>
        }
      }
    }
    for ((eventType, methodName) <- viewNode.getInlineClickHandlers) {
      for (act <- ownerActivities) {
        val method = act.getMethodByNameUnsafe(methodName)
        if (method != null) {
          writer.writeConstraint(ConstraintWriter.Constraint.eventHandler, eventType, method, viewNode.nodeID)
          analyzeAnyHandlerPostVASCO(method)
        }
      }
    }
    for ((attrib, value) <- viewNode.getAppAttrs) {
      if (value != null) {
        writer.writeConstraint(ConstraintWriter.Constraint.withName(attrib.name()), viewNode.nodeID, value)
      }
    }
    viewNode.sootClass match {
      case Some(c) =>
        if (!hasContentDescription && hier.isSubclassOf(c, imageViewClass)) {
          writer.writeConstraint(ConstraintWriter.Constraint.imageHasNoContentDescription, viewNode.nodeID)
        }
        if (Constants.isAdViewClass(c)) {
          writer.writeConstraint(ConstraintWriter.Constraint.adViewClass, c)
        }
        writer.writeConstraint(ConstraintWriter.Constraint.viewClass, c.getName, viewNode.nodeID)
        if (hier.isSubclassOf(c, buttonViewClass)) writer.writeConstraint(ConstraintWriter.Constraint.buttonView, viewNode.nodeID)
        if (Constants.isDialogClass(c)) writer.writeConstraint(ConstraintWriter.Constraint.dialogView, viewNode.nodeID, icfg.getMethodOf(viewNode.allocSite))
      case None =>
    }
  }

  def analyzeActivityHandlerPostVasco(act: SootClass, handler: SootMethod): Unit = {
    for (endpoint <- icfg.getEndPointsOf(handler).asScala) {
      val aftDomain = vascoSolution.getValueAfter(endpoint)
      if (aftDomain != null) {
        // TODO: inspect view nodes at the end of each activity handler
        // NOTE: there is extra computation, but do we care?
        for ((node, children) <- aftDomain.nodeEdgeMap) {
          val ownerActivities = aftDomain.getOwnerActivities(handler, endpoint.asInstanceOf[Stmt], node)
          analyzeViewNode(node, ownerActivities)
          for (child <- children) {
            writer.writeConstraint(ConstraintWriter.Constraint.containsView, node.nodeID, child.nodeID)
            analyzeViewNode(child, ownerActivities)
          }
        }
      }
    }
  }

  private def dumpCallgraph(): Unit = {
    val printWriter: PrintWriter = new PrintWriter("/tmp/icfg.txt")
    printWriter.print(Scene.v().getCallGraph.toString.replace(" ==> ", "\n\t==> "))
    printWriter.close()
  }

  private val aliasAnalysisMap = mutable.Map[SootMethod, LocalMustAliasAnalysis]()
  private val localDefsMap = mutable.Map[SootMethod, SmartLocalDefs]()

  def getLocalDefs(m: SootMethod) : SmartLocalDefs = {
    if (!localDefsMap.contains(m)) {
      val ug = new CompleteUnitGraph(m.getActiveBody)
      localDefsMap.addOne(m, new SmartLocalDefs(ug, new SimpleLiveLocals(ug)))
    }
    localDefsMap(m)
  }

  def getDefsOfAt(m: SootMethod, l: Local, u: soot.Unit): Set[Stmt] = {
    getLocalDefs(m).getDefsOfAt(l, u).asScala.toSet.map((u: soot.Unit) => u.asInstanceOf[Stmt])
  }

  def isAlias(l1: Local, l2: Local, stmt1: Stmt, stmt2: Stmt, m: SootMethod): Boolean = {
    if (l1.equivTo(l2)) {
      return true
    }
    if (!aliasAnalysisMap.contains(m)) {
      val ug = new CompleteUnitGraph(m.getActiveBody)
      aliasAnalysisMap.addOne(m, new LocalMustAliasAnalysis(ug, false))
    }
    val analysis = aliasAnalysisMap(m)
    analysis.mustAlias(l1, stmt1, l2, stmt2) || getLocalDefs(m).getDefsOf(l2) == getLocalDefs(m).getDefsOf(l1)
  }

  private val allHandlers = mutable.Set[SootMethod]()
  private val analyzedMethods = mutable.Set[SootMethod]()

  def initAllHandlers(): Unit = {
    for (c <- Scene.v().getApplicationClasses.asScala) {
      if (c.isConcrete && Constants.guiClasses.exists(listener => hier.isSubclassOf(c, listener))) {
        for (m <- c.getMethods.asScala) {
          if (m.hasActiveBody && m.getName.startsWith("on")) {
            allHandlers.add(m)
          }
        }
      }
    }

    for (receiver <- xmlParser.getReceivers.asScala) {
      if (receiver != null) {
        val curCls = Scene.v.getSootClassUnsafe(receiver)
        if (curCls != null && curCls.isConcrete && hier.isSubclassOf(curCls, Scene.v().getSootClass("android.content.BroadcastReceiver"))) {
          val m = curCls.getMethodByNameUnsafe("onReceive")
          if (m != null && m.hasActiveBody) {
            allHandlers.add(m)
          }
        }
      }
    }
  }

  val extraEdgeOutMap: mutable.Map[SootMethod, mutable.Set[SootMethod]] = mutable.Map()

  private def isTargetMethod(target: SootMethod): Boolean =
    target.isConcrete && target.hasActiveBody && target.getDeclaringClass.isApplicationClass &&
      (!Configs.isLibraryClass(target.getDeclaringClass.getName))

  def patchCallGraph(): Unit = {
    for (c <- Scene.v().getApplicationClasses.asScala) {
      for (m <- c.getMethods.asScala) {
        if (m.isConcrete && m.hasActiveBody) {
          for (unit <- m.getActiveBody.getUnits.asScala) {
            val stmt = unit.asInstanceOf[Stmt]
            if (stmt.containsInvokeExpr()) {
              val dispatchedTargets = mutable.Set[SootMethod]()
              val invokedTarget = Util.getMethodUnsafe(stmt.getInvokeExpr)
              if (invokedTarget != null) {
                if (isTargetMethod(invokedTarget)) {
                  dispatchedTargets.add(invokedTarget)
                } else {
                  stmt.getInvokeExpr match {
                    case instanceInvokeExpr: InstanceInvokeExpr =>
                      instanceInvokeExpr.getBase.getType match {
                        case refType: RefType =>
                          val dispatchedTarget = hier.virtualDispatch(invokedTarget, refType.getSootClass)
                          if (dispatchedTarget != null && isTargetMethod(dispatchedTarget)) {
                            dispatchedTargets.add(dispatchedTarget)
                          } else {
                            val subTypes = hier.getConcreteSubtypes(refType.getSootClass).asScala
                            if (subTypes.size < 5) { // FIXME: avoid over-explosion....
                              for (subClass <- subTypes) {
                                if (subClass != null && subClass.isConcrete) {
                                  val dispatchedTarget = hier.virtualDispatch(invokedTarget, subClass)
                                  if (dispatchedTarget != null) {
                                    dispatchedTargets.add(dispatchedTarget)
                                  }
                                }
                              }
                            }
                          }
                        case _ =>
                      }
                    case _ =>
                  }
                }
              }
              for (target <- dispatchedTargets) {
                if (isTargetMethod(target)) {
                  var edge: Edge = null
                  try {
                    edge = Scene.v().getCallGraph.findEdge(stmt, target)
                  } catch {
                    case ignored: Exception =>
                  }
                  if (edge == null) {
                    Scene.v().getCallGraph.addEdge(new Edge(m, stmt, target))
                  }
                }
                if (target.getSignature == "<android.os.Handler: boolean postDelayed(java.lang.Runnable,long)>") {
                  val runnableType = stmt.getInvokeExpr.getArg(0).getType.asInstanceOf[RefType]
                  val run = hier.virtualDispatch(Scene.v().getMethod("<java.lang.Runnable: void run()>"), runnableType.getSootClass)
                  if (run != null) {
                    extraEdgeOutMap.getOrElseUpdate(m, mutable.Set()).add(run)
                  } else {
                    println("No run in " + runnableType.getSootClass)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private var vascoSolution: DataFlowSolution[soot.Unit, AFTDomain] = _
  def runVASCO(): Unit = {
    // NOTE: over-approx of entrypoints
    val entrypointsFull = allActivities.flatMap(DynamicCFG.getRunner).map(_.method).toList
    val vascoProp = new AbstractValuePropVasco(entrypointsFull)
    println("VASCO starts")
    vascoProp.doAnalysis()
    println("VASCO finishes")

    analyzedMethods.addAll(allHandlers)
    analyzedMethods.addAll(vascoProp.getMethods.asScala)
    if (sys.env.contains("BATCH_RUN")) {
      vascoSolution = Helper.getMeetOverValidPathsSolution(vascoProp)
    } else {
      vascoSolution = Helper.getMeetOverValidPathsSolutionPar(vascoProp)
    }
    println("VASCO solution generated")
  }

  var outputPath = "/tmp/gator-facts/"
  var debugMode = false
  var onlyPreAnalysis = false

  def readConfigs(): Unit = {
    for (param <- Configs.clientParams.asScala) {
      if (param.startsWith("output:")) outputPath = param.substring("output:".length)
    }

    debugMode = Configs.clientParams.contains("debugMode:true")
    onlyPreAnalysis = Configs.clientParams.contains("onlyPreAnalysis:true")
  }

  private var ifdsSolver: IFDSSolver[soot.Unit, (Value, Set[AbstractValue]), SootMethod, InterproceduralCFG[soot.Unit, SootMethod]] = _
  private var icfg: JimpleBasedInterproceduralCFG = _

  def getIfdsResultAt(stmt: Stmt, target: Value): Iterable[AbstractValue] = {
    ifdsSolver.ifdsResultsAt(stmt).asScala.flatMap { case (tainted, taints) =>
      if (tainted.equivTo(target)) {
        taints
      } else {
        Set()
      }
    }
  }

  def runIFDS(): Unit = {
    icfg = new JimpleBasedInterproceduralCFG()
    val analysis = new AbstractValueProp(icfg, debugMode)
    ifdsSolver = new IFDSSolver(analysis)
    System.out.println("======================== IFDS Solver started  ========================")
    val entrypoints = Scene.v().getEntryPoints.asScala.filter(m => {
      val c = m.getDeclaringClass
      Constants.isActivity(c) || Constants.isService(c) || Constants.isReceiver(c)
    })
    Scene.v().setEntryPoints(entrypoints.asJava)
    System.out.println("======================= IFDS Entry-points " + Scene.v().getEntryPoints.size() + " =======================")
    IOUtil.writeLines(Scene.v().getEntryPoints.asScala.map(_.toString).toList, "/tmp/entrypoints.txt")
    // TODO: Develop a method to debug performance issue here
    ifdsSolver.solve()
    analyzedMethods.addAll(analysis.visitedMethods)
    System.out.println("======================== IFDS Solver finished ========================")
  }

  private val allActivities = hier.frameworkManaged.keySet().asScala.filter(c => hier.applicationActivityClasses.contains(c) && !c.isAbstract).toSet

  def writeConstraintsPostVASCO(): Unit = {
    for (act <- allActivities) {
      for ((handler, event) <- getActivityHandlers(act)) {
        analyzeAnyHandlerPostVASCO(handler)
        writer.writeConstraint(ConstraintWriter.Constraint.activityEventHandler, event, handler, act)
        analyzeActivityHandlerPostVasco(act, handler)
      }
      val lifecycleMethods = List(
        act.getMethodUnsafe(MethodNames.onActivityCreateSubSig),
        act.getMethodUnsafe(MethodNames.onActivityStartSubSig)
      )
      for (m <- lifecycleMethods) {
        if (m != null) {
          analyzeAnyHandlerPostVASCO(m)
          analyzeActivityHandlerPostVasco(act, m)
        }
      }
    }

    for (handler <- allHandlers) {
      analyzeAnyHandlerPostVASCO(handler)
    }

    if (mainActivity != null) {
      writer.writeConstraint(ConstraintWriter.Constraint.mainActivity, mainActivity)
    }

    for ((className, filters) <- xmlParser.getIntentFilters.asScala) {
      try {
        val curCls = Scene.v.getSootClass(className)
        if (curCls != null && curCls.isConcrete) {
          for (filter <- filters.asScala) {
            for (action <- filter.getActions.asScala) {
              val m = curCls.getMethodByNameUnsafe("onReceive")
              if (m != null) writer.writeConstraint(ConstraintWriter.Constraint.intentFilter, action, m)
            }
          }
        }
      } catch {
        case ignored: NullPointerException =>
      }
    }

    writer.close()
  }

  private val prefActivity = Scene.v.getSootClass("android.preference.PreferenceActivity")

  def analyzeActivityPreVasco(activityClass: SootClass): Unit = {
    for ((handler, _) <- getActivityHandlers(activityClass)) {
      DynamicCFG.addActivityHandlerToEventLoop(activityClass, handler)
    }
    val onCreate = hier.virtualDispatch(MethodNames.onActivityCreateSubSig, activityClass)
    if (onCreate != null) {
      writer.writeConstraint(ConstraintWriter.Constraint.lifecycleMethod, activityClass, "onCreate", onCreate)
    }

    val onDestroy = hier.virtualDispatch(MethodNames.onActivityDestroySubSig, activityClass)
    if (onDestroy != null) {
      writer.writeConstraint(ConstraintWriter.Constraint.lifecycleMethod, activityClass, "onDestroy", onDestroy)
    }
    if (hier.isSubclassOf(activityClass, prefActivity)) {
      writer.writeConstraint(ConstraintWriter.Constraint.preferenceActivity, activityClass)
    }
  }

  private val showDialogInvocations = mutable.Map[Stmt, Local]()

  def instrumentRunOnUiThread(m: SootMethod): Unit = {
    val swaps = mutable.Map[Stmt, Stmt]()
    for (unit <- m.getActiveBody.getUnits.asScala) {
      val stmt = unit.asInstanceOf[Stmt]
      if (stmt.containsInvokeExpr()) {
        val invokeExpr = stmt.getInvokeExpr
        if (Constants.isActivityRunOnUiThread(invokeExpr.getMethod)) {
          val runnableArg = invokeExpr.getArg(0).asInstanceOf[Local]
          val runnableArgClass = runnableArg.getType.asInstanceOf[RefType].getSootClass
          val runMethod = runnableArgClass.getMethodByNameUnsafe("run")
          val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(runnableArg, runMethod.makeRef()))
          Scene.v().getCallGraph.removeAllEdgesOutOf(stmt)
          Scene.v().getCallGraph.addEdge(new Edge(m, invocation, runMethod))
          methodAndReachables.getOrElseUpdate(m, mutable.Set()).add(runMethod)
          // FIXME: can't reach handler here
          swaps.put(stmt, invocation)
        }
      }
    }
    for ((out, in) <- swaps) {
      m.getActiveBody.getUnits.swapWith(out, in)
    }
  }

  def analyzeAnyHandlerPreVasco(handler: SootMethod): Unit = {
    val reachedMethods = reachableMethods(handler)
    for (reached <- reachedMethods) {
      if (reached.getDeclaringClass.isApplicationClass && reached.isConcrete && reached.hasActiveBody) {
        val swaps = mutable.Map[Stmt, Stmt]()
        for (unit <- reached.getActiveBody.getUnits.asScala) {
          val stmt = unit.asInstanceOf[Stmt]
          if (stmt.containsInvokeExpr()) {
            val invokeExpr = stmt.getInvokeExpr
            if (invokeExpr.getMethod.getSubSignature == "void startActivity(android.content.Intent)") {
              // NOTE: the base type is only used to provide an application context, thus it can't be used to infer the
              //       source activity
              GUIAnalysis.getIfdsResultAt(stmt, invokeExpr.getArg(0)).foreach {
                case AbstractValue.Intent(intent) =>
                  // FIXME: imprecision if we ignore the actions etc. fields?
                  val methods = mutable.Set[SootMethod]()
                  for (target <- intent.targets) {
                    DynamicCFG.getRunner(target) match {
                      case Some(runner) => methods.add(runner.method)
                      case None =>
                    }
                    if (!ic3Enabled) {
                      writer.writeConstraint(ConstraintWriter.Constraint.startActivity, handler, reached, target)
                    }
                  }
                  val base = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.getType.asInstanceOf[RefType].getSootClass
                  val runAllMethod = DynamicCFG.getRunAll(stmt, methods, base, invokeExpr.getMethod)
                  Scene.v().getCallGraph.removeAllEdgesOutOf(stmt)
                  Scene.v().getCallGraph.addEdge(new Edge(reached, stmt, runAllMethod))
                  // NOTE: no invocation/stmt swap
                  startWindowStmts.add(stmt)
                  invokeExpr.setMethodRef(runAllMethod.makeRef())
                case _ =>
              }
            }
            // TODO: need to bind analyze listener setting in IFDS first
            // TODO: actually -- this is a more complicated recursive problem. but we can play it easy first.
            // FIXME: AlertDialog$Builder has a show method as well, this might not work well...but we take it as if it is a Dialog object now
            if (Constants.isDialogShow(invokeExpr.getMethod.getSignature) ||
                Constants.isDialogBuilderShow(invokeExpr.getMethod.getSignature)) {
              val dialogBase = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
              val methods = mutable.Set[SootMethod]()
              for (defStmt <- getDefsOfAt(reached, dialogBase, stmt)) {
                DynamicCFG.getRunnerOfDialog(defStmt) match {
                  case Some(runner) => methods.add(runner.method)
                  case None =>
                }
              }
              if (methods.nonEmpty) {
                // FIXME: I need to build a static class for this type of work
                val runAllMethod = DynamicCFG.getRunAllDialog(stmt, methods, reached)
                val invocation = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(runAllMethod.makeRef(), dialogBase))
                Scene.v().getCallGraph.removeAllEdgesOutOf(stmt)
                Scene.v().getCallGraph.addEdge(new Edge(reached, invocation, runAllMethod))
                swaps.put(stmt, invocation)
                startWindowStmts.add(invocation)
                showDialogInvocations.put(invocation, dialogBase)
              }
            }
            if (invokeExpr.getMethod.getSubSignature == "void showDialog(int)") {
              invokeExpr.getArg(0) match {
                case intConstant: IntConstant =>
                  if (showCreateDialog.contains(reached.getDeclaringClass) &&
                      showCreateDialog(reached.getDeclaringClass).contains(intConstant.value)) {
                    val createMethod = showCreateDialog(reached.getDeclaringClass)(intConstant.value)

                    DynamicCFG.getRunnerOfDialog(reached.getDeclaringClass, createMethod, intConstant) match {
                      case Some((runner, internalInvocation)) =>
                        val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(reached.getActiveBody.getThisLocal, runner.method.makeRef()))
                        Scene.v().getCallGraph.removeAllEdgesOutOf(stmt)
                        Scene.v().getCallGraph.addEdge(new Edge(reached, invocation, runner.method))
                        methodAndReachables.getOrElseUpdate(reached, mutable.Set()).add(runner.method)
                        methodAndReachables.getOrElseUpdate(handler, mutable.Set()).add(runner.method)
                        swaps.put(stmt, invocation)
                        startWindowStmts.add(invocation)
                        showDialogInvocations.put(internalInvocation, runner.view)
                      case None =>
                    }
                  }
                case _ =>
              }
            }
            // Replace loadNativeAds with the load handler
            if (invokeExpr.getMethod.getName == "loadNativeAds") {
              for ((argType, idx) <- invokeExpr.getMethod.getParameterTypes.asScala.zipWithIndex) {
                if (argType.toString == "com.applovin.nativeAds.AppLovinNativeAdLoadListener") {
                  val listenerArg = invokeExpr.getArg(idx).asInstanceOf[Local]
                  val listenerClass = listenerArg.getType.asInstanceOf[RefType].getSootClass
                  val adLoadHandler = listenerClass.getMethodByNameUnsafe("onNativeAdsLoaded")
                  if (adLoadHandler != null && Constants.isActivity(reached.getDeclaringClass)) {
                    // Instrument adLoadHandler
                    instrumentRunOnUiThread(adLoadHandler)

                    // Replace invocation
                    val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(listenerArg, adLoadHandler.makeRef()))
                    Scene.v().getCallGraph.removeAllEdgesOutOf(stmt)
                    Scene.v().getCallGraph.addEdge(new Edge(reached, invocation, adLoadHandler))
                    methodAndReachables.getOrElseUpdate(reached, mutable.Set()).add(adLoadHandler) // All these are very hacky..
                    methodAndReachables.getOrElseUpdate(handler, mutable.Set()).add(adLoadHandler)
                    swaps.put(stmt, invocation)
                  }
                }
              }
            }
          }
        }

        for ((out, in) <- swaps) {
          reached.getActiveBody.getUnits.swapWith(out, in)
        }
      }
    }
  }

  private val analyzedHandlers = mutable.Set[SootMethod]()
  def analyzeAnyHandlerPostVASCO(handler: SootMethod): Unit = {
    if (analyzedHandlers.contains(handler)) {
      return
    }
    analyzedHandlers.add(handler)
    val reachedMethods = reachableMethods(handler)

    // Analyze the end-points
    for (endpoint <- icfg.getEndPointsOf(handler).asScala) {
      val aftDomain = vascoSolution.getValueAfter(endpoint)
      if (aftDomain != null) {
        for (((viewNode, eventType), eventHandlers) <- aftDomain.nodeHandlerMap) {
          for (eventHandler <- eventHandlers) {
            writer.writeConstraint(ConstraintWriter.Constraint.eventHandler, eventType, eventHandler, viewNode.nodeID)
            analyzeAnyHandlerPostVASCO(eventHandler)
          }
        }
        for ((act, nodes) <- aftDomain.activityRootViewMap) {
          for (viewNode <- nodes) {
            writer.writeConstraint(ConstraintWriter.Constraint.rootView, act, viewNode.nodeID)
          }
        }
      } else {
        // FIXME
        println("[WARN] Null endpoint: " + handler)
      }
    }

    // Analyze each statements in reached method
    // FIXME: performance
    for (reached <- reachedMethods) {
      if (reached.getDeclaringClass.isApplicationClass && reached.isConcrete && reached.hasActiveBody) {
        if (methodIc3Map.contains(reached)) {
          for ((srcClass, intents) <- methodIc3Map(reached)) {
            for (intent <- intents) {
              if (intent.getComponentClass != null && intent.getComponentClass.nonEmpty) {
                val targetAct = Scene.v().getSootClass(intent.getComponentClass)
                writer.writeConstraint(ConstraintWriter.Constraint.startActivity, handler, reached, targetAct)
              }
              if (intent.getAction != null && intent.getAction.equals("android.intent.action.VIEW")) {
                if (intent.getDataScheme != null && intent.getDataScheme.equals("market")) {
                  writer.writeConstraint(ConstraintWriter.Constraint.startViewActivityOfMarketHost, handler, reached);
                } else {
                  writer.writeConstraint(ConstraintWriter.Constraint.startViewActivityOfSomeHosts, handler, reached);
                }
              }
            }
          }
        }

        for (unit <- reached.getActiveBody.getUnits.asScala) {
          val stmt = unit.asInstanceOf[Stmt]
          if (stmt.containsInvokeExpr()) {
            val invokeExpr = stmt.getInvokeExpr

            val aftDomain = vascoSolution.getValueBefore(stmt)
            val aftDomain2 = vascoSolution.getValueAfter(stmt)
            if (WTGUtil.v.isActivityFinishCall(stmt)) {
              invokeExpr match {
                case instanceInvokeExpr: InstanceInvokeExpr =>
                  val actClass = instanceInvokeExpr.getBase.getType.asInstanceOf[RefType].getSootClass
                  // FIXME: reachability
                  writer.writeConstraint(ConstraintWriter.Constraint.finishActivity, handler, reached, actClass)
                case _ =>
              }
            }
            if (Constants.isDialogDismiss(invokeExpr.getMethod.getSignature)) {
              val dialog = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
              if (aftDomain != null) {
                // NOTE: We can combine the for-loops here
                for (dialogNode <- aftDomain.getViewNodes(reached, stmt, dialog)) {
                  writer.writeConstraint(ConstraintWriter.Constraint.dismiss, handler, dialogNode.nodeID)
                }
              }
            }
            // FIXME: frauddroid-gba.apk "<android.app.Activity: void showDialog(int)>" and onPrepareDialog, onCreateDialog
            if (showDialogInvocations.contains(stmt)) {
              if (aftDomain != null) {
                val dialogBase = showDialogInvocations(stmt)
                for (dialogNode <- aftDomain.getViewNodes(reached, stmt, dialogBase)) {
                  writer.writeConstraint(ConstraintWriter.Constraint.dialogView, dialogNode.nodeID, icfg.getMethodOf(dialogNode.allocSite))
                  writer.writeConstraint(ConstraintWriter.Constraint.showDialog, handler, reached, dialogNode.nodeID)
                  for (handler <- aftDomain.getDialogButtonHandlers(dialogNode)) {
                    writer.writeConstraint(ConstraintWriter.Constraint.alertDialogFixedButtonHandler, handler, dialogNode.nodeID)
                  }
                }
              }
            }
            if (invokeExpr.getMethod.getSignature == "<com.google.ads.consent.ConsentInformation: void setConsentStatus(com.google.ads.consent.ConsentStatus)>") {
              writer.writeConstraint(ConstraintWriter.Constraint.setStatus, handler, reached)
            }
            if (invokeExpr.getMethod.getSignature == "<com.google.ads.consent.ConsentInformation: void requestConsentInfoUpdate(java.lang.String[],com.google.ads.consent.ConsentInfoUpdateListener)>") {
              val updateListenerType = invokeExpr.getArg(1).getType.asInstanceOf[RefType]
              val onConsentInfoUpdated = updateListenerType.getSootClass.getMethodUnsafe("void onConsentInfoUpdated(com.google.ads.consent.ConsentStatus)")
              if (onConsentInfoUpdated != null) {
                writer.writeConstraint(ConstraintWriter.Constraint.setConsetInfoUpdateHandler, handler, reached)
              }
            }
            if (invokeExpr.getMethod.getSignature == "<android.os.BaseBundle: void putString(java.lang.String,java.lang.String)>") {
              if (invokeExpr.getArg(0).isInstanceOf[StringConstant] && invokeExpr.getArg(1).isInstanceOf[StringConstant]) {
                val arg0 = invokeExpr.getArg(0).asInstanceOf[StringConstant].value
                val arg1 = invokeExpr.getArg(1).asInstanceOf[StringConstant].value
                if (arg0 == "npa" && arg1 == "1") {
                  writer.writeConstraint(ConstraintWriter.Constraint.setNPA, handler)
                }
              }
            }

            if (invokeExpr.getMethod.getSignature == "<com.waps.AdView: void <init>(android.content.Context,android.widget.LinearLayout)>" ||
              invokeExpr.getMethod.getSignature == "<com.waps.MiniAdView: void <init>(android.content.Context,android.widget.LinearLayout)>") {
              val layout = invokeExpr.getArg(1).asInstanceOf[Local]
              val aftDomain = vascoSolution.getValueBefore(stmt)
              if (aftDomain != null) {
                for (node <- aftDomain.getViewNodes(reached, stmt, layout)) {
                  for (idName <- getIdName(node)) {
                    writer.writeConstraint(ConstraintWriter.Constraint.adViewIdName, idName)
                  }
                }
              }
            }

            val invokedMethodClass = invokeExpr.getMethod.getDeclaringClass
            if (invokedMethodClass.getName == "android.media.AudioRecord" && invokeExpr.getMethod.getName == "read") {
              writer.writeConstraint(ConstraintWriter.Constraint.readAudio, handler, reached)
            }
            if (invokedMethodClass.getName == "java.lang.Class" && invokeExpr.getMethod.getName == "getDeclaredMethod") {
              writer.writeConstraint(ConstraintWriter.Constraint.invokesReflectiveAPI, handler, reached)
            }
            if (invokedMethodClass.getName == "com.google.ads.consent.ConsentForm" && invokeExpr.getMethod.getName == "load") {
              writer.writeConstraint(ConstraintWriter.Constraint.loadGoogleConsentForm, handler, reached)
            }
            if (invokedMethodClass.getName == "android.webkit.WebView" && invokeExpr.getMethod.getName == "loadUrl") {
              var arg0 = "ANY"
              if (invokeExpr.getArg(0).isInstanceOf[StringConstant]) {
                arg0 = invokeExpr.getArg(0).asInstanceOf[StringConstant].value
              }
              writer.writeConstraint(ConstraintWriter.Constraint.loadWebViewUrl, handler, reached, arg0)
            }
            if (invokedMethodClass.getName == "com.tongqu.client.utils.Downloader" && invokeExpr.getMethod.getName == "getInst") {
              writer.writeConstraint(ConstraintWriter.Constraint.downloadApp, handler, reached)
            }
            // NOTE: the "reached" field here has a different semantics than gator version
            if (Constants.isAdMethod(invokeExpr.getMethod)) {
              writer.writeConstraint(ConstraintWriter.Constraint.showAd, handler, invokeExpr.getMethod)
            }
            if (Constants.isSuspiciousAdMethod(invokeExpr.getMethod)) {
              writer.writeConstraint(ConstraintWriter.Constraint.showSuspiciousAd, handler, reached)
            }
            if (Constants.isInterstitialAdMethod(invokeExpr.getMethod)) {
              writer.writeConstraint(ConstraintWriter.Constraint.showInterstitialAd, handler, reached)
            }
            if (Constants.isSuspiciousInterstitialAdMethod(invokeExpr.getMethod)) {
              writer.writeConstraint(ConstraintWriter.Constraint.showSuspiciousInterstitialAd, handler, reached)
            }
          }
        }
      }
    }
  }

  private val mainActivity = xmlParser.getMainActivity

  private var methodTargets: Map[SootMethod, Set[SootMethod]] = Map()

  def saveOldCallGraph(): Unit = {
    methodTargets =
      Scene.v().getCallGraph.sourceMethods().asScala.map(src => (src.method(), Scene.v().getCallGraph.edgesOutOf(src).asScala.map(_.getTgt.method()).toSet)).toMap
  }

  def getCallees(methodSig: String): List[SootMethod] = {
    Scene.v.getCallGraph.edgesOutOf(Scene.v.getMethod(methodSig)).asScala.map(_.getTgt.method).toList
  }

  private var ic3Enabled = false
  private val methodIc3Map: mutable.Map[SootMethod, mutable.Set[(SootClass, Set[Intent])]] = mutable.Map()

  private val showCreateDialog = mutable.Map[SootClass, mutable.Map[Int, SootMethod]]()

  private def copyMethod(method: SootMethod, suffix: String): SootMethod = {
    val newMethod = new SootMethod(method.getName + suffix, method.getParameterTypes, method.getReturnType, method.getModifiers)
    val newBody = method.getActiveBody.asInstanceOf[JimpleBody].clone().asInstanceOf[Body]
    newMethod.setActiveBody(newBody)
    newMethod
  }

  private def pruneFirstLookupSwitchExcept(method: SootMethod, i: Int): Unit = {
    val paramLocal = method.getActiveBody.getParameterLocal(0)
    for (unit <- method.getActiveBody.getUnits.asScala) {
      unit match {
        case lookupSwitchStmt: LookupSwitchStmt =>
          assert(lookupSwitchStmt.getKey.equivTo(paramLocal))
          for ((value, idx) <- lookupSwitchStmt.getLookupValues.asScala.zipWithIndex) {
            if (value.value != i) {
              lookupSwitchStmt.setTarget(idx, lookupSwitchStmt.getDefaultTarget)
            }
          }
          return
        case _ =>
      }
    }
    throw new Exception("No lookup")
  }


  private def getIfTarget(method: SootMethod, i: Int): Stmt = {
    val paramLocal = method.getActiveBody.getParameterLocal(0)
    for (unit <- method.getActiveBody.getUnits.asScala) {
      unit match {
        case ifStmt: IfStmt =>
          ifStmt.getCondition match {
            case eqExpr: EqExpr =>
              if (eqExpr.getOp1.equivTo(paramLocal) && eqExpr.getOp2.asInstanceOf[IntConstant].value == i) {
                return ifStmt.getTarget
              }
          }
        case _ =>
      }
    }
    null
  }

  private def pruneIfExcept(method: SootMethod, i: Int): Unit = {
    val paramLocal = method.getActiveBody.getParameterLocal(0)
    val iTarget = getIfTarget(method, i)
    assert(iTarget != null)
    for (unit <- method.getActiveBody.getUnits.asScala) {
      unit match {
        case ifStmt: IfStmt =>
          ifStmt.getCondition match {
            case eqExpr: EqExpr =>
              if (eqExpr.getOp1.equivTo(paramLocal) && eqExpr.getOp2.asInstanceOf[IntConstant].value != i) {
                ifStmt.setTarget(iTarget)
              }
          }
        case _ =>
      }
    }
  }

  def instrumentAllDialogCreate(): Unit = {
    for (activity <- allActivities) {
      val method = activity.getMethodUnsafe("android.app.Dialog onCreateDialog(int)")
      if (method != null) {
        instrumentDialogCreate(method, activity)
      }
    }
  }

  def instrumentDialogCreate(method: SootMethod, activity: SootClass): Unit = {
    val paramLocal = method.getActiveBody.getParameterLocal(0)
    var hasIfStmt = false
    var hasSuperOnCreateDialog = false
    // FIXME: this structure is not working well -- esp. when the onCreateDialog is just a wrapper of some other
    //        invocation
    for (unit <- method.getActiveBody.getUnits.asScala) {
      unit match {
        case lookupSwitchStmt: LookupSwitchStmt =>
          assert(lookupSwitchStmt.getKey.equivTo(paramLocal))
          for (value <- lookupSwitchStmt.getLookupValues.asScala) {
            val newMethod = copyMethod(method, "_" + value.value)
            pruneFirstLookupSwitchExcept(newMethod, value.value)
            activity.addMethod(newMethod)
            showCreateDialog.getOrElseUpdate(activity, mutable.Map()).put(value.value, newMethod)
          }
          return
        case ifStmt: IfStmt =>
          ifStmt.getCondition match {
            case eqExpr: EqExpr =>
              if (eqExpr.getOp1.equivTo(paramLocal)) {
                hasIfStmt = true
                val i = eqExpr.getOp2.asInstanceOf[IntConstant].value
                val newMethod = copyMethod(method, "_" + i)
                pruneIfExcept(newMethod, i)
                activity.addMethod(newMethod)
                showCreateDialog.getOrElseUpdate(activity, mutable.Map()).put(i, newMethod)
              }
            case _ =>
          }
        case stmt: Stmt =>
          if (stmt.containsInvokeExpr() && stmt.getInvokeExpr.getMethod.getName == "onCreateDialog") {
            hasSuperOnCreateDialog = true
          }
      }
    }
//    if (!hasIfStmt && !hasSuperOnCreateDialog) {
//      throw new Exception("No lookup or if\n" + method.getSignature + "\n" + method.getActiveBody)
//    }
  }

  private def instrumentAllDialogInit(): Unit = {
    for (c <- Scene.v.getApplicationClasses.asScala) {
      val methods = c.getMethods.asScala.toList
      for (m <- methods) { // FIXME: ConcurrentModificationError
        if (m.isConcrete && m.hasActiveBody) {
          val customDialogInit: Iterable[(soot.Unit, Local, SootMethod)] = m.getActiveBody.getUnits.asScala.flatMap {
            case stmt: Stmt if stmt.containsInvokeExpr() =>
              val invoked = Util.getMethodUnsafe(stmt.getInvokeExpr)
              if (invoked != null) {
                stmt.getInvokeExpr match {
                  case instanceInvokeExpr: InstanceInvokeExpr if invoked.getName == "<init>" =>
                    val baseClass = instanceInvokeExpr.getBase.getType.asInstanceOf[RefType].getSootClass
                    if (baseClass.isConcrete) {
                      val onCreate = hier.virtualDispatch("void onCreate(android.os.Bundle)", baseClass)
                      if (Constants.isDialogClass(baseClass) && onCreate != null && onCreate.isConcrete && onCreate.hasActiveBody) {
                        Some((stmt, instanceInvokeExpr.getBase.asInstanceOf[Local], onCreate))
                      } else {
                        None
                      }
                    } else {
                      None
                    }
                  case _ => None
                }
              } else {
                None
              }
            case _ => None
          }
          for ((stmt, base, onCreate) <- customDialogInit) {
            val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(base, onCreate.makeRef(), NullConstant.v()))
            Scene.v().getCallGraph.addEdge(new Edge(m, invocation, onCreate))
            m.getActiveBody.getUnits.insertAfter(invocation, stmt)
          }
        }
      }
    }
  }

  def run(): Unit = {
    println("Pre-analysis time: " + Debug.v().getExecutionTime + " seconds")
    println("Mark II")
    readConfigs()

    val ic3 = Configs.project.replace(".apk", "_ic3.txt")
    if (new File(ic3).exists) {
      ic3Enabled = true
      val app = Ic3ResultLoader.load(ic3)
      if (app != null) {
        for (component <- app.getComponentList.asScala) {
          val src = Scene.v.getSootClass(component.getName)
          for (p <- component.getExitPointsList.asScala) {
            val exitMethod = Scene.v.getMethod(p.getInstruction.getMethod)
            val intents = app.getIntents(component, p)
            if (intents != null && intents.size() > 0) {
              methodIc3Map.getOrElseUpdate(exitMethod, mutable.Set()).add((src, intents.asScala.toSet))
            }
          }
        }
      }
    }

    if (onlyPreAnalysis) {
      IOUtil.writeLines(Scene.v().getReachableMethods.listener().asScala.map(_.method().getSignature).toList, "/tmp/methods_markii.txt")
      println("Reachable: " + Scene.v().getReachableMethods.size())
      return
    }

    initAllHandlers()

    instrumentAllDialogInit()

    // TODO: use a proper harness
    patchCallGraph()

    if (debugMode) {
      dumpCallgraph()
    }

    saveOldCallGraph()

    // IFDS must run before VASCO since VASCO depends on IFDS as pre-analysis
    runIFDS()

    instrumentAllDialogCreate()

    writer = new ConstraintWriter(outputPath)

    for (service <- xmlParser.getServices.asScala) {
      if (service != null) {
        val curCls = Scene.v.getSootClassUnsafe(service)
        if (curCls != null && curCls.isConcrete) {
          writer.writeConstraint(ConstraintWriter.Constraint.serviceClass, curCls)
          val names = curCls.getName.split("\\.")
          writer.writeConstraint(ConstraintWriter.Constraint.serviceClassLastName, curCls, names.last)
        }
      }
    }

    // Write some constraints and prepare code for VASCO analysis
    for (handler <- allHandlers) {
      analyzeAnyHandlerPreVasco(handler)
    }

    for (activity <- allActivities) {
      analyzeActivityPreVasco(activity)
    }

    runVASCO()

    // Write more constraints
    writeConstraintsPostVASCO()

    // Dump abstractions
    if (debugMode) {
      val printWriter = new PrintWriter("/tmp/abstractions.txt")
      for (m <- analyzedMethods) {
        printWriter.println("====== Method " + m.getSignature + " =======")
        printWriter.println(m.getActiveBody)
        for (unit <- m.getActiveBody.getUnits.asScala) {
          val abstractions = ifdsSolver.ifdsResultsAt(unit)
          val aftDomain = vascoSolution.getValueAfter(unit)
          if ((abstractions != null && abstractions.size() > 0) || (aftDomain != null && aftDomain.nonEmpty)) {
            if (abstractions != null && abstractions.size() > 0) {
              for (value <- abstractions.asScala) {
                for (abstraction <- value._2) {
                  printWriter.println("\t\t" + value._1 + ": " + abstraction)
                }
              }
            }

            printWriter.println("\tUnit: " + unit)
            if (aftDomain != null && aftDomain.nonEmpty) {
              printWriter.println("AFTDomain: ")
              printWriter.println(aftDomain)
            }

            printWriter.println()
          }
        }
      }
      printWriter.close()
    }
  }

  def getActivityHandlers(activity: SootClass): Map[SootMethod, String] = {
    Constants.activityHandlerSubsigs.flatMap { case (sig, event) =>
      val c = hier.matchForVirtualDispatch(sig, activity)
      if (c != null && c.isApplicationClass) {
        Some((c.getMethod(sig), event))
      } else {
        None
      }
    }
  }

  def locateStmt(sootMethod: SootMethod, stmt: Stmt): String = {
    val lines = mutable.ArrayBuffer[String]()

    for (unit <- sootMethod.getActiveBody.getUnits.asScala) {
      if (unit != stmt) {
        lines.addOne(unit.toString)
      } else {
        lines.addOne("************************")
        lines.addOne(unit.toString)
        lines.addOne("************************")
      }
    }
    lines.mkString("\n")
  }

  // NOTE: a type-safe way to prevent missing run All to configure heap transfer
  private val startWindowStmts: mutable.Set[Stmt] = mutable.Set()

  def isStartWindowStmt(stmt: Stmt): Boolean = startWindowStmts.contains(stmt)
}
