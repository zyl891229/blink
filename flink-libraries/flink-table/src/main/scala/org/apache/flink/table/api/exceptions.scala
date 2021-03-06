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

package org.apache.flink.table.api

import org.apache.flink.table.catalog.CatalogPartition
import org.apache.flink.table.descriptors.DescriptorProperties
import org.apache.flink.table.factories.{TableFactory => JTableFactory}

import _root_.scala.collection.JavaConverters._

/**
  * Exception for all errors occurring during expression parsing.
  */
case class ExpressionParserException(msg: String) extends RuntimeException(msg)

/**
  * Exception for all errors occurring during sql parsing.
  */
case class SqlParserException(
    msg: String,
    cause: Throwable)
  extends RuntimeException(msg, cause) {

  def this(msg: String) = this(msg, null)

}

/**
  * Exception for unwanted method calling on unresolved expression.
  */
case class UnresolvedException(msg: String) extends RuntimeException(msg)

/**
  * Exception for an operation on a nonexistent table.
  *
  * @param catalog    catalog name
  * @param table      table name
  * @param cause      the cause
  */
case class TableNotExistException(
    catalog: String,
    table: String,
    cause: Throwable)
    extends RuntimeException(s"Table $catalog.$table does not exist.", cause) {

  def this(catalog: String, table: String) = this(catalog, table, null)

}

/**
  * Exception for an operation on a nonexistent function.
  *
  * @param catalog    catalog name
  * @param function   function name
  * @param cause      the cause
  */
case class FunctionNotExistException(
    catalog: String,
    function: String,
    cause: Throwable)
  extends RuntimeException(s"Function $catalog.$function does not exist.", cause) {

  def this(catalog: String, function: String) = this(catalog, function, null)

}

/**
  * Exception for an invalid function.
  * @param catalog catalog name
  * @param functionName function name
  * @param className class name
  * @param cause the cause
  */
case class InvalidFunctionException(
    catalog: String,
    functionName: String,
    className: String,
    cause: Throwable)
    extends RuntimeException(s"Function $functionName, class $className is invalid.", cause) {
}

/**
  * Exception for adding an already existent table
  *
  * @param catalog    catalog name
  * @param table      table name
  * @param cause      the cause
  */
case class TableAlreadyExistException(
    catalog: String,
    table: String,
    cause: Throwable)
    extends RuntimeException(s"Table $catalog.$table already exists.", cause) {

  def this(catalog: String, table: String) = this(catalog, table, null)

}

/**
  * Exception for adding an already existent function
  *
  * @param catalog    catalog name
  * @param function   function name
  * @param cause      the cause
  */
case class FunctionAlreadyExistException(
    catalog: String,
    function: String,
    cause: Throwable)
  extends RuntimeException(s"Function $catalog.$function already exists.", cause) {

  def this(catalog: String, function: String) = this(catalog, function, null)

}

/**
  * Exception for operation on a nonexistent database
  *
  * @param catalog catalog name
  * @param database database name
  * @param cause the cause
  */
case class DatabaseNotExistException(
  catalog: String,
  database: String,
  cause: Throwable)
  extends RuntimeException(s"Database $catalog.$database does not exist.", cause) {

  def this(catalog: String, database: String) = this(catalog, database, null)
}

/**
  * Exception for adding an already existent database
  *
  * @param catalog catalog name
  * @param database database name
  * @param cause the cause
  */
case class DatabaseAlreadyExistException(
  catalog: String,
  database: String,
  cause: Throwable)
  extends RuntimeException(s"Database $catalog.$database already exists.", cause) {

  def this(catalog: String, database: String) = this(catalog, database, null)
}

/**
  * Exception for operation on a nonexistent catalog
  *
  * @param catalog catalog name
  * @param cause the cause
  */
case class CatalogNotExistException(
    catalog: String,
    cause: Throwable)
    extends RuntimeException(s"Catalog $catalog does not exist.", cause) {

  def this(catalog: String) = this(catalog, null)
}

/**
  * Exception for adding an already existent catalog
  *
  * @param catalog catalog name
  * @param cause the cause
  */
case class CatalogAlreadyExistException(
    catalog: String,
    cause: Throwable)
    extends RuntimeException(s"Catalog $catalog already exists.", cause) {

  def this(catalog: String) = this(catalog, null)
}

/**
  * Exception for not finding a [[JTableFactory]] for the given properties.
  *
  * @param message message that indicates the current matching step
  * @param factoryClass required factory class
  * @param factories all found factories
  * @param properties properties that describe the configuration
  * @param cause the cause
  */
case class NoMatchingTableFactoryException(
  message: String,
  factoryClass: Class[_],
  factories: Seq[JTableFactory],
  properties: Map[String, String],
  cause: Throwable)
  extends RuntimeException(
    s"""Could not find a suitable table factory for '${factoryClass.getName}' in
       |the classpath.
       |
        |Reason: $message
       |
        |The following properties are requested:
       |${DescriptorProperties.toString(properties.asJava)}
       |
        |The following factories have been considered:
       |${factories.map(_.getClass.getName).mkString("\n")}
       |""".stripMargin,
    cause) {

  def this(
    message: String,
    factoryClass: Class[_],
    factories: Seq[JTableFactory],
    properties: Map[String, String]) = {
    this(message, factoryClass, factories, properties, null)
  }
}

/**
  * Exception for finding more than one [[JTableFactory]] for the given properties.
  *
  * @param matchingFactories factories that match the properties
  * @param factoryClass required factory class
  * @param factories all found factories
  * @param properties properties that describe the configuration
  * @param cause the cause
  */
case class AmbiguousTableFactoryException(
  matchingFactories: Seq[JTableFactory],
  factoryClass: Class[_],
  factories: Seq[JTableFactory],
  properties: Map[String, String],
  cause: Throwable)
  extends RuntimeException(
    s"""More than one suitable table factory for '${factoryClass.getName}' could
       |be found in the classpath.
       |
        |The following factories match:
       |${matchingFactories.map(_.getClass.getName).mkString("\n")}
       |
        |The following properties are requested:
       |${DescriptorProperties.toString(properties.asJava)}
       |
        |The following factories have been considered:
       |${factories.map(_.getClass.getName).mkString("\n")}
       |""".stripMargin,
    cause) {

  def this(
    matchingFactories: Seq[JTableFactory],
    factoryClass: Class[_],
    factories: Seq[JTableFactory],
    properties: Map[String, String]) = {
    this(matchingFactories, factoryClass, factories, properties, null)
  }
}
