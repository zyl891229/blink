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

import org.apache.flink.api.scala._
import org.apache.flink.table.api.TableException
import org.apache.flink.table.api.scala._
import org.apache.flink.table.calcite.CalciteConfig
import org.apache.flink.table.plan.optimize._

import org.apache.calcite.plan.hep.HepMatchOrder
import org.apache.calcite.rel.rules.SemiJoinFilterTransposeRule
import org.apache.calcite.tools.RuleSets
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.{Before, Test}

@RunWith(classOf[Parameterized])
class FlinkSemiJoinProjectTransposeRuleTest(fieldsNullable: Boolean)
  extends SubQueryTestBase(fieldsNullable) {

  @Before
  def setup(): Unit = {
    // update programs inited in parent class
    val programs = util.getTableEnv.getConfig.getCalciteConfig.getBatchPrograms
      .getOrElse(throw new TableException("optimize programs is not set in parent class"))
    programs.addLast("semi_join_transpose",
      FlinkHepRuleSetProgramBuilder.newBuilder
        .setHepRulesExecutionType(HEP_RULES_EXECUTION_TYPE.RULE_COLLECTION)
        .setHepMatchOrder(HepMatchOrder.BOTTOM_UP)
        .add(RuleSets.ofList(
          FlinkSemiJoinJoinTransposeRule.INSTANCE,
          FlinkSemiJoinProjectTransposeRule.INSTANCE,
          SemiJoinFilterTransposeRule.INSTANCE))
        .build())
    val calciteConfig = CalciteConfig.createBuilder(util.tableEnv.getConfig.getCalciteConfig)
      .replaceBatchPrograms(programs).build()
    util.tableEnv.getConfig.setCalciteConfig(calciteConfig)

    util.addTable[(Int, Long, String)]("x", 'a, 'b, 'c)
    util.addTable[(Int, Long, String)]("y", 'd, 'e, 'f)
    util.addTable[(Int, Long, String)]("z", 'i, 'j, 'k)
  }

  @Test
  def testTranspose(): Unit = {
    val sqlQuery = "SELECT a, f FROM " +
      "(SELECT a, b, d, e, f FROM x, y WHERE x.c = y.f) xy " +
      "WHERE xy.e > 100 AND xy.d IN (SELECT z.i FROM z WHERE z.j < 50)"
    util.verifyPlan(sqlQuery)
  }

  @Test
  def testCannotTranspose(): Unit = {
    val sqlQuery = "SELECT a, f FROM " +
      "(SELECT a * 2 as a, b, d + 1 as d, e, f FROM x, y WHERE x.c = y.f) xy " +
      "WHERE xy.e > 100 AND xy.d IN (SELECT z.i FROM z WHERE z.j < 50)"
    util.verifyPlan(sqlQuery)
  }

}

object FlinkSemiJoinProjectTransposeRuleTest {
  @Parameterized.Parameters(name = "{0}")
  def parameters(): java.util.Collection[Boolean] = {
    java.util.Arrays.asList(true, false)
  }
}
