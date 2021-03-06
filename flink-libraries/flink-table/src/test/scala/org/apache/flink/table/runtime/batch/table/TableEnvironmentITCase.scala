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

package org.apache.flink.table.runtime.batch.table

import org.apache.flink.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.types.DataType
import org.apache.flink.table.runtime.batch.sql.BatchTestBase
import org.apache.flink.table.util.{CollectionBatchExecTable, MemoryTableSourceSinkUtil}
import org.apache.flink.test.util.TestBaseUtils

import org.junit.Assert.assertEquals
import org.junit._

import scala.collection.JavaConverters._

class TableEnvironmentITCase extends BatchTestBase {

  @Test
  def testSimpleRegister(): Unit = {

    val tableName = "MyTable"

    val ds = CollectionBatchExecTable.get3TupleDataSet(tEnv)
    tEnv.registerTable(tableName, ds)
    val t = tEnv.scan(tableName).select('_1, '_2, '_3)

    val expected = "1,1,Hi\n" + "2,2,Hello\n" + "3,2,Hello world\n" +
      "4,3,Hello world, how are you?\n" + "5,3,I am fine.\n" + "6,3,Luke Skywalker\n" +
      "7,4,Comment#1\n" + "8,4,Comment#2\n" + "9,4,Comment#3\n" + "10,4,Comment#4\n" +
      "11,5,Comment#5\n" + "12,5,Comment#6\n" + "13,5,Comment#7\n" + "14,5,Comment#8\n" +
      "15,5,Comment#9\n" + "16,6,Comment#10\n" + "17,6,Comment#11\n" + "18,6,Comment#12\n" +
      "19,6,Comment#13\n" + "20,6,Comment#14\n" + "21,6,Comment#15\n"
    val results = t.collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testTableRegister(): Unit = {

    val tableName = "MyTable"
    val t = CollectionBatchExecTable.get3TupleDataSet(tEnv, "a, b, c")
    tEnv.registerTable(tableName, t)

    val regT = tEnv.scan(tableName).select('a, 'b).filter('a > 8)

    val expected = "9,4\n" + "10,4\n" +
      "11,5\n" + "12,5\n" + "13,5\n" + "14,5\n" +
      "15,5\n" + "16,6\n" + "17,6\n" + "18,6\n" +
      "19,6\n" + "20,6\n" + "21,6\n"

    val results = regT.collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testToTable(): Unit = {
    val t = CollectionBatchExecTable.get3TupleDataSet(tEnv, "a, b, c")
      .select('a, 'b, 'c)

    val expected = "1,1,Hi\n" + "2,2,Hello\n" + "3,2,Hello world\n" +
      "4,3,Hello world, how are you?\n" + "5,3,I am fine.\n" + "6,3,Luke Skywalker\n" +
      "7,4,Comment#1\n" + "8,4,Comment#2\n" + "9,4,Comment#3\n" + "10,4,Comment#4\n" +
      "11,5,Comment#5\n" + "12,5,Comment#6\n" + "13,5,Comment#7\n" + "14,5,Comment#8\n" +
      "15,5,Comment#9\n" + "16,6,Comment#10\n" + "17,6,Comment#11\n" + "18,6,Comment#12\n" +
      "19,6,Comment#13\n" + "20,6,Comment#14\n" + "21,6,Comment#15\n"
    val results = t.collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testToTableFromCaseClass(): Unit = {
    val data = List(
      SomeCaseClass("Peter", 28, 4000.00, "Sales"),
      SomeCaseClass("Anna", 56, 10000.00, "Engineering"),
      SomeCaseClass("Lucy", 42, 6000.00, "HR"))

    val t =  tEnv.fromCollection(data, "a, b, c, d")
      .select('a, 'b, 'c, 'd)

    val expected: String =
      "Peter,28,4000.0,Sales\n" +
      "Anna,56,10000.0,Engineering\n" +
      "Lucy,42,6000.0,HR\n"
    val results = t.collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testToTableFromAndToCaseClass(): Unit = {
    val data = List(
      SomeCaseClass("Peter", 28, 4000.00, "Sales"),
      SomeCaseClass("Anna", 56, 10000.00, "Engineering"),
      SomeCaseClass("Lucy", 42, 6000.00, "HR"))

    val t = tEnv.fromCollection(data, "a, b, c, d")
      .select('a, 'b, 'c, 'd)

    val expected: String =
      "SomeCaseClass(Peter,28,4000.0,Sales)\n" +
      "SomeCaseClass(Anna,56,10000.0,Engineering)\n" +
      "SomeCaseClass(Lucy,42,6000.0,HR)\n"
    val results = t.collectAsT(createTypeInformation[SomeCaseClass])
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testInsertIntoMemoryTable(): Unit = {
    MemoryTableSourceSinkUtil.clear

    val t = CollectionBatchExecTable.getSmall3TupleDataSet(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("sourceTable", t)

    val fieldNames = Array("d", "e", "f")
    val fieldTypes = tEnv.scan("sourceTable").getSchema.getTypes.asInstanceOf[Array[DataType]]
    val sink = new MemoryTableSourceSinkUtil.UnsafeMemoryAppendTableSink
    tEnv.registerTableSink("targetTable", fieldNames, fieldTypes, sink)

    tEnv.scan("sourceTable")
      .select('a, 'b, 'c)
      .insertInto("targetTable")
    tEnv.execute()

    val expected = List("1,1,Hi", "2,2,Hello", "3,2,Hello world")
    assertEquals(expected.sorted, MemoryTableSourceSinkUtil.results.sorted)
  }

  @Test
  def testSinkToConsole(): Unit = {
    val data = List(
      SomeCaseClass("Peter", 28, 4000.00, "Sales"),
      SomeCaseClass("Anna", 56, 10000.00, "Engineering"),
      SomeCaseClass("Lucy", 42, 6000.00, "HR"))

    tEnv.registerTable("s", tEnv.fromCollection(data, "a, b, c, d"))
    tEnv.sqlUpdate("insert into console select * from s")
    tEnv.execute()
  }
}

case class SomeCaseClass(name: String, age: Int, salary: Double, department: String) {
  def this() { this("", 0, 0.0, "") }
}
