package com.research.nomad.markii.dataflow

import java.time.Instant

import com.research.nomad.markii.dataflow.AbsNode.{ActNode, ListenerNode, ViewNode}
import com.research.nomad.markii.{Constants, DynamicCFG, GUIAnalysis}
import io.github.izgzhen.msbase.{IOUtil, JsonUtil}
import presto.android.gui.listener.EventType

import scala.jdk.CollectionConverters._
import presto.android.Hierarchy
import soot.jimple.internal.{JIdentityStmt, JimpleLocal}
import soot.jimple.{AssignStmt, CastExpr, InstanceFieldRef, InstanceInvokeExpr, IntConstant, InvokeExpr, NewExpr, ReturnStmt, StaticFieldRef, Stmt, ThisRef}
import soot.{Local, RefType, Scene, SootClass, SootMethod, UnitPatchingChain, Value}
import vasco.{Context, ForwardInterProceduralAnalysis, ProgramRepresentation}

import scala.collection.mutable

class AbstractValuePropVasco(entryPoints: List[SootMethod])
  extends ForwardInterProceduralAnalysis[SootMethod, soot.Unit, AFTDomain] {

  type Domain = AFTDomain
  type DomainContext = Context[SootMethod, soot.Unit, Domain]

  private val RETURN_LOCAL = new JimpleLocal("@return", null)
  private val hier = Hierarchy.v()

  private def setDialogButtonListener(buttonType: DialogButtonType.Value, invokeExpr: InvokeExpr, d: AFTDomain,
                                      ctxMethod: SootMethod, stmt: Stmt): AFTDomain = {
    invokeExpr.getArgs.asScala.map(_.getType).collectFirst {
      case refType: RefType if Constants.isDialogOnClickListener(refType.getSootClass) => refType.getSootClass
    } match {
      case Some(listener) =>
        val handler = listener.getMethodByNameUnsafe("onClick")
        if (handler != null) {
          val dialogBuilder = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
          d.setDialogHandler(ctxMethod, stmt, dialogBuilder, handler, Some(buttonType))
        } else {
          d
        }
      case _ => d
    }
  }

  def assigned(ctxMethod: SootMethod, assignStmt: Stmt, ref: Ref,
               value: Value, ctx: Domain, input: Domain): Domain = {
    val killed = input.killRef(ref)
    value match {
      case castExpr: CastExpr =>
        assigned(ctxMethod, assignStmt, ref, castExpr.getOp, ctx, input)
      case newExpr: NewExpr =>
        val sootClass = newExpr.getBaseType.getSootClass
        if (hier.isSubclassOf(sootClass, Scene.v().getSootClass(Constants.androidViewClassName))) {
          killed.newView(ref, sootClass, assignStmt)
        } else if (Constants.isDialogBuilderClass(sootClass)) {
          killed.newView(ref, sootClass, assignStmt)
        } else if (Constants.isDialogClass(sootClass)) {
          killed.newView(ref, sootClass, assignStmt)
        } else if (Constants.isViewEventListenerClass(sootClass)) {
          killed.newListener(ref, sootClass)
        } else if (hier.isSubclassOf(sootClass, Constants.layoutParamsClass)) {
          killed.newLayoutParams(ref)
        } else {
          killed
        }
      case rhsLocal: Local =>
        ctx.getAccessPath(rhsLocal) match {
          case Some(accessPath) =>
            killed.withSubPath(ref, accessPath)
          case None => killed
        }
      case instanceFieldRef: InstanceFieldRef =>
        val rhsLocalRef = Ref.from(instanceFieldRef)
        input.getAccessPath(rhsLocalRef) match {
          case Some(accessPath) =>
            killed.withSubPath(ref, accessPath)
          case None => killed
        }
      case staticFieldRef: StaticFieldRef =>
        val rhsLocalRef = Ref.from(staticFieldRef)
        input.getAccessPath(rhsLocalRef) match {
          case Some(accessPath) =>
            killed.withSubPath(ref, accessPath)
          case None => killed
        }
      case invokeExpr: InstanceInvokeExpr =>
        val signature = invokeExpr.getMethod.getSignature
        if (Constants.isActivityFindViewById(invokeExpr.getMethod)) {
          invokeExpr.getArg(0) match {
            case intConstant: IntConstant =>
              val activityBase = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase
              // FIXME: over-approx of activity instances
              val activityClass = activityBase.getType.asInstanceOf[RefType].getSootClass
              val subViews = mutable.Set[ViewNode]()
              val id = intConstant.value
              ctx.activityRootViewMap.get(activityClass) match {
                case Some(rootViews) =>
                  for (viewNode <- rootViews) {
                    subViews.addAll(ctx.findViewById(viewNode, id))
                  }
                case _ =>
              }
              return killed.withNodes(ref, subViews.toSet)
            case _ =>
          }
        }
        if (Constants.isDialogFindViewById(invokeExpr.getMethod) || Constants.isViewFindViewById(invokeExpr.getMethod)) {
          invokeExpr.getArg(0) match {
            case intConstant: IntConstant =>
              val viewLocal = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
              val subViews = mutable.Set[ViewNode]()
              val id = intConstant.value
              for (viewNode <- ctx.getViewNodes(ctxMethod, assignStmt, viewLocal)) {
                subViews.addAll(ctx.findViewById(viewNode, id))
              }
              return killed.withNodes(ref, subViews.toSet)
            case _ =>
          }
        }
        if (invokeExpr.getMethod.getName == "findViewById") {
          println("Unhandled signature: " + signature)
        }
        if (Constants.isDialogBuilderCreate(signature)) {
          val builder = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
          return killed.dialogCreate(
            ref, sootClass = invokeExpr.getType.asInstanceOf[RefType].getSootClass,
            assignStmt, builder, ctxMethod)
        }
        val subSig = invokeExpr.getMethod.getSubSignature
        val setButtionType = Constants.fromDialogBuilderSetButton(subSig)
        if (setButtionType.nonEmpty) {
          setDialogButtonListener(setButtionType.get, invokeExpr, input, ctxMethod, assignStmt)
        } else if (Constants.isDialogBuilderSetAny(subSig)) {
          input // Don't kill for other builder setters
        } else if (signature == "<android.view.LayoutInflater: android.view.View inflate(int,android.view.ViewGroup,boolean)>") {
          // FIXME: other inflate signatures
          val intConstant = invokeExpr.getArg(0).asInstanceOf[IntConstant]
          invokeExpr.getArg(1) match {
            case parentView: Local =>
              val attachToRoot = invokeExpr.getArg(2).asInstanceOf[IntConstant].value != 0
              killed.inflate(ctxMethod, assignStmt, intConstant.value, ref, parentView, attachToRoot)
            case _ => killed
          }
        } else {
          killed
        }
      case _ => killed
    }
  }

  private def getUnitIndex(units: UnitPatchingChain, u: soot.Unit): Int = {
    units.asScala.view.toSeq.indexOf(u)
  }

//  private var maxLog = Some(100)
  private var maxLog: Option[Int] = None
  private var logStateCounter = 0
  private val logStateInterval = 1000

  private def logState(method: SootMethod, unit: soot.Unit, d: Domain): Unit = {
//    println(method, unit, d.sizeSummary)
    if (!GUIAnalysis.debugMode) return
    if (logStateCounter == logStateInterval) {
      logStateCounter = 0
      println(s"worklist size: ${Instant.now.getEpochSecond}: ${worklist.size()}")
    } else {
      logStateCounter += 1
    }
    if (method.getSignature != "<xvideo.furbie.ro.AsyncListActivity: void gopro()>") {
      return
    }
    if (maxLog.nonEmpty) {
      if (maxLog.get < 0) {
        throw new RuntimeException("Logging ends")
      } else {
        maxLog = Some(maxLog.get - 1)
      }
    }
    val idx = getUnitIndex(method.getActiveBody.getUnits, unit)
    val obj = Map(
      "method" -> method.getSignature,
      "unit" -> unit.toString(),
      "unitIdx" -> idx,
      "domain" -> d.toJSONObj,
      "domainSize" -> d.sizeSummary
    )
    val json = JsonUtil.toJson(obj)
    IOUtil.write(json, "/tmp/markii-debug/" + method.getSignature + "@" + idx + ".json")
  }

  override def normalFlowFunction(context: DomainContext, unit: soot.Unit, d: Domain): Domain = {
    logState(context.getMethod, unit, d)
    unit match {
      case assignStmt: AssignStmt =>
        val ref = Ref.from(assignStmt.getLeftOp)
        return assigned(context.getMethod, assignStmt, ref, assignStmt.getRightOp, d, d)
      case stmt: JIdentityStmt =>
        val methodName = context.getMethod.getName
        val methodClass = context.getMethod.getDeclaringClass
        if (stmt.getRightOp.isInstanceOf[ThisRef] && Constants.isActivity(methodClass) && methodName == Constants.runnerMethodName) {
          // HACK: This is a HACK
          val localRef = Ref.from(stmt.getLeftOp)
          val killed = d.killRef(localRef)
          return killed.newAct(localRef, methodClass)
        }
      case retStmt: ReturnStmt =>
        return assigned(context.getMethod, retStmt, Ref.LocalRef(RETURN_LOCAL), retStmt.getOp, d, d)
      case _ =>
    }
    d
  }

  // <com.appbrain.AppBrainActivity: boolean onKeyDown(int,android.view.KeyEvent)> is not entered, taken as a local call instead
  // APK: examples/frauddroid-autumn.apk
  override def callEntryFlowFunction(context: DomainContext, m: SootMethod, callSite: soot.Unit, d: Domain): Domain = {
    val stmt = callSite.asInstanceOf[Stmt]
    val invokeExpr = stmt.getInvokeExpr
    var ret = if (GUIAnalysis.isStartWindowStmt(callSite.asInstanceOf[Stmt])) {
      topValue()
    } else {
      d.getHeap
    }
    if (m.hasActiveBody) {
      for (i <- 0 until invokeExpr.getArgCount) {
        ret = assigned(context.getMethod, stmt, Ref.LocalRef(m.getActiveBody.getParameterLocal(i)), invokeExpr.getArg(i), d, ret)
      }
      invokeExpr match {
        case instanceInvokeExpr: InstanceInvokeExpr =>
          ret = assigned(context.getMethod, stmt, Ref.LocalRef(m.getActiveBody.getThisLocal), instanceInvokeExpr.getBase, d, ret)
        case _ =>
      }
    }
    ret
  }

  // ???: How is the exit flow joined with the flow in the caller context? How is the caller context updated?
  override def callExitFlowFunction(context: DomainContext, m: SootMethod, unit: soot.Unit, d: Domain): Domain = {
    var ret = d.getHeap
    unit match {
      case assignStmt: AssignStmt =>
        ret = assigned(context.getMethod, assignStmt, Ref.LocalRef(assignStmt.getLeftOp.asInstanceOf[Local]), RETURN_LOCAL, d, ret)
      case _ =>
    }
    unit.asInstanceOf[Stmt].getInvokeExpr match {
      case instanceInvokeExpr: InstanceInvokeExpr =>
        val base = instanceInvokeExpr.getBase.asInstanceOf[Local]
        if (instanceInvokeExpr.getMethod.hasActiveBody) {
          val thisLocal = instanceInvokeExpr.getMethod.getActiveBody.getThisLocal
          ret = assigned(context.getMethod, unit.asInstanceOf[Stmt], Ref.LocalRef(base), thisLocal, d, ret)
        }
      case _ =>
    }
    ret
  }

  def setEventListener(d: AFTDomain, context: DomainContext, invokeExpr: InstanceInvokeExpr,
                       stmt: Stmt, handlerSubSig: String, eventType: EventType): AFTDomain = {
    val viewBase = invokeExpr.getBase.asInstanceOf[Local]
    var listeners: Set[SootClass] = invokeExpr.getArg(0) match {
      case local: Local =>
        d.getNodes(context.getMethod, stmt, local).collect {
          // FIXME: sometimes, the listener returned from here is not valid
          case ListenerNode(listener) => listener
        }.toSet
      case _ => Set()
    }
    invokeExpr.getArg(0).getType match {
      case refType: RefType =>
        listeners = listeners + refType.getSootClass
      case _ =>
    }
    val handlers = listeners.map(cls => {
      cls.getMethodUnsafe(handlerSubSig)
    }).filter(m => m != null && m.isConcrete && m.hasActiveBody)
    for (handler <- handlers) {
      for (dialogNode <- d.getOwnerDialogs(context.getMethod, stmt, viewBase)) {
        DynamicCFG.addViewHandlerToEventLoopDialog(dialogNode, handler) match {
          case Some((runner, invocation)) =>
            aftProgramRepresentation.refreshCallgraph(runner)
            val callers = getCallers(context)
            if (callers != null) {
              for (caller <- callers.asScala) {
                caller.getCallingContext.setValueBefore(invocation, topValue())
                caller.getCallingContext.setValueAfter(invocation, topValue())
                initContext(runner, topValue())
              }
            }
          case None =>
        }
      }

      for (ownerActivity <- d.getOwnerActivities(context.getMethod, stmt, viewBase)) {
        // FIXME: continueButton has no owner activities
        // FIXME: adding handler invocation might make some context missing nodes when joining over feasible
        //        paths
        DynamicCFG.addViewHandlerToEventLoopAct(ownerActivity, handler) match {
          case Some((runner, invocation)) =>
            aftProgramRepresentation.refreshCallgraph(runner)
            for (runnerContext <- getContexts(runner).asScala) {
              runnerContext.setValueBefore(invocation, topValue())
              runnerContext.setValueAfter(invocation, topValue())
            }
            initContext(runner, topValue())
          case None =>
        }
      }
    }
    d.setHandlers(context.getMethod, stmt, viewBase, eventType, handlers)
  }

  override def callLocalFlowFunction(context: DomainContext, unit: soot.Unit, d: Domain): Domain = {
    logState(context.getMethod, unit, d)
    unit match {
      case assignStmt: AssignStmt =>
        val ref = Ref.from(assignStmt.getLeftOp)
        assigned(context.getMethod, assignStmt, ref, assignStmt.getRightOp, d, d)
      case _ =>
        val stmt = unit.asInstanceOf[Stmt]
        if (stmt.containsInvokeExpr()) {
          val invokeExpr = stmt.getInvokeExpr
          if (invokeExpr.getMethod.getName == "addView" &&
            invokeExpr.getMethod.getDeclaringClass.getName == "android.view.ViewGroup") {
            val parentView = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase
            val childView = invokeExpr.getArg(0)
            return d.withEdge(context.getMethod, stmt, parentView.asInstanceOf[Local], childView.asInstanceOf[Local])
          }
          val subSig = invokeExpr.getMethod.getSubSignature
          val setButtionType = Constants.fromDialogBuilderSetButton(subSig)
          if (setButtionType.nonEmpty) {
            return setDialogButtonListener(setButtionType.get, invokeExpr, d, context.getMethod, stmt)
          }
          if (Constants.isDialogSetButton(invokeExpr.getMethod.getSignature)) {
            invokeExpr.getArgs.asScala.map(_.getType).collectFirst {
              case refType: RefType if Constants.isDialogOnClickListener(refType.getSootClass) => refType.getSootClass
            } match {
              case Some(listener) =>
                val handler = listener.getMethodByNameUnsafe("onClick")
                if (handler != null) {
                  val dialogBuilder = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
                  return d.setDialogHandler(context.getMethod, stmt, dialogBuilder, handler, None)
                }
              case _ =>
            }
          }
          if (invokeExpr.getMethod.getSubSignature == "void setOnClickListener(android.view.View$OnClickListener)") {
            return setEventListener(d, context, invokeExpr.asInstanceOf[InstanceInvokeExpr], stmt, "void onClick(android.view.View)", EventType.click)
          }
          if (invokeExpr.getMethod.getSubSignature == "void setOnTouchListener(android.view.View$OnTouchListener)") {
            return setEventListener(d, context, invokeExpr.asInstanceOf[InstanceInvokeExpr], stmt, "boolean onTouch(android.view.View,android.view.MotionEvent)", EventType.touch)
          }
          if (invokeExpr.getMethod.getSignature == "<android.view.View: void setId(int)>") {
            val dialogBase = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
            invokeExpr.getArg(0) match {
              case intConstant: IntConstant => return d.setId(context.getMethod, dialogBase, stmt, intConstant.value)
              case _ =>
            }
          }
          if (invokeExpr.getMethod.getName == "setLayoutParams") {
            if (invokeExpr.getMethod.getSignature == "<android.view.View: void setLayoutParams(android.view.ViewGroup$LayoutParams)>") {
              val viewBase = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
              val paramsLocal = invokeExpr.getArg(0).asInstanceOf[Local]
              return d.setLayoutParams(context.getMethod, stmt, viewBase, paramsLocal)
            }
            println("Unhandled: " + invokeExpr.getMethod.getSignature)
          }
          // FIXME: improve precision, handle View argument
          // FIMXE: sub-class won't get the correct predicat here
          if (Constants.isActivitySetContentViewWithInt(invokeExpr.getMethod)) {
            invokeExpr.getArg(0) match {
              case intConstant: IntConstant => return setContentView(d, context.getMethod, invokeExpr, stmt, Left(intConstant.value))
              case _ =>
            }
          }
          if (Constants.isActivitySetContentViewWithView(invokeExpr.getMethod)) {
            return setContentView(d, context.getMethod, invokeExpr, stmt, Right(invokeExpr.getArg(0).asInstanceOf[Local]))
          }
          if (Constants.isDialogSetContentViewWithInt(invokeExpr.getMethod)) {
            val dialogBase = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
            invokeExpr.getArg(0) match {
              case intConstant: IntConstant =>
                return d.setContentViewDialog(context.getMethod, stmt, dialogBase, intConstant.value)
              case _ =>
            }
          }
          if (Constants.isDialogSetContentViewWithView(invokeExpr.getMethod)) {
            val dialogBase = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
            return d.setContentViewDialog(context.getMethod, stmt, dialogBase, invokeExpr.getArg(0).asInstanceOf[Local])
          }
          if (hier.isSubclassOf(invokeExpr.getMethod.getDeclaringClass, Constants.layoutParamsClass)) {
            if (invokeExpr.getMethod.getName == "<init>") {
              val paramsBase = invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local]
              if (invokeExpr.getMethod.getSubSignature == "void <init>(int,int)") {
                return d.initLayoutParams(context.getMethod, stmt, paramsBase, invokeExpr.getArg(0), invokeExpr.getArg(1))
              }
              println("Unhandled: " + invokeExpr.getMethod.getSignature)
            }
          }
        }
        d
    }
  }

  private def setContentView(d: AFTDomain, ctxMethod: SootMethod, invokeExpr: InvokeExpr,
                             stmt: Stmt, viewArg: Either[Int, Local]): AFTDomain = {
    val nodes = d.getNodes(ctxMethod, stmt, invokeExpr.asInstanceOf[InstanceInvokeExpr].getBase.asInstanceOf[Local])
    var output = d
    for (node <- nodes) {
      node match {
        case ActNode(sootClass) =>
          viewArg match {
            case Left(id) => output = output.setContentViewAct(stmt, sootClass, id)
            case Right(viewLocal) => output = output.setContentViewAct(ctxMethod, stmt, sootClass, viewLocal)
          }
        case _ => // TODO: WebView etc.
      }
    }
    output
  }

  override def boundaryValue(m: SootMethod): Domain = topValue()

  override def copy(d: Domain): Domain = d

  override def meet(d1: Domain, d2: Domain): Domain = d1.meet(d2)

  private val aftProgramRepresentation = new AFTProgramRepresentation(entryPoints)
  override def programRepresentation(): ProgramRepresentation[SootMethod, soot.Unit] = aftProgramRepresentation

  override def topValue(): Domain = AFTDomain.top
}
