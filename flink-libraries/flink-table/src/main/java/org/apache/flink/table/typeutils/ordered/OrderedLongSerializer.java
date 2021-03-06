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

package org.apache.flink.table.typeutils.ordered;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.base.TypeSerializerSingleton;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;

import java.io.IOException;

/**
 * A serializer for long. The serialized value maintains the sort order of the original value.
 */
@Internal
public final class OrderedLongSerializer extends TypeSerializerSingleton<Long> {

	private static final long serialVersionUID = 1L;

	public static final OrderedLongSerializer ASC_INSTANCE = new OrderedLongSerializer(OrderedBytes.Order.ASCENDING);

	public static final OrderedLongSerializer DESC_INSTANCE = new OrderedLongSerializer(OrderedBytes.Order.DESCENDING);

	private static final Long ZERO = 0L;

	private static final OrderedBytes orderedBytes = new OrderedBytes();

	private final OrderedBytes.Order ord;

	private OrderedLongSerializer(OrderedBytes.Order ord) {
		this.ord = ord;
	}

	@Override
	public boolean isImmutableType() {
		return true;
	}

	@Override
	public Long createInstance() {
		return ZERO;
	}

	@Override
	public Long copy(Long from) {
		return from;
	}

	@Override
	public Long copy(Long from, Long reuse) {
		return from;
	}

	@Override
	public int getLength() {
		return 8;
	}

	@Override
	public void serialize(Long record, DataOutputView target) throws IOException {
		orderedBytes.encodeLong(target, record, ord);
	}

	@Override
	public Long deserialize(DataInputView source) throws IOException {
		return orderedBytes.decodeLong(source, ord);
	}

	@Override
	public Long deserialize(Long reuse, DataInputView source) throws IOException {
		return deserialize(source);
	}

	@Override
	public void copy(DataInputView source, DataOutputView target) throws IOException {
		orderedBytes.encodeLong(target, orderedBytes.decodeLong(source, ord), ord);
	}

	@Override
	public boolean canEqual(Object obj) {
		return obj instanceof OrderedLongSerializer;
	}
}
