package com.research.nomad.markii

import com.research.nomad.markii.dataflow.AbsNode.ViewNode
import presto.android.MethodNames
import soot.jimple.internal.JEqExpr
import soot.{IntType, Local, Modifier, RefType, Scene, SootClass, SootMethod, UnitPatchingChain, Value, VoidType}
import soot.jimple.{AssignStmt, InstanceInvokeExpr, IntConstant, Jimple, NullConstant, Stmt}
import soot.jimple.toolkits.callgraph.Edge

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/* Created at 3/25/20 by zhen */
// TODO: combine this with FlowDroid's hardness -- which is better
object DynamicCFG {
  private var utilsBaseClass: SootClass = null
  def getUtilsBaseClass: SootClass = {
    if (utilsBaseClass == null) {
      utilsBaseClass = new SootClass("markii.Utils", Modifier.PUBLIC)
      utilsBaseClass.setSuperclass(Scene.v.getSootClass("java.lang.Object"))
      Scene.v().addClass(utilsBaseClass)
    }
    utilsBaseClass
  }

  private val dialogRunners = mutable.Map[Stmt, Runner]()
  private val dialogCreateRunners = mutable.Map[SootMethod, (Runner, Stmt)]()

  def getRunnerOfDialog(ownerClass: SootClass, createMethod: SootMethod, arg: Value): Option[(Runner, Stmt)] = {
    if (!dialogCreateRunners.contains(createMethod)) {
      val method = new SootMethod("run_dialog_" + createMethod.hashCode(), List().asJava, VoidType.v, Modifier.PUBLIC)
      ownerClass.addMethod(method)

      val body = Jimple.v().newBody(method)
      method.setActiveBody(body)
      val units = body.getUnits

      val thisLocal = Jimple.v().newLocal("t0", ownerClass.getType)
      body.getLocals.add(thisLocal)
      units.add(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newThisRef(ownerClass.getType)))

      val dialogType = createMethod.getReturnType
      val dialogLocal = Jimple.v().newLocal("dialog", dialogType)
      body.getLocals.add(dialogLocal)
      val defStmt = Jimple.v().newAssignStmt(dialogLocal, Jimple.v().newVirtualInvokeExpr(thisLocal, createMethod.makeRef(), arg))
      units.add(defStmt)
      Scene.v().getCallGraph.addEdge(new Edge(method, defStmt, createMethod))

      val loopExit = Jimple.v().newNopStmt()
      units.add(loopExit)
      val gotoStmt = Jimple.v().newGotoStmt(loopExit)
      units.add(gotoStmt)

      dialogCreateRunners.put(createMethod, (Runner(method, loopExit, dialogLocal), defStmt))
    }
    dialogCreateRunners.get(createMethod)
  }

  def getRunnerOfDialog(defStmt: Stmt): Option[Runner] = {
    if (!dialogRunners.contains(defStmt)) {
      val rhsTypeOption = defStmt match {
        case assignStmt: AssignStmt => Some(assignStmt.getRightOp.getType)
        case _ => None
      }
      if (rhsTypeOption.isEmpty) return None
      val rhsType = rhsTypeOption.get
      val method = new SootMethod("run_dialog_" + defStmt.hashCode(),
        List(rhsType).asJava, VoidType.v, Modifier.PUBLIC | Modifier.STATIC)
      getUtilsBaseClass.addMethod(method)
      val body = Jimple.v().newBody(method)
      method.setActiveBody(body)
      val units = body.getUnits

      val dialogLocal = Jimple.v().newLocal("dialog", rhsType)
      body.getLocals.add(dialogLocal)
      units.add(Jimple.v().newIdentityStmt(dialogLocal, Jimple.v().newParameterRef(rhsType, 0)))

      val loopExit = Jimple.v().newNopStmt()
      units.add(loopExit)
      val gotoStmt = Jimple.v().newGotoStmt(loopExit)
      units.add(gotoStmt)

      dialogRunners.put(defStmt, Runner(method, loopExit, dialogLocal))
    }
    Some(dialogRunners(defStmt))
  }

  private val runners = mutable.Map[SootClass, Runner]()

  private def addMethod(subSig: String, activity: SootClass, thisLocal: Local, method: SootMethod, units: UnitPatchingChain): Unit = {
    val m = activity.getMethodUnsafe(subSig)
    if (m != null) {
      val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(thisLocal, m.makeRef()))
      units.add(invocation)
      Scene.v().getCallGraph.addEdge(new Edge(method, invocation, m))
    }
  }

  def getRunner(activity: SootClass): Option[Runner] = {
    if (!runners.contains(activity)) {
      val onCreate = activity.getMethodUnsafe(MethodNames.onActivityCreateSubSig)
      if (onCreate == null) {
        return None
      }
      val method = new SootMethod(Constants.runnerMethodName, List().asJava, VoidType.v, Modifier.PUBLIC)
      activity.addMethod(method)
      val body = Jimple.v().newBody(method)
      method.setActiveBody(body)
      val units = body.getUnits

      val thisLocal = Jimple.v().newLocal("t0", activity.getType)
      body.getLocals.add(thisLocal)

      units.add(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newThisRef(activity.getType)))

      addMethod("void <init>()", activity, thisLocal, method, units)

      // Add onCreate
      {
        val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(thisLocal, onCreate.makeRef(), NullConstant.v()))
        units.add(invocation)
        Scene.v().getCallGraph.addEdge(new Edge(method, invocation, onCreate))
      }

      addMethod(MethodNames.onActivityStartSubSig, activity, thisLocal, method, units)
      addMethod(MethodNames.onActivityResumeSubSig, activity, thisLocal, method, units)
      addMethod(MethodNames.onActivityResultSubSig, activity, thisLocal, method, units)

      val loopExit = Jimple.v().newNopStmt()
      units.add(loopExit)
      val gotoStmt = Jimple.v().newGotoStmt(loopExit)
      units.add(gotoStmt)

      runners.put(activity, Runner(method, loopExit, thisLocal))
    }
    Some(runners(activity))
  }

  private val runAllMethods = mutable.Map[Stmt, SootMethod]()

  /**
   * FIXME: some repetition
   * @param stmt
   * @param methods
   * @param ctxMethod
   * @return
   */
  def getRunAllDialog(stmt: Stmt, methods: Iterable[SootMethod], ctxMethod: SootMethod): SootMethod = {
    if (!runAllMethods.contains(stmt)) {
      val base = stmt.getInvokeExpr.asInstanceOf[InstanceInvokeExpr].getBase
      val runAllMethod = new SootMethod("runAll_" + stmt.hashCode(),
        List(base.getType).asJava, VoidType.v, Modifier.PUBLIC | Modifier.STATIC)
      getUtilsBaseClass.addMethod(runAllMethod)
      val body = Jimple.v().newBody(runAllMethod)
      runAllMethod.setActiveBody(body)
      val units = body.getUnits

      val dialogLocal = Jimple.v().newLocal("dialog", base.getType)
      body.getLocals.add(dialogLocal)

      units.add(Jimple.v().newIdentityStmt(dialogLocal, Jimple.v().newParameterRef(base.getType, 0)))

      val invocations = mutable.Map[Stmt, Stmt]()
      for (m <- methods) {
        val invocation = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(m.makeRef(), dialogLocal))
        Scene.v().getCallGraph.addEdge(new Edge(runAllMethod, invocation, m))
        val invocationNoOp = Jimple.v().newNopStmt()
        invocations.put(invocationNoOp, invocation)
        units.add(Jimple.v().newIfStmt(new JEqExpr(IntConstant.v(0), IntConstant.v(0)), invocationNoOp))
      }

      // Add a loop at the end to prevent normal control flow exit
      val loopExit = Jimple.v().newNopStmt()
      val gotoStmt = Jimple.v().newGotoStmt(loopExit)

      units.add(Jimple.v().newGotoStmt(loopExit))
      for ((noOp, invocation) <- invocations) {
        units.add(noOp)
        units.add(invocation)
        units.add(Jimple.v().newGotoStmt(loopExit))
      }

      units.add(loopExit)
      units.add(gotoStmt)

      runAllMethods.put(stmt, runAllMethod)
    }
    runAllMethods(stmt)
  }

  def getRunAll(stmt: Stmt, methods: Iterable[SootMethod], baseClass: SootClass, replaced: SootMethod): SootMethod = {
    if (!runAllMethods.contains(stmt)) {
      val runAllMethod = new SootMethod("runAll_" + stmt.hashCode(),
        replaced.getParameterTypes, VoidType.v, Modifier.PUBLIC)
      baseClass.addMethod(runAllMethod)
      val body = Jimple.v().newBody(runAllMethod)
      runAllMethod.setActiveBody(body)
      val units = body.getUnits

      val thisLocal = Jimple.v().newLocal("t0", replaced.getDeclaringClass.getType)
      val arg = Jimple.v().newLocal("l0", replaced.getParameterTypes.get(0))
      body.getLocals.add(arg)
      body.getLocals.add(thisLocal)

      units.add(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newThisRef(replaced.getDeclaringClass.getType)))
      units.add(Jimple.v().newIdentityStmt(arg, Jimple.v().newParameterRef(replaced.getParameterType(0), 0)))

      val invocations = mutable.Map[Stmt, Stmt]()
      for ((m, i) <- methods.zipWithIndex) {
        val local = Jimple.v().newLocal("base_" + i, RefType.v(m.getDeclaringClass))
        body.getLocals.add(local)
        val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(local, m.makeRef()))
        Scene.v().getCallGraph.addEdge(new Edge(runAllMethod, invocation, m))
        val invocationNoOp = Jimple.v().newNopStmt()
        invocations.put(invocationNoOp, invocation)
        units.add(Jimple.v().newIfStmt(new JEqExpr(IntConstant.v(0), IntConstant.v(0)), invocationNoOp))
      }

      // Add a loop at the end to prevent normal control flow exit
      val loopExit = Jimple.v().newNopStmt()
      val gotoStmt = Jimple.v().newGotoStmt(loopExit)

      units.add(Jimple.v().newGotoStmt(loopExit))
      for ((noOp, invocation) <- invocations) {
        units.add(noOp)
        units.add(invocation)
        units.add(Jimple.v().newGotoStmt(loopExit))
      }

      units.add(loopExit)
      units.add(gotoStmt)

      runAllMethods.put(stmt, runAllMethod)
    }
    runAllMethods(stmt)
  }

  def addActivityHandlerToEventLoop(ownerActivity: SootClass, handler: SootMethod): Unit = {
    addHandlerToEventLoopAct(ownerActivity, handler, runner => {
      val units = runner.method.getActiveBody.getUnits
      val arguments = handler.getParameterTypes.asScala.map {
        case _: RefType => NullConstant.v()
        case _: IntType => IntConstant.v(0)
      }
      val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(runner.view, handler.makeRef(), arguments.asJava))
      Scene.v().getCallGraph.addEdge(new Edge(runner.method, invocation, handler))
      units.insertAfter(invocation, runner.loopExit)
      invocation
    })
  }

  // FIXME: this should not be part of the VASCO loop
  /**
   * @param ownerActivity
   * @param handler
   * @return If None, nothing changed, so no need to work on the return value
   */
  def addViewHandlerToEventLoopAct(ownerActivity: SootClass, handler: SootMethod): Option[(SootMethod, Stmt)] = {
    addHandlerToEventLoopAct(ownerActivity, handler, runner => {
      val units = runner.method.getActiveBody.getUnits
      // NOTE: there is no object for listener
      val base = Jimple.v().newLocal("listener", RefType.v(handler.getDeclaringClass))
      runner.method.getActiveBody.getLocals.add(base)
      val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(base, handler.makeRef(), NullConstant.v()))
      Scene.v().getCallGraph.addEdge(new Edge(runner.method, invocation, handler))
      units.insertAfter(invocation, runner.loopExit)
      invocation
    }) match {
      case Some((method, stmt, changed)) =>
        if (changed) {
          Some((method, stmt))
        } else {
          None
        }
      case None => None
    }
  }

  // FIXME: this should not be part of the VASCO loop
  /**
   * @param ownerDialog
   * @param handler
   * @return If None, nothing changed, so no need to work on the return value
   */
  def addViewHandlerToEventLoopDialog(ownerDialog: ViewNode, handler: SootMethod): Option[(SootMethod, Stmt)] = {
    addHandlerToEventLoopDialog(ownerDialog, handler, runner => {
      val units = runner.method.getActiveBody.getUnits
      // NOTE: there is no object for listener
      val base = Jimple.v().newLocal("listener", RefType.v(handler.getDeclaringClass))
      runner.method.getActiveBody.getLocals.add(base)
      val invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(base, handler.makeRef(), NullConstant.v()))
      Scene.v().getCallGraph.addEdge(new Edge(runner.method, invocation, handler))
      units.insertAfter(invocation, runner.loopExit)
      invocation
    }) match {
      case Some((method, stmt, changed)) =>
        if (changed) {
          Some((method, stmt))
        } else {
          None
        }
      case None => None
    }
  }

  private val addedHandlers = mutable.Map[SootClass, mutable.Map[SootMethod, Stmt]]()

  /**
   * From def/alloc site to method-invocation map
   */
  private val addedHandlersDialog = mutable.Map[Stmt, mutable.Map[SootMethod, Stmt]]()

  private def addHandlerToEventLoopAct(ownerActivity: SootClass, handler: SootMethod,
                                       createInvocation: Runner => Stmt): Option[(SootMethod, Stmt, Boolean)] = {
    DynamicCFG.getRunner(ownerActivity) match {
      case Some(runner) =>
        var changed = false
        if (!addedHandlers.contains(ownerActivity)) {
          addedHandlers.put(ownerActivity, mutable.Map())
        }
        if (!addedHandlers(ownerActivity).contains(handler)) {
          addedHandlers(ownerActivity).put(handler, createInvocation(runner))
          changed = true
        }
        Some((runner.method, addedHandlers(ownerActivity)(handler), changed))
      case None => None
    }
  }

  private def addHandlerToEventLoopDialog(ownerDialog: ViewNode, handler: SootMethod,
                                          createInvocation: Runner => Stmt): Option[(SootMethod, Stmt, Boolean)] = {
    val defStmt = ownerDialog.allocSite
    DynamicCFG.getRunnerOfDialog(defStmt) match {
      case Some(runner) =>
        var changed = false
        if (!addedHandlersDialog.contains(defStmt)) {
          addedHandlersDialog.put(defStmt, mutable.Map())
        }
        if (!addedHandlersDialog(defStmt).contains(handler)) {
          addedHandlersDialog(defStmt).put(handler, createInvocation(runner))
          changed = true
        }
        Some((runner.method, addedHandlersDialog(defStmt)(handler), changed))
      case None => None
    }
  }
}
