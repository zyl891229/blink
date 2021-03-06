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

package org.apache.flink.table.plan.rules.logical

import org.apache.flink.table.plan.util.FlinkRexUtil

import org.apache.calcite.plan.RelOptRule.{operand, _}
import org.apache.calcite.plan.{RelOptRule, RelOptRuleCall, RelOptRuleOperand}
import org.apache.calcite.rel.logical.LogicalJoin
import org.apache.calcite.rex._

class JoinConditionSimplifyExpressionsRule(
    operand: RelOptRuleOperand,
    description: String)
  extends RelOptRule(operand, description){

  override def onMatch(call: RelOptRuleCall): Unit = {
    val join = call.rel(0).asInstanceOf[LogicalJoin]
    val cond = join.getCondition

    if (join.getCondition.isAlwaysTrue) {
      return
    }

    val simpleCondExp = FlinkRexUtil.simplify(join.getCluster.getRexBuilder, cond)
    val newCondExp = RexUtil.pullFactors(join.getCluster.getRexBuilder, simpleCondExp)

    if (newCondExp.toString.equals(cond.toString)) {
      return
    }

    val newJoinRel = join.copy(
      join.getTraitSet,
      newCondExp,
      join.getLeft,
      join.getRight,
      join.getJoinType,
      join.isSemiJoinDone)

    call.transformTo(newJoinRel)
    call.getPlanner.setImportance(join, 0.0)
  }
}

object  JoinConditionSimplifyExpressionsRule {
  val INSTANCE = new JoinConditionSimplifyExpressionsRule(
    operand(classOf[LogicalJoin], any()),
    "JoinConditionSimplifyExpressionsRule")
}
