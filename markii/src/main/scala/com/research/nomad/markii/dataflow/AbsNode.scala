package com.research.nomad.markii.dataflow

import presto.android.gui.listener.EventType
import presto.android.xml.AndroidView
import soot.SootClass
import soot.jimple.Stmt

import scala.collection.mutable
import scala.jdk.CollectionConverters._

sealed abstract class AbsNode extends Product with Serializable

object AbsNode {
  final case class ViewNode(allocSite: Stmt,
                            id: Set[Int] = Set(),
                            sootClass: Option[SootClass] = None,
                            attributes: Map[AndroidView.ViewAttr, Set[String]] = Map(),
                            private val androidView: AndroidView) extends AbsNode {

    // FIXME: merge attributes without changing the hash code?
    //        or maybe factoring out the attribute map into another abstract state?
    def setAttributes(attrs: Set[(AndroidView.ViewAttr, String)]): AbsNode = {
      val newAttrs = mutable.Map[AndroidView.ViewAttr, Set[String]]()
      for ((attr, value) <- attrs) {
        newAttrs.put(attr, newAttrs.getOrElse(attr, Set()) + value)
      }
      for ((attr, values) <- attributes) {
        if (!newAttrs.contains(attr)) {
          newAttrs.put(attr, values)
        }
      }
      copy(attributes = newAttrs.toMap)
    }

    def nodeID: Int = hashCode()

    override def toString: String = f"ViewNode(${nodeID.toString}, id=${id}, class=${sootClass})"

    def getAttrs: Iterable[(AndroidView.ViewAttr, String)] = {
      val defaultAttrs = if (androidView != null) {
        androidView.getViewAttrs.asScala.filter { case (attr, _) => !attributes.contains(attr) }
      } else {
        List()
      }
      defaultAttrs ++ attributes.flatMap { case (attr, values) => values.map((attr, _)) }
    }

    def getAppAttrs: Iterable[(AndroidView.ViewAppAttr, String)] = if (androidView != null) {
      androidView.getViewAppAttrs.asScala
    } else {
      List()
    }

    def getInlineClickHandlers: Iterable[(EventType, String)] = if (androidView != null) {
      androidView.getInlineClickHandlers.asScala
    } else {
      List()
    }
  }

  final case class ActNode(sootClass: SootClass) extends AbsNode

  final case class ListenerNode(listener: SootClass) extends AbsNode

  final case class LayoutParamsNode(attrs: Set[(AndroidView.ViewAttr, String)] = Set()) extends AbsNode
}