/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.expressions

import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rex._
import org.apache.calcite.sql.fun.SqlStdOperatorTable
import org.apache.calcite.tools.RelBuilder
import org.apache.flink.table.api.types.{DataTypes, InternalType, TimestampType}
import org.apache.flink.table.calcite.FlinkRelBuilder
import org.apache.flink.table.expressions.TimeIntervalUnit.TimeIntervalUnit
import org.apache.flink.table.functions.sql.ScalarSqlFunctions
import org.apache.flink.table.plan.logical.LogicalExprVisitor
import org.apache.flink.table.typeutils.TypeCheckUtils
import org.apache.flink.table.typeutils.TypeCheckUtils.isTimeInterval
import org.apache.flink.table.validate.{ValidationFailure, ValidationResult, ValidationSuccess}

import scala.collection.JavaConversions._

case class Extract(timeIntervalUnit: Expression, temporal: Expression) extends Expression {

  override private[flink] def children: Seq[Expression] = timeIntervalUnit :: temporal :: Nil

  override private[flink] def resultType: InternalType = DataTypes.LONG

  override private[flink] def validateInput(): ValidationResult = {
    if (!TypeCheckUtils.isTemporal(temporal.resultType)) {
      return ValidationFailure(s"Extract operator requires Temporal input, " +
        s"but $temporal is of type ${temporal.resultType}")
    }

    timeIntervalUnit match {
      case SymbolExpression(TimeIntervalUnit.YEAR)
           | SymbolExpression(TimeIntervalUnit.QUARTER)
           | SymbolExpression(TimeIntervalUnit.MONTH)
           | SymbolExpression(TimeIntervalUnit.WEEK)
           | SymbolExpression(TimeIntervalUnit.DAY)
        if temporal.resultType == DataTypes.DATE
          || temporal.resultType == DataTypes.TIMESTAMP
          || temporal.resultType == DataTypes.INTERVAL_MILLIS
          || temporal.resultType == DataTypes.INTERVAL_MONTHS =>
        ValidationSuccess

      case SymbolExpression(TimeIntervalUnit.HOUR)
           | SymbolExpression(TimeIntervalUnit.MINUTE)
           | SymbolExpression(TimeIntervalUnit.SECOND)
        if temporal.resultType == DataTypes.TIME
          || temporal.resultType == DataTypes.TIMESTAMP
          || temporal.resultType == DataTypes.INTERVAL_MILLIS =>
        ValidationSuccess

      case _ =>
        ValidationFailure(s"Extract operator does not support unit '$timeIntervalUnit' for input" +
          s" of type '${temporal.resultType}'.")
    }
  }

  override def toString: String = s"($temporal).extract($timeIntervalUnit)"

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode =
    relBuilder
      .getRexBuilder
      .makeCall(
        SqlStdOperatorTable.EXTRACT,
        Seq(timeIntervalUnit.toRexNode, temporal.toRexNode))

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

abstract class TemporalCeilFloor(
    timeIntervalUnit: Expression,
    temporal: Expression)
  extends Expression {

  override private[flink] def children: Seq[Expression] = timeIntervalUnit :: temporal :: Nil

  override private[flink] def resultType: InternalType = temporal.resultType

  override private[flink] def validateInput(): ValidationResult = {
    if (!TypeCheckUtils.isTimePoint(temporal.resultType)) {
      return ValidationFailure(s"Temporal ceil/floor operator requires Time Point input, " +
        s"but $temporal is of type ${temporal.resultType}")
    }
    val unit = timeIntervalUnit match {
      case SymbolExpression(u: TimeIntervalUnit) => Some(u)
      case _ => None
    }
    if (unit.isEmpty) {
      return ValidationFailure(s"Temporal ceil/floor operator requires Time Interval Unit " +
        s"input, but $timeIntervalUnit is of type ${timeIntervalUnit.resultType}")
    }

    (unit.get, temporal.resultType) match {
      case (TimeIntervalUnit.YEAR | TimeIntervalUnit.MONTH,
          DataTypes.DATE | _: TimestampType) =>
        ValidationSuccess
      case (TimeIntervalUnit.DAY, _: TimestampType) =>
        ValidationSuccess
      case (TimeIntervalUnit.HOUR | TimeIntervalUnit.MINUTE | TimeIntervalUnit.SECOND,
          DataTypes.TIME | _: TimestampType) =>
        ValidationSuccess
      case _ =>
        ValidationFailure(s"Temporal ceil/floor operator does not support " +
          s"unit '$timeIntervalUnit' for input of type '${temporal.resultType}'.")
    }
  }
}

case class TemporalFloor(
    timeIntervalUnit: Expression,
    temporal: Expression)
  extends TemporalCeilFloor(
    timeIntervalUnit,
    temporal) {

  override def toString: String = s"($temporal).floor($timeIntervalUnit)"

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    relBuilder.call(SqlStdOperatorTable.FLOOR, temporal.toRexNode, timeIntervalUnit.toRexNode)
  }

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class TemporalCeil(
    timeIntervalUnit: Expression,
    temporal: Expression)
  extends TemporalCeilFloor(
    timeIntervalUnit,
    temporal) {

  override def toString: String = s"($temporal).ceil($timeIntervalUnit)"

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    relBuilder.call(SqlStdOperatorTable.CEIL, temporal.toRexNode, timeIntervalUnit.toRexNode)
  }

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

abstract class CurrentTimePoint(
    targetType: InternalType,
    local: Boolean)
  extends LeafExpression {

  override private[flink] def resultType: InternalType = targetType

  override private[flink] def validateInput(): ValidationResult = {
    if (!TypeCheckUtils.isTimePoint(targetType)) {
      ValidationFailure(s"CurrentTimePoint operator requires Time Point target type, " +
        s"but get $targetType.")
    } else if (local && targetType == DataTypes.DATE) {
      ValidationFailure(s"Localized CurrentTimePoint operator requires Time or Timestamp target " +
        s"type, but get $targetType.")
    } else {
      ValidationSuccess
    }
  }

  override def toString: String = if (local) {
    s"local$targetType()"
  } else {
    s"current$targetType()"
  }

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    val operator = targetType match {
      case DataTypes.TIME if local => SqlStdOperatorTable.LOCALTIME
      case DataTypes.TIMESTAMP if local => SqlStdOperatorTable.LOCALTIMESTAMP
      case DataTypes.DATE => SqlStdOperatorTable.CURRENT_DATE
      case DataTypes.TIME => SqlStdOperatorTable.CURRENT_TIME
      case DataTypes.TIMESTAMP => SqlStdOperatorTable.CURRENT_TIMESTAMP
    }
    relBuilder.call(operator)
  }
}

case class CurrentDate() extends CurrentTimePoint(DataTypes.DATE, local = false) {
  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class CurrentTime() extends CurrentTimePoint(DataTypes.TIME, local = false) {
  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class CurrentTimestamp() extends CurrentTimePoint(DataTypes.TIMESTAMP, local = false) {
  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class LocalTime() extends CurrentTimePoint(DataTypes.TIME, local = true) {
  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class LocalTimestamp() extends CurrentTimePoint(DataTypes.TIMESTAMP, local = true) {
  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

/**
 * * Extracts the quarter of a year from a SQL date.
  */
case class Quarter(child: Expression) extends UnaryExpression with InputTypeSpec {

  override private[flink] def expectedTypes: Seq[InternalType] = Seq(DataTypes.DATE)

  override private[flink] def resultType: InternalType = DataTypes.LONG

  override def toString: String = s"($child).quarter()"

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    /**
      * Standard conversion of the QUARTER operator.
      * Source: [[org.apache.calcite.sql2rel.StandardConvertletTable#convertQuarter()]]
      */
    Plus(
      Cast(
        Div(
          Minus(
            Extract(TimeIntervalUnit.MONTH, child),
            Literal(1L)),
          Literal(TimeUnit.QUARTER.multiplier.longValue())),
        DataTypes.INT
      ),
      Literal(1L)
    ).toRexNode
  }

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

/**
  * Determines whether two anchored time intervals overlap.
  */
case class TemporalOverlaps(
    leftTimePoint: Expression,
    leftTemporal: Expression,
    rightTimePoint: Expression,
    rightTemporal: Expression)
  extends Expression {

  override private[flink] def children: Seq[Expression] =
    Seq(leftTimePoint, leftTemporal, rightTimePoint, rightTemporal)

  override private[flink] def resultType: InternalType = DataTypes.BOOLEAN

  override private[flink] def validateInput(): ValidationResult = {
    if (!TypeCheckUtils.isTimePoint(leftTimePoint.resultType)) {
      return ValidationFailure(s"TemporalOverlaps operator requires leftTimePoint to be of type " +
        s"Time Point, but get ${leftTimePoint.resultType}.")
    }
    if (!TypeCheckUtils.isTimePoint(rightTimePoint.resultType)) {
      return ValidationFailure(s"TemporalOverlaps operator requires rightTimePoint to be of " +
        s"type Time Point, but get ${rightTimePoint.resultType}.")
    }
    if (leftTimePoint.resultType != rightTimePoint.resultType) {
      return ValidationFailure(s"TemporalOverlaps operator requires leftTimePoint and " +
        s"rightTimePoint to be of same type.")
    }

    // leftTemporal is point, then it must be comparable with leftTimePoint
    if (TypeCheckUtils.isTimePoint(leftTemporal.resultType)) {
      if (leftTemporal.resultType != leftTimePoint.resultType) {
        return ValidationFailure(s"TemporalOverlaps operator requires leftTemporal and " +
          s"leftTimePoint to be of same type if leftTemporal is of type Time Point.")
      }
    } else if (!isTimeInterval(leftTemporal.resultType)) {
      return ValidationFailure(s"TemporalOverlaps operator requires leftTemporal to be of " +
        s"type Time Point or Time Interval.")
    }

    // rightTemporal is point, then it must be comparable with rightTimePoint
    if (TypeCheckUtils.isTimePoint(rightTemporal.resultType)) {
      if (rightTemporal.resultType != rightTimePoint.resultType) {
        return ValidationFailure(s"TemporalOverlaps operator requires rightTemporal and " +
          s"rightTimePoint to be of same type if rightTemporal is of type Time Point.")
      }
    } else if (!isTimeInterval(rightTemporal.resultType)) {
      return ValidationFailure(s"TemporalOverlaps operator requires rightTemporal to be of " +
        s"type Time Point or Time Interval.")
    }
    ValidationSuccess
  }

  override def toString: String = s"temporalOverlaps(${children.mkString(", ")})"

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    convertOverlaps(
      leftTimePoint.toRexNode,
      leftTemporal.toRexNode,
      rightTimePoint.toRexNode,
      rightTemporal.toRexNode,
      relBuilder.asInstanceOf[FlinkRelBuilder])
  }

  /**
    * Standard conversion of the OVERLAPS operator.
    * Source: [[org.apache.calcite.sql2rel.StandardConvertletTable#convertOverlaps()]]
    */
  private def convertOverlaps(
      leftP: RexNode,
      leftT: RexNode,
      rightP: RexNode,
      rightT: RexNode,
      relBuilder: FlinkRelBuilder)
    : RexNode = {
    val convLeftT = convertOverlapsEnd(relBuilder, leftP, leftT, leftTemporal.resultType)
    val convRightT = convertOverlapsEnd(relBuilder, rightP, rightT, rightTemporal.resultType)

    // sort end points into start and end, such that (s0 <= e0) and (s1 <= e1).
    val (s0, e0) = buildSwap(relBuilder, leftP, convLeftT)
    val (s1, e1) = buildSwap(relBuilder, rightP, convRightT)

    // (e0 >= s1) AND (e1 >= s0)
    val leftPred = relBuilder.call(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, e0, s1)
    val rightPred = relBuilder.call(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, e1, s0)
    relBuilder.call(SqlStdOperatorTable.AND, leftPred, rightPred)
  }

  private def convertOverlapsEnd(
      relBuilder: FlinkRelBuilder,
      start: RexNode, end: RexNode,
      endType: InternalType) = {
    if (isTimeInterval(endType)) {
      relBuilder.call(SqlStdOperatorTable.DATETIME_PLUS, start, end)
    } else {
      end
    }
  }

  private def buildSwap(relBuilder: FlinkRelBuilder, start: RexNode, end: RexNode) = {
    val le = relBuilder.call(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, start, end)
    val l = relBuilder.call(SqlStdOperatorTable.CASE, le, start, end)
    val r = relBuilder.call(SqlStdOperatorTable.CASE, le, end, start)
    (l, r)
  }

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class DateFormat(timestamp: Expression, format: Expression) extends Expression {
  override private[flink] def children = timestamp :: format :: Nil

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder) =
    relBuilder.call(ScalarSqlFunctions.DATE_FORMAT, timestamp.toRexNode, format.toRexNode)

  override def toString: String = s"$timestamp.dateFormat($format)"

  override private[flink] def resultType = DataTypes.STRING

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class TimestampDiff(
    timePointUnit: Expression,
    timePoint1: Expression,
    timePoint2: Expression)
    extends Expression {

  override private[flink] def children: Seq[Expression] =
    timePointUnit :: timePoint1 :: timePoint2 :: Nil

  override private[flink] def validateInput(): ValidationResult = {
    if (!TypeCheckUtils.isTimePoint(timePoint1.resultType)) {
      return ValidationFailure(
        s"$this requires an input time point type, " +
            s"but timePoint1 is of type '${timePoint1.resultType}'.")
    }

    if (!TypeCheckUtils.isTimePoint(timePoint2.resultType)) {
      return ValidationFailure(
        s"$this requires an input time point type, " +
            s"but timePoint2 is of type '${timePoint2.resultType}'.")
    }

    timePointUnit match {
      case SymbolExpression(TimePointUnit.YEAR)
           | SymbolExpression(TimePointUnit.QUARTER)
           | SymbolExpression(TimePointUnit.MONTH)
           | SymbolExpression(TimePointUnit.WEEK)
           | SymbolExpression(TimePointUnit.DAY)
           | SymbolExpression(TimePointUnit.HOUR)
           | SymbolExpression(TimePointUnit.MINUTE)
           | SymbolExpression(TimePointUnit.SECOND)
        if timePoint1.resultType == DataTypes.DATE
            || timePoint1.resultType == DataTypes.TIMESTAMP
            || timePoint2.resultType == DataTypes.DATE
            || timePoint2.resultType == DataTypes.TIMESTAMP =>
        ValidationSuccess

      case _ =>
        ValidationFailure(s"$this operator does not support unit '$timePointUnit'" +
            s" for input of type ('${timePoint1.resultType}', '${timePoint2.resultType}').")
    }
  }
  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    relBuilder
        .getRexBuilder
        .makeCall(SqlStdOperatorTable.TIMESTAMP_DIFF,
          Seq(timePointUnit.toRexNode, timePoint2.toRexNode, timePoint1.toRexNode))
  }

  override def toString: String = s"timestampDiff(${children.mkString(", ")})"

  override private[flink] def resultType = DataTypes.INT

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}
