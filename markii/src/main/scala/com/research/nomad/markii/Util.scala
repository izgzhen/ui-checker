package com.research.nomad.markii

import soot.SootMethod
import soot.jimple.InvokeExpr

/* Created at 6/3/20 by zhen */
object Util {
  def getMethodUnsafe(invokeExpr: InvokeExpr): SootMethod = {
    try {
      invokeExpr.getMethod
    } catch {
      case ignored: Exception =>
        println(ignored)
        null
    }
  }
}
