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

package org.apache.flink.table.api.batch.table.validation

import org.apache.flink.api.scala._
import org.apache.flink.table.api.ValidationException
import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.types.{DataType, DataTypes}
import org.apache.flink.table.util.{MemoryTableSourceSinkUtil, TableTestBase}
import org.junit._

class InsertIntoValidationTest extends TableTestBase {

  @Test(expected = classOf[ValidationException])
  def testInconsistentLengthInsert(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String)]("sourceTable", 'a, 'b, 'c)

    val fieldNames = Array("d", "e")
    val fieldTypes: Array[DataType] = Array(DataTypes.INT, DataTypes.LONG)
    val sink = new MemoryTableSourceSinkUtil.UnsafeMemoryAppendTableSink
    util.tableEnv.registerTableSink("targetTable", fieldNames, fieldTypes, sink)

    // must fail because TableSink accepts fewer fields.
    util.tableEnv.scan("sourceTable")
      .select('a, 'b, 'c)
      .insertInto("targetTable")
  }

  @Test(expected = classOf[ValidationException])
  def testUnmatchedTypesInsert(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String)]("sourceTable", 'a, 'b, 'c)

    val fieldNames = Array("d", "e", "f")
    val fieldTypes: Array[DataType] =
      Array(DataTypes.STRING, DataTypes.INT, DataTypes.LONG)
    val sink = new MemoryTableSourceSinkUtil.UnsafeMemoryAppendTableSink
    util.tableEnv.registerTableSink("targetTable", fieldNames, fieldTypes, sink)

    // must fail because types of result and TableSink do not match.
    util.tableEnv.scan("sourceTable")
      .select('a, 'b, 'c)
      .insertInto("targetTable")
  }
}
