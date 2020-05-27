package com.research.nomad.markii.dataflow

import soot.jimple.InvokeExpr

/* Created at 3/20/20 by zhen */
sealed abstract class AbstractValue extends Product with Serializable

object AbstractValue {
  // TODO: Improve intent analysis
  final case class Intent(intent: AbstractIntent) extends AbstractValue
}
