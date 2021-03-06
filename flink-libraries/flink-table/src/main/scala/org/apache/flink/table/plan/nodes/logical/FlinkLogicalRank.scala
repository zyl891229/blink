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
package org.apache.flink.table.plan.nodes.logical

import org.apache.flink.table.plan.metadata.FlinkRelMetadataQuery
import org.apache.flink.table.plan.nodes.FlinkConventions
import org.apache.flink.table.plan.nodes.calcite.{LogicalRank, Rank}
import org.apache.flink.table.plan.util.{FlinkRexUtil, RankRange}

import org.apache.calcite.plan._
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.convert.ConverterRule
import org.apache.calcite.rel.{RelCollation, RelNode, RelWriter}
import org.apache.calcite.sql.SqlRankFunction
import org.apache.calcite.util.ImmutableBitSet

import java.util

import scala.collection.JavaConversions._

class FlinkLogicalRank(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    input: RelNode,
    rankFunction: SqlRankFunction,
    partitionKey: ImmutableBitSet,
    sortCollation: RelCollation,
    rankRange: RankRange,
    val outputRankFunColumn: Boolean)
  extends Rank(
    cluster,
    traitSet,
    input,
    rankFunction,
    partitionKey,
    sortCollation,
    rankRange)
  with FlinkLogicalRel {

  override def deriveRowType(): RelDataType = {
    if (outputRankFunColumn) {
      super.deriveRowType()
    } else {
      input.getRowType
    }
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    val inputFieldNames = input.getRowType.getFieldNames
    pw.item("input", getInput)
      .item("rankFunction", rankFunction)
      .item("partitionBy", partitionKey.map(inputFieldNames.get(_)).mkString(","))
      .item("orderBy", Rank.sortFieldsToString(sortCollation, input.getRowType))
      .item("rankRange", rankRange.toString(inputFieldNames))
      .item("select", getRowType.getFieldNames.mkString(", "))
  }

  override def copy(traitSet: RelTraitSet, inputs: util.List[RelNode]): RelNode = {
    new FlinkLogicalRank(
      cluster,
      traitSet,
      inputs.get(0),
      rankFunction,
      partitionKey,
      sortCollation,
      rankRange,
      outputRankFunColumn)
  }

  override def isDeterministic: Boolean = FlinkRexUtil.isDeterministicOperator(rankFunction)
}

private class FlinkLogicalRankConverter extends ConverterRule(
  classOf[LogicalRank],
  Convention.NONE,
  FlinkConventions.LOGICAL,
  "FlinkLogicalRankConverter") {
  override def convert(rel: RelNode): RelNode = {
    val rank = rel.asInstanceOf[LogicalRank]
    val newInput = RelOptRule.convert(rank.getInput, FlinkConventions.LOGICAL)
    FlinkLogicalRank.create(
      newInput,
      rank.rankFunction,
      rank.partitionKey,
      rank.sortCollation,
      rank.rankRange,
      outputRankFunColumn = true
    )
  }
}


object FlinkLogicalRank {
  val CONVERTER: ConverterRule = new FlinkLogicalRankConverter

  def create(
      input: RelNode,
      rankFunction: SqlRankFunction,
      partitionKey: ImmutableBitSet,
      sortCollation: RelCollation,
      rankRange: RankRange,
      outputRankFunColumn: Boolean): FlinkLogicalRank = {
    val cluster = input.getCluster
    val traits = cluster.traitSetOf(Convention.NONE)
    // FIXME: FlinkRelMdDistribution requires the current RelNode to compute
    // the distribution trait, so we have to create FlinkLogicalRank to
    // calculate the distribution trait.
    val rank = new FlinkLogicalRank(cluster, traits, input, rankFunction, partitionKey,
      sortCollation, rankRange, outputRankFunColumn)
    val newTraitSet = FlinkRelMetadataQuery.traitSet(rank)
      .replace(FlinkConventions.LOGICAL).simplify()
    rank.copy(newTraitSet, rank.getInputs).asInstanceOf[FlinkLogicalRank]
  }
}
