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

package org.apache.flink.table.catalog;

import java.util.Objects;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A database name and object (table/view/function) name combo in a catalog.
 */
public class ObjectPath {
	private final String dbName;
	private final String objectName;

	public ObjectPath(String dbName, String objectName) {
		checkNotNull(dbName, "dbName cannot be null");
		checkNotNull(objectName, "objectName cannot be null");

		this.dbName = dbName;
		this.objectName = objectName;
	}

	public String getDbName() {
		return dbName;
	}

	public String getObjectName() {
		return objectName;
	}

	public String getFullName() {
		return String.format("%s.%s", dbName, objectName);
	}

	public static ObjectPath fromString(String fullName) {
		String[] paths = fullName.split("\\.");

		if (paths.length != 2) {
			throw new IllegalArgumentException(
				String.format("Cannot get split '%s' to get dbName and objectName", fullName));
		}

		return new ObjectPath(paths[0], paths[1]);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		ObjectPath that = (ObjectPath) o;

		return Objects.equals(dbName, that.dbName) &&
			Objects.equals(objectName, that.objectName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(dbName, objectName);
	}

	@Override
	public String toString() {
		return String.format("%s.%s", dbName, objectName);
	}
}
