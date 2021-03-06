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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Represents a database object in catalog.
 */
public class CatalogDatabase {
	private final Map<String, String> properties;

	public CatalogDatabase() {
		this(new HashMap<>());
	}

	public CatalogDatabase(Map<String, String> properties) {
		this.properties = checkNotNull(properties, "properties cannot be null");
	}

	public Map<String, String> getProperties() {
		return properties;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		CatalogDatabase that = (CatalogDatabase) o;
		return properties.equals(that.properties);
	}

	@Override
	public int hashCode() {
		return Objects.hash(properties);
	}
}
