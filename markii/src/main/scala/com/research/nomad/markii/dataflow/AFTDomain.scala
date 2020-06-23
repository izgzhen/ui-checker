package com.research.nomad.markii.dataflow

import com.research.nomad.markii.{Constants, GUIAnalysis}
import com.research.nomad.markii.dataflow.AbsNode.{ActNode, LayoutParamsNode, ListenerNode, ViewNode}
import presto.android.gui.listener.EventType
import presto.android.xml.AndroidView
import soot.jimple.{IntConstant, Stmt}
import soot.{Local, SootClass, SootMethod, Value}

import scala.jdk.CollectionConverters._

object DialogButtonType extends Enumeration {
  type DialogButtonType = Value
  val POSITIVE, NEGATIVE, NEUTRAL = Value
}

/**
 * UPDATE STRUCTURAL METHODS when the data structure is changed
 */
case class AFTDomain(private val localNodeMap: Map[Local, AccessPath[AbsNode]],
                     private val globalNodeMap: Map[SootClass, AccessPath[AbsNode]],
                     // FIXME: consider aliasing carefully when writing to localNodeMap
                     nodeEdgeMap: Map[ViewNode, Set[ViewNode]],
                     nodeEdgeRevMap: Map[ViewNode, Set[ViewNode]],
                     nodeHandlerMap: Map[(ViewNode, EventType), Set[SootMethod]],
                     dialogHandlerMap: Map[(ViewNode, DialogButtonType.Value), Set[SootMethod]],
                     activityRootViewMap: Map[SootClass, Set[ViewNode]]) {
  /**
   * STRUCTURAL METHOD
   */
  def meet(d: AFTDomain): AFTDomain = {
    if (equals(AFTDomain.top)) return d
    if (d.equals(AFTDomain.top)) return this
    if (equals(d)) return this
    AFTDomain(
      AFTDomain.mergeMapOfAccessPath(localNodeMap, d.localNodeMap),
      AFTDomain.mergeMapOfAccessPath(globalNodeMap, d.globalNodeMap),
      AFTDomain.mergeMapOfSets(nodeEdgeMap, d.nodeEdgeMap),
      AFTDomain.mergeMapOfSets(nodeEdgeRevMap, d.nodeEdgeRevMap),
      AFTDomain.mergeMapOfSets(nodeHandlerMap, d.nodeHandlerMap),
      AFTDomain.mergeMapOfSets(dialogHandlerMap, d.dialogHandlerMap),
      AFTDomain.mergeMapOfSets(activityRootViewMap, d.activityRootViewMap))
  }

  /**
   * STRUCTURAL METHOD
   */
  def updateNodes(contextMethod: SootMethod, stmt: Stmt, local: Local, nodeToNode: AbsNode => AbsNode): AFTDomain = {
    copy(localNodeMap = localNodeMap.map { case (l, accessPath) =>
      if (GUIAnalysis.isAlias(local, l, stmt, stmt, contextMethod)) {
        (l, accessPath.updateData(nodeToNode))
      } else {
        (l, accessPath)
      }
    })
  }

  /**
   * STRUCTURAL METHOD
   */
  def mapViewNodes(nodeToNode: ViewNode => ViewNode): AFTDomain = {
    AFTDomain(
      localNodeMap = localNodeMap.view.mapValues(_.updateData {
        case v: ViewNode => nodeToNode(v)
        case n => n
      }).toMap,
      globalNodeMap = globalNodeMap.view.mapValues(_.updateData {
        case v: ViewNode => nodeToNode(v)
        case n => n
      }).toMap,
      nodeEdgeMap = nodeEdgeMap.map { case (key, values) => (nodeToNode(key), values.map(nodeToNode)) },
      nodeEdgeRevMap = nodeEdgeRevMap.map { case (key, values) => (nodeToNode(key), values.map(nodeToNode)) },
      nodeHandlerMap = nodeHandlerMap.map { case ((node, t), handlers) => ((nodeToNode(node), t), handlers) },
      dialogHandlerMap = dialogHandlerMap.map { case ((node, t), handlers) => ((nodeToNode(node), t), handlers) },
      activityRootViewMap = activityRootViewMap.view.mapValues(_.map(nodeToNode)).toMap
    )
  }

  /**
   * STRUCTURAL METHOD
   */
  def sizeSummary: Map[String, Int] =
    Map(
      "localNodeMap" -> localNodeMap.size,
      "globalNodeMap" -> globalNodeMap.size,
      "nodeEdgeMap" -> nodeEdgeMap.values.map(_.size).sum,
      "nodeHandlerMap" -> nodeHandlerMap.size,
      "dialogHandlerMap" -> dialogHandlerMap.size,
      "activityRootViewMap" -> activityRootViewMap.values.map(_.size).sum)

  /**
   * STRUCTURAL METHOD
   */
  def getHeap: AFTDomain = copy(localNodeMap = Map())

  /**
   * STRUCTURAL METHOD
   */
  def nonEmpty: Boolean =
    localNodeMap.nonEmpty || globalNodeMap.nonEmpty || nodeEdgeMap.nonEmpty || nodeEdgeRevMap.nonEmpty ||
      activityRootViewMap.nonEmpty || nodeHandlerMap.nonEmpty || dialogHandlerMap.nonEmpty

  /**
   * STRUCTURAL METHOD
   */
  private def equivTo(domain: AFTDomain): Boolean =
    localNodeMap == domain.localNodeMap &&
      globalNodeMap == domain.globalNodeMap &&
      nodeEdgeMap == domain.nodeEdgeMap &&
      activityRootViewMap == domain.activityRootViewMap &&
      nodeHandlerMap == domain.nodeHandlerMap &&
      dialogHandlerMap == domain.dialogHandlerMap

  override def equals(obj: Any): Boolean = {
    if (obj == null) return false
    if (super.equals(obj)) return true
    obj match {
      case domain: AFTDomain =>
        if (hashCode() == domain.hashCode()) {
          equivTo(domain)
        } else {
          false
        }
      case _ => false
    }
  }

  override def toString: String = sizeSummary.toString

  def getViewNodes(contextMethod: SootMethod, stmt: Stmt, local: Local): Iterable[ViewNode] = {
    getNodes(contextMethod, stmt, local).collect { case v: ViewNode => v }
  }

  def getNodes(contextMethod: SootMethod, stmt: Stmt, local: Local): Iterable[AbsNode] = {
    localNodeMap.flatMap { case (l, accessPath) =>
      if (GUIAnalysis.isAlias(local, l, stmt, stmt, contextMethod)) {
        accessPath.data.getOrElse(Set())
      } else {
        Set()
      }
    }
  }

  def addEdge(parentNode: ViewNode, childNode: ViewNode): AFTDomain = {
    // FIXME: current view-node is over-approximate, thus a wrapper function that creates many nodes at runtime
    //        might be interpreted as returning the same abstract node, if there is no sufficient context info to
    //        differentiate them. Currently, we will not avoid such kind of recursive edges, but will detect them
    //        when making recursive graph traversal
    // assert(parentNode != childNode)
    copy(nodeEdgeMap = AFTDomain.addEdgeToMap(nodeEdgeMap, parentNode, childNode),
      nodeEdgeRevMap = AFTDomain.addEdgeToMap(nodeEdgeRevMap, childNode, parentNode))
  }

  def getOwnerActivities(contextMethod: SootMethod, stmt: Stmt, viewBase: Local): Set[SootClass] =
    getViewNodes(contextMethod, stmt, viewBase).flatMap(getOwnerActivities(contextMethod, stmt, _)).toSet

  def getOwnerDialogs(contextMethod: SootMethod, stmt: Stmt, viewBase: Local): Set[ViewNode] =
    getViewNodes(contextMethod, stmt, viewBase).flatMap(getOwnerDialogs(contextMethod, stmt, _)).toSet

  def getOwnerActivities(contextMethod: SootMethod, stmt: Stmt,
                         viewNode: ViewNode, visited: Set[ViewNode] = Set()): Set[SootClass] = {
    nodeEdgeRevMap.get(viewNode) match {
      case Some(edges) => edges.flatMap(parentNode => if (visited.contains(parentNode)) {
        None
      } else {
        getOwnerActivities(contextMethod, stmt, parentNode, visited + parentNode)
      })
      case None => activityRootViewMap.flatMap { case (clazz, nodes) =>
        if (nodes.contains(viewNode)) { Set(clazz) } else { Set() }
      }.toSet
    }
  }

  def getOwnerDialogs(contextMethod: SootMethod, stmt: Stmt,
                      viewNode: ViewNode, visited: Set[ViewNode] = Set()): Set[ViewNode] = {
    nodeEdgeRevMap.get(viewNode) match {
      case Some(edges) =>
        edges.flatMap(parentNode =>
          if (visited.contains(parentNode)) {
            Set()
          } else {
            getOwnerDialogs(contextMethod, stmt, parentNode, visited + parentNode)
          })
      case None => if (viewNode.sootClass.nonEmpty && Constants.isDialogClass(viewNode.sootClass.get)) {
        Set(viewNode)
      } else {
        Set()
      }
    }
  }

  def getAccessPath(l: Local): Option[AccessPath[AbsNode]] = localNodeMap.get(l)

  def getAccessPath(ref: Ref): Option[AccessPath[AbsNode]] =
    ref match {
      case Ref.GlobalRef(cls, fields) =>
        globalNodeMap.get(cls) match {
          case Some(accessPath) => accessPath.get(fields)
          case None => None
        }
      case Ref.LocalRef(local, fields) =>
        localNodeMap.get(local) match {
          case Some(accessPath) => accessPath.get(fields)
          case None => None
        }
    }

  def withSubPath(ref: Ref, subPath: AccessPath[AbsNode]): AFTDomain = {
    ref match {
      case Ref.GlobalRef(cls, fields) =>
        copy(
          globalNodeMap = globalNodeMap.get(cls) match {
            case None => globalNodeMap + (cls -> AccessPath(None).add(fields, subPath))
            case Some(accessPath) => globalNodeMap + (cls -> accessPath.add(fields, subPath))
          }
        )
      case Ref.LocalRef(local, fields) =>
        copy(
          localNodeMap = localNodeMap.get(local) match {
            case None => localNodeMap + (local -> AccessPath(None).add(fields, subPath))
            case Some(accessPath) => localNodeMap + (local -> accessPath.add(fields, subPath))
          }
        )
    }
  }

  def setContentViewDialog(ctxMethod: SootMethod, stmt: Stmt, dialogLocal: Local, id: Int): AFTDomain = {
    val androidView = GUIAnalysis.xmlParser.findViewById(id)
    val viewNode = ViewNode(stmt, id = Set(id, androidView.getId.toInt), androidView = androidView)
    inflateAFT(stmt, viewNode, androidView).withEdge(ctxMethod, stmt, dialogLocal, viewNode)
  }

  def setContentViewDialog(ctxMethod: SootMethod, stmt: Stmt, dialogLocal: Local, viewLocal: Local): AFTDomain = {
    var d = copy()
    for (viewNode <- getViewNodes(ctxMethod, stmt, viewLocal)) {
      d = d.withEdge(ctxMethod, stmt, dialogLocal, viewNode)
    }
    d
  }

  def setContentViewAct(stmt: Stmt, actClass: SootClass, id: Int): AFTDomain = {
    val view = GUIAnalysis.xmlParser.findViewById(id)
    val viewNode = ViewNode(stmt, id = Set(id, view.getId.toInt), androidView = view)
    inflateAFT(stmt, viewNode, view).copy(
      activityRootViewMap = activityRootViewMap + (actClass -> Set(viewNode))
    )
  }

  def setContentViewAct(ctxMethod: SootMethod, stmt: Stmt, actClass: SootClass, viewLocal: Local): AFTDomain = {
    var activityRootViewMap2 = activityRootViewMap
    for (viewNode <- getViewNodes(ctxMethod, stmt, viewLocal)) {
      activityRootViewMap2 = activityRootViewMap2 + (actClass -> Set(viewNode))
    }
    copy(
      activityRootViewMap = activityRootViewMap2
    )
  }

  def setId(ctxMethod: SootMethod, dialogLocal: Local, stmt: Stmt, newId: Int): AFTDomain = {
    val nodes = getViewNodes(ctxMethod, stmt, dialogLocal).toSet
    mapViewNodes(viewNode => {
      if (nodes.contains(viewNode)) {
        viewNode.copy(id = viewNode.id + newId)
      } else {
        viewNode
      }
    })
  }

  def inflate(contextMethod: SootMethod, stmt: Stmt, id: Int, ref: Ref, parentView: Local, attachToRoot: Boolean): AFTDomain = {
    val view = GUIAnalysis.xmlParser.findViewById(id)
    val viewNode = ViewNode(stmt, id = Set(id), androidView = view)
    val inflated = inflateAFT(stmt, viewNode, view).withNode(ref, viewNode)
    if (attachToRoot) {
      inflated.withEdge(contextMethod, stmt, parentView, viewNode)
    } else {
      inflated
    }
  }

  def inflateAFT(stmt: Stmt, node: ViewNode, view: AndroidView): AFTDomain = {
    var d = copy()
    if (view == null) {
      return d
    }
    for (child <- view.getChildren.asScala) {
      val childNode = ViewNode(stmt, id=Set(child.getId), sootClass = Some(child.getSootClass), androidView = child)
      d = d.inflateAFT(stmt, childNode, child)
      d = d.addEdge(node, childNode)
    }
    d
  }

  def setHandlers(contextMethod: SootMethod, stmt: Stmt, l: Local,
                  eventType: EventType, handlers: Set[SootMethod]): AFTDomain = {
    var nodeHandlerMap2 = nodeHandlerMap
    for (viewNode <- getViewNodes(contextMethod, stmt, l)) {
      nodeHandlerMap2 += ((viewNode, eventType) -> handlers)
    }
    copy(nodeHandlerMap = nodeHandlerMap2)
  }

  /**
   * @param contextMethod
   * @param stmt
   * @param l
   * @param handler
   * @param dialogButtonType: If none, means unknown type
   * @return
   */
  def setDialogHandler(contextMethod: SootMethod, stmt: Stmt, l: Local,
                       handler: SootMethod, dialogButtonType: Option[DialogButtonType.Value]): AFTDomain = {
    dialogButtonType match {
      case Some(buttonType) =>
        var dialogHandlerMap2 = dialogHandlerMap
        for (viewNode <- getViewNodes(contextMethod, stmt, l)) {
          dialogHandlerMap2 += ((viewNode, buttonType) -> Set(handler))
        }
        copy(dialogHandlerMap = dialogHandlerMap2)
      case None =>
        var dialogHandlerMap2 = dialogHandlerMap
        for (viewNode <- getViewNodes(contextMethod, stmt, l)) {
          for (buttonType <- DialogButtonType.values) {
            val handlers = dialogHandlerMap2.get((viewNode, buttonType)) match {
              case Some(handlers) => handlers + handler
              case None => Set(handler)
            }
            dialogHandlerMap2 += ((viewNode, buttonType) -> handlers)
          }
        }
        copy(dialogHandlerMap = dialogHandlerMap2)
    }
  }

  def setLayoutParams(ctxMethod: SootMethod, stmt: Stmt, viewLocal: Local, paramsLocal: Local): AFTDomain = {
    var d = copy()
    for (node <- getNodes(ctxMethod, stmt, paramsLocal)) {
      node match {
        case LayoutParamsNode(attrs) =>
          d = d.updateNodes(ctxMethod, stmt, viewLocal, {
            case viewNode: ViewNode =>
              viewNode.setAttributes(attrs)
            case n => n
          })
        case _ =>
      }
    }
    d
  }

  def initLayoutParams(ctxMethod: SootMethod, stmt: Stmt, paramsBase: Local, width: Value, height: Value): AFTDomain = {
    val newAttrs = List((width, AndroidView.ViewAttr.layout_width), (height, AndroidView.ViewAttr.layout_height)).collect {
      case (intConstant: IntConstant, attr) =>
        (attr, Constants.layoutParamIntToString(intConstant.value))
    }
    if (newAttrs.nonEmpty) {
      updateNodes(ctxMethod, stmt, paramsBase, {
        case LayoutParamsNode(attrs) => LayoutParamsNode(attrs ++ newAttrs)
        case n => n
      })
    } else {
      this
    }
  }

  def getDialogButtonHandlers(node: ViewNode): Iterable[SootMethod] = {
    dialogHandlerMap.flatMap { case ((n, _), handlers) =>
      if (n == node) {
        handlers
      } else {
        Set()
      }
    }
  }

  def killRef(ref: Ref): AFTDomain =
    ref match {
      case Ref.GlobalRef(cls, fields) =>
        copy(globalNodeMap = (fields, globalNodeMap.get(cls)) match {
          case (_, None) => globalNodeMap
          case (List(), _) => globalNodeMap - cls
          case (fields, Some(accessPath)) => globalNodeMap + (cls -> accessPath.kill(fields))
        })
      case Ref.LocalRef(local, fields) =>
        copy(localNodeMap = (fields, localNodeMap.get(local)) match {
          case (_, None) => localNodeMap
          case (List(), _) => localNodeMap - local
          case (fields, Some(accessPath)) => localNodeMap + (local -> accessPath.kill(fields))
        })
    }

  def withNode(ref: Ref, node: AbsNode): AFTDomain = {
    withNodes(ref, Set(node))
  }

  def withNodes(ref: Ref, nodes: Set[AbsNode]): AFTDomain = {
    ref match {
      case Ref.GlobalRef(cls, fields) =>
        copy(
          globalNodeMap = if (nodes.nonEmpty) {
            globalNodeMap + (cls -> AccessPath(None).add(fields, nodes))
          } else {
            globalNodeMap
          }
        )
      case Ref.LocalRef(local, fields) =>
        copy(
          localNodeMap = if (nodes.nonEmpty) {
            localNodeMap + (local -> AccessPath(None).add(fields, nodes))
          } else {
            localNodeMap
          }
        )
    }
  }

  def newView(ref: Ref, sootClass: SootClass, stmt: Stmt): AFTDomain = {
    withNode(ref, ViewNode(stmt, sootClass = Some(sootClass), androidView = null))
  }

  def newLayoutParams(ref: Ref): AFTDomain = {
    withNode(ref, LayoutParamsNode())
  }

  def dialogCreate(ref: Ref, sootClass: SootClass, stmt: Stmt, builderLocal: Local, contextMethod: SootMethod): AFTDomain = {
    val dialogViewNode = ViewNode(stmt, sootClass = Some(sootClass), androidView = null)
    var d = withNode(ref, dialogViewNode)
    for (builderNode <- getViewNodes(contextMethod, stmt, builderLocal)) {
      for (((node, buttonType), handlers) <- dialogHandlerMap) {
        if (node == builderNode) {
          val newHandlers = d.dialogHandlerMap.getOrElse((dialogViewNode, buttonType), Set()) ++ handlers
          d = d.copy(
            dialogHandlerMap = d.dialogHandlerMap + ((dialogViewNode, buttonType) -> newHandlers)
          )
        }
      }
    }
    d
  }

  def newAct(ref: Ref, sootClass: SootClass): AFTDomain = {
    withNode(ref, ActNode(sootClass = sootClass))
  }

  def newListener(ref: Ref, c: SootClass): AFTDomain = {
    withNode(ref, ListenerNode(c))
  }

  def findViewById(viewNode: ViewNode, id: Int): IterableOnce[ViewNode] = {
    // FIXME: without knowing the exact indices, this is going to be an over-approx
    (nodeEdgeMap.get(viewNode) match {
      case Some(edges) =>
        edges.flatMap(targetNode => findViewById(targetNode, id))
      case None => Iterable.empty
    }) ++ (if (viewNode.id.contains(id)) {
      Iterable.single(viewNode)
    } else {
      Iterable.empty
    })
  }

  /**
   * FIXME: might be slow
   * @param parent
   * @param child
   * @return
   */
  def withEdge(contextMethod: SootMethod, stmt: Stmt, parent: Local, child: Local): AFTDomain = {
    var d = copy()
    for (parentNode <- getViewNodes(contextMethod, stmt, parent)) {
      for (childNode <- getViewNodes(contextMethod, stmt, child)) {
        d = d.addEdge(parentNode, childNode)
      }
    }
    d
  }

  // NOTE: repetition
  def withEdge(contextMethod: SootMethod, stmt: Stmt, parent: Local, childNode: ViewNode): AFTDomain = {
    var d = copy()
    for (parentNode <- getViewNodes(contextMethod, stmt, parent)) {
      d = d.addEdge(parentNode, childNode)
    }
    d
  }

  def toJSONObj: Object = Map[String, Object](
    "localNodeMap" -> localNodeMap.map { case (k, v) => (k.toString(), v.toJSONObj) },
    "globalNodeMap" -> globalNodeMap.map { case (k, v) => (k.toString(), v.toJSONObj) }
  )
}

object AFTDomain {
  val top: AFTDomain = AFTDomain(Map(), Map(), Map(), Map(), Map(), Map(), Map())

  def addEdgeToMap(m: Map[ViewNode, Set[ViewNode]], from: ViewNode, to: ViewNode): Map[ViewNode, Set[ViewNode]] = {
    m.get(from) match {
      case Some(edges) =>
        m + (from -> (edges ++ Set(to)))
      case None =>
        m + (from -> Set(to))
    }
  }

  def mapToString[K, V](m: Map[K, V]): String =
    m.map { case (k, v) => "\t " + k + " -> " + v }.mkString("\n")

  def mergeMapOfSets[K, V](m1: Map[K, Set[V]], m2: Map[K, Set[V]]): Map[K, Set[V]] = {
    if (m1.equals(m2)) return m1
    mergeMaps(m1, m2, (m1: Set[V], m2: Set[V]) => m1 ++ m2)
  }

  def mergeMaps[K, V](m1: Map[K, V], m2: Map[K, V], f: (V, V) => V): Map[K, V] = {
    (m1.keySet ++ m2.keySet).map(k => {
      if (m1.contains(k) && m2.contains(k)) {
        (k, f(m1(k), m2(k)))
      } else if (m1.contains(k)) {
        (k, m1(k))
      } else {
        (k, m2(k))
      }
    }).toMap
  }

  def mergeMapOfAccessPath[K, D](m1: Map[K, AccessPath[D]],
                                 m2: Map[K, AccessPath[D]]): Map[K, AccessPath[D]] = {
    if (m1.equals(m2)) return m1
    mergeMaps(m1, m2, (m1: AccessPath[D], m2: AccessPath[D]) => m1.merge(m2))
  }
}