package com.research.nomad.markii.dataflow

import soot.SootClass

/* Created at 3/12/20 by zhen */
class AbstractIntent(val targets: Set[SootClass] = Set(),
                     val actions: Set[String] = Set()) {
  def withActions(newActions: Set[String]): AbstractIntent = {
    new AbstractIntent(targets, actions ++ newActions)
  }

  def withTargets(newTargets: Set[SootClass]): AbstractIntent = {
    new AbstractIntent(targets ++ newTargets, actions)
  }

  override def toString: String = f"AbstractIntent[${hashCode()}](${targets}, ${actions})"

  override def hashCode(): Int = {
    targets.hashCode() ^ actions.hashCode() + 1
  }

  override def equals(obj: Any): Boolean = {
    if (obj == null) return false
    obj match {
      case abstractIntent: AbstractIntent =>
        targets.equals(abstractIntent.targets) && actions.equals(abstractIntent.actions)
      case _ => false
    }
  }
}
