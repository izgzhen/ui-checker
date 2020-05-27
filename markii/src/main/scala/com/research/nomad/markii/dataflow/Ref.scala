package com.research.nomad.markii.dataflow

import soot.{Local, SootClass, Value}
import soot.jimple.{ArrayRef, InstanceFieldRef, StaticFieldRef}

sealed abstract class Ref extends Product with Serializable {
  def field(name: String): Ref
}

object Ref {
  final case class GlobalRef(cls: SootClass, fields: List[String] = List()) extends Ref {
    def field(name: String): Ref = GlobalRef(cls, fields ++ List(name))
  }

  final case class LocalRef(local: Local, fields: List[String] = List()) extends Ref {
    def field(name: String): Ref = LocalRef(local, fields ++ List(name))
  }

  def from(l: Local): Ref = LocalRef(l)

  def from(instanceFieldRef: InstanceFieldRef): Ref = {
    val localRef = from(instanceFieldRef.getBase.asInstanceOf[Local])
    localRef.field(instanceFieldRef.getField.getName)
  }

  def from(staticFieldRef: StaticFieldRef): Ref = {
    val ref = GlobalRef(staticFieldRef.getFieldRef.declaringClass())
    ref.field(staticFieldRef.getField.getName)
  }

  def from(value: Value): Ref = {
    value match {
      case staticFieldRef: StaticFieldRef => from(staticFieldRef)
      case local: Local => from(local)
      case instanceFieldRef: InstanceFieldRef => from(instanceFieldRef)
      case arrayRef: ArrayRef => from(arrayRef.getBase)
    }
  }
}
