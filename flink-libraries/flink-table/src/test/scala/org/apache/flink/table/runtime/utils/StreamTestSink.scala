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
package org.apache.flink.table.runtime.utils

import java.lang.{Boolean => JBoolean}
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import org.apache.flink.api.common.ExecutionConfig
import org.apache.flink.api.common.functions.MapFunction
import org.apache.flink.api.common.io.OutputFormat
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.api.java.tuple.{Tuple2 => JTuple2}
import org.apache.flink.configuration.Configuration
import org.apache.flink.runtime.state.{FunctionInitializationContext, FunctionSnapshotContext}
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.streaming.api.datastream.{DataStream, DataStreamSink}
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction
import org.apache.flink.table.api._
import org.apache.flink.table.api.types.{RowType, DataType, DataTypes}
import org.apache.flink.table.connector.DefinedDistribution
import org.apache.flink.table.dataformat.util.BaseRowUtil
import org.apache.flink.table.dataformat.{BaseRow, GenericRow}
import org.apache.flink.table.runtime.conversion.DataStructureConverters
import org.apache.flink.table.runtime.utils.JavaPojos.Pojo1
import org.apache.flink.table.sinks.{RetractStreamTableSink, TableSink, _}
import org.apache.flink.table.typeutils.BaseRowTypeInfo
import org.apache.flink.types.Row

import _root_.scala.collection.JavaConverters._
import _root_.scala.collection.mutable
import _root_.scala.collection.mutable.ArrayBuffer

object StreamTestSink {

  TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

  private[utils] val idCounter: AtomicInteger = new AtomicInteger(0)

  private[utils] val globalResults =
    mutable.HashMap.empty[Int, mutable.Map[Int, ArrayBuffer[String]]]
  private[utils] val globalRetractResults =
    mutable.HashMap.empty[Int, mutable.Map[Int, ArrayBuffer[String]]]
  private[utils] val globalUpsertResults =
    mutable.HashMap.empty[Int, mutable.Map[Int, mutable.Map[String, String]]]

  private[utils] def getNewSinkId: Int = {
    val idx = idCounter.getAndIncrement()
    this.synchronized{
      globalResults.put(idx, mutable.HashMap.empty[Int, ArrayBuffer[String]])
      globalRetractResults.put(idx, mutable.HashMap.empty[Int, ArrayBuffer[String]])
      globalUpsertResults.put(idx, mutable.HashMap.empty[Int, mutable.Map[String, String]])
    }
    idx
  }

  def clear(): Unit = {
    globalResults.clear()
    globalRetractResults.clear()
    globalUpsertResults.clear()
  }
}

abstract class AbstractExactlyOnceSink[T] extends RichSinkFunction[T] with CheckpointedFunction {
  protected var resultsState: ListState[String] = _
  protected var localResults: ArrayBuffer[String] = _
  protected val idx: Int = StreamTestSink.getNewSinkId

  protected var globalResults: mutable.Map[Int, ArrayBuffer[String]]= _
  protected var globalRetractResults: mutable.Map[Int, ArrayBuffer[String]] = _
  protected var globalUpsertResults: mutable.Map[Int, mutable.Map[String, String]] = _

  override def initializeState(context: FunctionInitializationContext): Unit = {
    resultsState = context.getOperatorStateStore
      .getListState(new ListStateDescriptor[String]("sink-results", Types.STRING))

    localResults = mutable.ArrayBuffer.empty[String]

    if (context.isRestored) {
      for (value <- resultsState.get().asScala) {
        localResults += value
      }
    }

    val taskId = getRuntimeContext.getIndexOfThisSubtask
    StreamTestSink.synchronized(
      StreamTestSink.globalResults(idx) += (taskId -> localResults)
    )
  }

  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    resultsState.clear()
    for (value <- localResults) {
      resultsState.add(value)
    }
  }

  protected def clearAndStashGlobalResults(): Unit = {
    if (globalResults == null) {
      StreamTestSink.synchronized{
        globalResults = StreamTestSink.globalResults.remove(idx).get
        globalRetractResults = StreamTestSink.globalRetractResults.remove(idx).get
        globalUpsertResults = StreamTestSink.globalUpsertResults.remove(idx).get
      }
    }
  }

  protected def getResults: List[String] = {
    clearAndStashGlobalResults()
    val result = ArrayBuffer.empty[String]
    this.globalResults.foreach {
      case (_, list) => result ++= list
    }
    result.toList
  }
}

final class TestingAppendSink extends AbstractExactlyOnceSink[Row] {
  def invoke(value: Row): Unit = localResults += value.toString
  def getAppendResults: List[String] = getResults
}

final class TestingAppendPojoSink extends AbstractExactlyOnceSink[Pojo1] {
  def invoke(value: Pojo1): Unit = localResults += value.toString
  def getAppendResults: List[String] = getResults
}

final class TestingAppendBaseRowSink(rowTypeInfo: BaseRowTypeInfo)
  extends AbstractExactlyOnceSink[BaseRow] {
  def invoke(value: BaseRow): Unit = localResults += baseRowToString(value, rowTypeInfo)
  def getAppendResults: List[String] = getResults
  def baseRowToString(value: BaseRow, rowTypeInfo: BaseRowTypeInfo): String = {
    val config = new ExecutionConfig
    val fieldTypes = rowTypeInfo.getFieldTypes
    val fieldSerializers = fieldTypes.map(_.createSerializer(config))
    BaseRowUtil.toGenericRow(value, fieldTypes, fieldSerializers).toString
  }
}

class TestingRetractSink extends AbstractExactlyOnceSink[(Boolean, Row)] {
  protected var retractResultsState: ListState[String] = _
  protected var localRetractResults: ArrayBuffer[String] = _

  override def initializeState(context: FunctionInitializationContext): Unit = {
    super.initializeState(context)
    retractResultsState = context.getOperatorStateStore
      .getListState(new ListStateDescriptor[String]("sink-retract-results", Types.STRING))

    localRetractResults = mutable.ArrayBuffer.empty[String]

    if (context.isRestored) {
      for (value <- retractResultsState.get().asScala) {
        localRetractResults += value
      }
    }

    val taskId = getRuntimeContext.getIndexOfThisSubtask
    StreamTestSink.synchronized{
      StreamTestSink.globalRetractResults(idx) += (taskId -> localRetractResults)
    }
  }

  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    super.snapshotState(context)
    retractResultsState.clear()
    for (value <- localRetractResults) {
      retractResultsState.add(value)
    }
  }

  def invoke(v: (Boolean, Row)): Unit = {
    this.synchronized {
      val tupleString = v.toString()
      localResults += tupleString
      val rowString = v._2.toString
      if (v._1) {
        localRetractResults += rowString
      } else {
        val index = localRetractResults.indexOf(rowString)
        if (index >= 0) {
          localRetractResults.remove(index)
        } else {
          throw new RuntimeException("Tried to retract a value that wasn't added first. " +
            "This is probably an incorrectly implemented test. " +
            "Try to set the parallelism of the sink to 1.")
        }
      }
    }
  }

  def getRawResults: List[String] = getResults

  def getRetractResults: List[String] = {
    clearAndStashGlobalResults()
    val result = ArrayBuffer.empty[String]
    this.globalRetractResults.foreach {
      case (_, list) => result ++= list
    }
    result.toList
  }
}

final class TestingUpsertSink(keys: Array[Int])
  extends AbstractExactlyOnceSink[BaseRow] {

  private var upsertResultsState: ListState[String] = _
  private var localUpsertResults: mutable.Map[String, String] = _
  private var fieldTypes: Array[DataType] = _

  def configureTypes(fieldTypes: Array[DataType]): Unit = {
    this.fieldTypes = fieldTypes
  }

  override def initializeState(context: FunctionInitializationContext): Unit = {
    super.initializeState(context)
    upsertResultsState = context.getOperatorStateStore
      .getListState(new ListStateDescriptor[String]("sink-upsert-results", Types.STRING))

    localUpsertResults = mutable.HashMap.empty[String, String]

    if (context.isRestored) {
      var key: String = null
      var value: String = null
      for (entry <- upsertResultsState.get().asScala) {
        if (key == null) {
          key = entry
        } else {
          value = entry
          localUpsertResults += (key -> value)
          key = null
          value = null
        }
      }
      if (key != null) {
        throw new RuntimeException("The resultState is corrupt.")
      }
    }

    val taskId = getRuntimeContext.getIndexOfThisSubtask
    StreamTestSink.synchronized{
      StreamTestSink.globalUpsertResults(idx) += (taskId -> localUpsertResults)
    }
  }

  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    super.snapshotState(context)
    upsertResultsState.clear()
    for ((key, value) <- localUpsertResults) {
      upsertResultsState.add(key)
      upsertResultsState.add(value)
    }
  }

  def invoke(row: BaseRow): Unit = {

    val wrapRow = new GenericRow(2)
    wrapRow.update(0, BaseRowUtil.isAccumulateMsg(row))
    wrapRow.update(1, row)
    val converter = DataStructureConverters.createToExternalConverter(
      DataTypes.createTupleType(DataTypes.BOOLEAN, DataTypes.createRowType(fieldTypes: _*)))
    val v = converter.apply(wrapRow).asInstanceOf[JTuple2[Boolean, Row]]

    val tupleString = v.toString
    localResults += tupleString
    val keyString = Row.project(v.f1, keys).toString
    if (v.f0) {
      localUpsertResults += (keyString -> v.f1.toString)
    } else {
      val oldValue = localUpsertResults.remove(keyString)
      if (oldValue.isEmpty) {
        throw new RuntimeException("Tried to delete a value that wasn't inserted first. " +
          "This is probably an incorrectly implemented test. " +
          "Try to set the parallelism of the sink to 1.")
      }
    }
  }

  def getRawResults: List[String] = getResults

  def getUpsertResults: List[String] = {
    clearAndStashGlobalResults()
    val result = ArrayBuffer.empty[String]
    this.globalUpsertResults.foreach {
      case (_, map) => map.foreach(result += _._2)
    }
    result.toList
  }
}

class TestPartitionalSink(
    index: Int, results: mutable.Map[Int, mutable.HashSet[Any]])
    extends TestingRetractSink {
  protected var taskId: Int = _

  override def open(parameters: Configuration): Unit = {
    taskId = getRuntimeContext.getIndexOfThisSubtask
    val localResults = mutable.HashSet.empty[Any]
    results.synchronized(
      results += (taskId -> localResults)
    )
  }

  override def invoke(value: (Boolean, Row)): Unit = {
    results.synchronized {
      val contain = results.filter(_._1 != taskId).exists(_._2.contains(value._2.getField(index)))
      if (contain) {
        throw new RuntimeException("The same key is assigned into different partitions!")
      }
      results(taskId) += value._2.getField(index)
    }
  }
}

class TestPartitionalOutputFormat[T](
    results: mutable.Map[Int, mutable.HashSet[String]], getIndexKey: (T) => String)
  extends TestingOutputFormat[T] {
  protected var taskId: Int = _

  override def open(taskNumber: Int, numTasks: Int): Unit = {
    taskId = taskNumber
    val localResults = mutable.HashSet.empty[String]
    results.synchronized(
      results += (taskId -> localResults)
    )
  }

  override def writeRecord(value: T): Unit = {
    results.synchronized {
      val contain = results.filter(_._1 != taskId).exists(_._2.contains(getIndexKey(value)))
      if (contain) {
        throw new RuntimeException("The same key is assigned into different partitions!")
      }
      results(taskId) += getIndexKey(value)
    }
  }
}

class TestingOutputFormat[T]
  extends OutputFormat[T] {

  val index: Int = StreamTestSink.getNewSinkId
  var localRetractResults: ArrayBuffer[String] = _

  protected var globalResults: mutable.Map[Int, ArrayBuffer[String]] = _

  def configure(var1: Configuration): Unit = {}

  def open(taskNumber: Int, numTasks: Int): Unit = {
    localRetractResults = mutable.ArrayBuffer.empty[String]
    StreamTestSink.synchronized{
      StreamTestSink.globalResults(index) += (taskNumber -> localRetractResults)
    }
  }

  def writeRecord(value: T): Unit = localRetractResults += value.toString

  def close(): Unit = {}

  protected def clearAndStashGlobalResults(): Unit = {
    if (globalResults == null) {
      StreamTestSink.synchronized{
        globalResults = StreamTestSink.globalResults.remove(index).get
      }
    }
  }

  def getResults: List[String] = {
    clearAndStashGlobalResults()
    val result = ArrayBuffer.empty[String]
    this.globalResults.foreach {
      case (_, list) => result ++= list
    }
    result.toList
  }
}

final class TestingUpsertTableSink(keys: Array[Int])
  extends BaseUpsertStreamTableSink[BaseRow] {
  var fNames: Array[String] = _
  var fTypes: Array[DataType] = _
  var sink = new TestingUpsertSink(keys)

  override def setKeyFields(keys: Array[String]): Unit = {
    // ignore
  }

  override def setIsAppendOnly(isAppendOnly: JBoolean): Unit = {
    // ignore
  }

  override def getOutputType: DataType =
    new RowType(fTypes, fNames)

  override def emitDataStream(dataStream: DataStream[BaseRow]) = {
    dataStream.addSink(sink)
      .name(s"TestingUpsertTableSink(keys=${
        if (keys != null) {
          "(" + keys.mkString(",") + ")"
        } else {
          "null"
        }
      })")
      .setParallelism(1)
  }

  override def getFieldNames: Array[String] = fNames

  override def getFieldTypes: Array[DataType] = fTypes

  override def configure(
    fieldNames: Array[String],
    fieldTypes: Array[DataType])
  : TableSink[BaseRow] = {
    val copy = new TestingUpsertTableSink(keys)
    copy.fNames = fieldNames
    copy.fTypes = fieldTypes
    sink.configureTypes(fieldTypes)
    copy.sink = sink
    copy
  }

  def getRawResults: List[String] = sink.getRawResults

  def getUpsertResults: List[String] = sink.getUpsertResults
}

final class TestingAppendTableSink extends AppendStreamTableSink[Row]
  with BatchTableSink[Row]{
  var fNames: Array[String] = _
  var fTypes: Array[DataType] = _
  var sink = new TestingAppendSink
  var outputFormat = new TestingOutputFormat[Row]

  override def emitDataStream(dataStream: DataStream[Row]) = {
    dataStream.addSink(sink).name("TestingAppendTableSink")
      .setParallelism(dataStream.getParallelism)
  }

  override def emitBoundedStream(
      boundedStream: DataStream[Row],
      tableConfig: TableConfig,
      executionConfig: ExecutionConfig): DataStreamSink[Row] = {
    boundedStream.writeUsingOutputFormat(outputFormat).name("appendTableSink")
  }

  override def getOutputType: DataType = DataTypes.createRowType(fTypes, fNames)

  override def configure(
    fieldNames: Array[String],
    fieldTypes: Array[DataType])
  : TableSink[Row] = {
    val copy = new TestingAppendTableSink
    copy.fNames = fieldNames
    copy.fTypes = fieldTypes
    copy.outputFormat = outputFormat
    copy.sink = sink
    copy
  }

  override def getFieldNames: Array[String] = fNames

  override def getFieldTypes: Array[DataType] = fTypes

  def getAppendResults: List[String] = sink.getAppendResults

  def getResults: List[String] = outputFormat.getResults
}

final class TestingRetractTableSink extends RetractStreamTableSink[Row]
  with BatchCompatibleStreamTableSink[JTuple2[JBoolean, Row]] with DefinedDistribution {

  var fNames: Array[String] = _
  var fTypes: Array[DataType] = _
  var sink = new TestingRetractSink
  var outputFormat = new TestingOutputFormat[JTuple2[JBoolean, Row]]

  var pk: String = _

  def setPartitionalOutput(output: TestingOutputFormat[JTuple2[JBoolean, Row]]): Unit = {
    outputFormat = output
  }

  def setPartitionalSink(sinkFunction: TestingRetractSink): Unit = {
    sink = sinkFunction
  }

  def setPartitionField(p: String): Unit = {
    pk = p
  }

  override def getPartitionField(): String = pk

  override def shuffleEmptyKey(): Boolean = false

  override def emitDataStream(dataStream: DataStream[JTuple2[JBoolean, Row]]) = {
    dataStream.map(new MapFunction[JTuple2[JBoolean, Row], (Boolean, Row)] {
      override def map(value: JTuple2[JBoolean, Row]): (Boolean, Row) = {
        (value.f0, value.f1)
      }
    }).setParallelism(dataStream.getParallelism)
      .addSink(sink)
      .name("TestingRetractTableSink")
      .setParallelism(dataStream.getParallelism)
  }

  override def emitBoundedStream(ds: DataStream[JTuple2[JBoolean, Row]])
  : DataStreamSink[JTuple2[JBoolean, Row]] = {
    ds.writeUsingOutputFormat(outputFormat)
      .name("appendBatchExecSink")
  }

  override def getRecordType: DataType = DataTypes.createRowType(fTypes, fNames)

  override def getFieldNames: Array[String] = fNames

  override def getFieldTypes: Array[DataType] = fTypes

  override def configure(
    fieldNames: Array[String],
    fieldTypes: Array[DataType])
  : TableSink[JTuple2[JBoolean, Row]] = {
    val copy = new TestingRetractTableSink
    copy.fNames = fieldNames
    copy.fTypes = fieldTypes
    copy.sink = sink
    copy.outputFormat = outputFormat
    copy.pk = pk
    copy
  }

  def getRawResults: List[String] = {
    sink.getRawResults
  }

  def getRetractResults: List[String] = {
    sink.getRetractResults
  }

  def getResults: List[String] = {
    outputFormat.getResults
  }
}
