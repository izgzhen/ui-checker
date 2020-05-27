package com.research.nomad.markii.dataflow

import scala.collection.mutable

/**
 * Access path models an pointer with data and sub-fields
 *
 * @param data
 * @param fields
 * @tparam D
 */
case class AccessPath[D](data: Option[Set[D]], fields: Map[String, AccessPath[D]] = Map[String, AccessPath[D]](), maxDepth: Int = 2) {
  def merge(path: AccessPath[D]): AccessPath[D] = {
    val newData = (data, path.data) match {
      case (Some(s1), Some(s2)) => Some(s1 ++ s2)
      case (Some(s1), None) => Some(s1)
      case (None, Some(s2)) => Some(s2)
      case (None, None) => None
    }
    AccessPath[D](newData, AFTDomain.mergeMapOfAccessPath(fields, path.fields))
  }

  def updateData(f: D => D): AccessPath[D] = {
    AccessPath(data = data.map(_.map(f)), fields)
  }

  def traverse(): List[(List[String], Set[D])] = {
    val m = mutable.Queue[(List[String], Set[D])]()
    data match {
      case Some(ds) => m.addOne((List(), ds))
      case None =>
    }
    for ((fieldName, subPath) <- fields) {
      for ((path, ds) <- subPath.traverse()) {
        m.addOne((fieldName::path, ds))
      }
    }
    m.toList
  }

  override def toString: String = {
    traverse().map { case (path, ds) =>
      path.mkString(".") + ": " + ds.mkString(",")
    }.mkString("\n\t")
  }

  def toJSONObj: Object = {
    traverse().map { case (path, ds) =>
      (path.mkString("."), ds.mkString(","))
    }
  }

  def truncatedFieldNames(fieldNames: List[String]): List[String] = {
    if (fieldNames.length > maxDepth) {
      fieldNames.slice(0, maxDepth) :+ "*"
    } else {
      fieldNames
    }
  }

  def kill(fieldNames: List[String]): AccessPath[D] = {
    truncatedFieldNames(fieldNames) match {
      case ::(head, next) => fields.get(head) match {
        case Some(subPath) => AccessPath(data = data, fields = fields + (head -> subPath.kill(next)))
        case None => this
      }
      case Nil => AccessPath(None, fields = fields)
    }
  }

  def get(fieldNames: List[String]): Option[AccessPath[D]] = {
    truncatedFieldNames(fieldNames) match {
      case ::(head, next) => fields.get(head) match {
        case Some(subPath) => subPath.get(next)
        case None => None
      }
      case Nil => Some(this)
    }
  }

  def getAllData: Set[D] = {
    val subData = fields.values.flatMap(_.getAllData).toSet
    subData ++ data.getOrElse(Set())
  }

  def truncated(d: Int): AccessPath[D] = {
    if (d == 0)
      if (fields.nonEmpty) {
        val subData = fields.values.flatMap(_.getAllData).toSet
        AccessPath(data = data).add(List("*"), subData)
      } else {
        this
    } else {
      AccessPath(data = data, fields = fields.map { case (k, v) => (k, v.truncated(d - 1))})
    }
  }

  def add(fieldNames: List[String], subPath: AccessPath[D]): AccessPath[D] = {
    val truncated = truncatedFieldNames(fieldNames)
    val leftDepth = maxDepth - truncated.size
    val subPathTruncated = subPath.truncated(leftDepth)
    truncated match {
      case ::(head, next) => fields.get(head) match {
        case Some(accessPath) => AccessPath(data = data, fields = fields + (head -> accessPath.add(next, subPathTruncated)))
        case None => AccessPath(data = data, fields = fields + (head -> AccessPath(None).add(next, subPathTruncated)))
      }
      case Nil => merge(subPathTruncated)
    }
  }

  def add(fieldNames: List[String], newData: Set[D]): AccessPath[D] = {
    truncatedFieldNames(fieldNames) match {
      case ::(head, next) => fields.get(head) match {
        case Some(subPath) => AccessPath(data = data, fields = fields + (head -> subPath.add(next, newData)))
        case None => AccessPath(data = data, fields = fields + (head -> AccessPath(None).add(next, newData)))
      }
      case Nil => AccessPath(data = Some(newData ++ data.getOrElse(Set())), fields = fields)
    }
  }
}
