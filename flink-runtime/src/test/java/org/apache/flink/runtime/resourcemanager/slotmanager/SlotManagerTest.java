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

package org.apache.flink.runtime.resourcemanager.slotmanager;

import akka.pattern.AskTimeoutException;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple6;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.clusterframework.types.TaskManagerSlot;
import org.apache.flink.runtime.concurrent.Executors;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.resourcemanager.ResourceManagerId;
import org.apache.flink.runtime.resourcemanager.SlotRequest;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.placementconstraint.InterSlotPlacementConstraint;
import org.apache.flink.runtime.resourcemanager.placementconstraint.PlacementConstraint;
import org.apache.flink.runtime.resourcemanager.placementconstraint.SlotTag;
import org.apache.flink.runtime.resourcemanager.placementconstraint.SlotTagScope;
import org.apache.flink.runtime.resourcemanager.placementconstraint.TaggedSlot;
import org.apache.flink.runtime.resourcemanager.placementconstraint.TaggedSlotContext;
import org.apache.flink.runtime.resourcemanager.registration.TaskExecutorConnection;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.SlotStatus;
import org.apache.flink.runtime.taskexecutor.TaskExecutorGateway;
import org.apache.flink.runtime.taskexecutor.TestingTaskExecutorGateway;
import org.apache.flink.runtime.taskexecutor.TestingTaskExecutorGatewayBuilder;
import org.apache.flink.runtime.taskexecutor.exceptions.SlotAllocationException;
import org.apache.flink.runtime.testingUtils.TestingUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.TestLogger;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nonnull;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link SlotManager}.
 */
public class SlotManagerTest extends TestLogger {

	/**
	 * Tests that we can register task manager and their slots at the slot manager.
	 */
	@Test
	public void testTaskManagerRegistration() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		final ResourceID resourceId = ResourceID.generate();
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile);
		final SlotReport slotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			assertTrue("The number registered slots does not equal the expected number.",2 == slotManager.getNumberRegisteredSlots());

			assertNotNull(slotManager.getSlot(slotId1));
			assertNotNull(slotManager.getSlot(slotId2));
		}
	}

	/**
	 * Tests TM reports slot status to RM after RM has failed and restarted.
	 */
	@Test
	public void testTaskManagerRegistrationAfterRMFailover() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);

		final ResourceID resourceId = ResourceID.generate();
		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);

		final JobID jobId = new JobID();
		final AllocationID allocationID = new AllocationID();
		final List<SlotTag> tags = Arrays.asList(new SlotTag("tag-1", jobId), new SlotTag("tag-2", jobId));

		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile, null, null, null, 3L);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile, jobId, allocationID, resourceProfile, tags, 6L);
		final SlotReport slotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			assertEquals("The number registered slots does not equal the expected number.",2, slotManager.getNumberRegisteredSlots());

			TaskManagerSlot slotOne = slotManager.getSlot(slotId1);
			assertNotNull(slotOne);
			assertEquals(TaskManagerSlot.State.FREE, slotOne.getState());
			assertEquals(3L, slotOne.getVersion());

			TaskManagerSlot slotTwo = slotManager.getSlot(slotId2);
			assertNotNull(slotTwo);
			assertEquals(TaskManagerSlot.State.ALLOCATED, slotTwo.getState());
			assertEquals(6L, slotTwo.getVersion());

			assertEquals(tags, slotManager.allocationIdTags.get(allocationID));
		}
	}

	/**
	 * Tests that un-registration of task managers will free and remove all registered slots.
	 */
	@Test
	public void testTaskManagerUnregistration() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final JobID jobId = new JobID();

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			any(JobID.class),
			any(AllocationID.class),
			any(ResourceProfile.class),
			anyString(),
			any(List.class),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(new CompletableFuture<>());

		final ResourceID resourceId = ResourceID.generate();
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);
		final AllocationID allocationId1 = new AllocationID();
		final AllocationID allocationId2 = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile, jobId, allocationId1, resourceProfile, 0L);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile);
		final SlotReport slotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

		final SlotRequest slotRequest = new SlotRequest(
			new JobID(),
			allocationId2,
			resourceProfile,
			"foobar");

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			assertTrue("The number registered slots does not equal the expected number.",2 == slotManager.getNumberRegisteredSlots());

			TaskManagerSlot slot1 = slotManager.getSlot(slotId1);
			TaskManagerSlot slot2 = slotManager.getSlot(slotId2);

			assertTrue(slot1.getState() == TaskManagerSlot.State.ALLOCATED);
			assertTrue(slot2.getState() == TaskManagerSlot.State.FREE);

			assertTrue(slotManager.registerSlotRequest(slotRequest));

			assertFalse(slot2.getState() == TaskManagerSlot.State.FREE);
			assertTrue(slot2.getState() == TaskManagerSlot.State.PENDING);

			PendingSlotRequest pendingSlotRequest = slotManager.getSlotRequest(allocationId2);

			assertTrue("The pending slot request should have been assigned to slot 2", pendingSlotRequest.isAssigned());

			slotManager.unregisterTaskManager(taskManagerConnection.getInstanceID());

			assertTrue(0 == slotManager.getNumberRegisteredSlots());
			assertFalse(pendingSlotRequest.isAssigned());
		}
	}

	/**
	 * Tests that a slot request with no free slots will trigger the resource allocation
	 */
	@Test
	public void testSlotRequestWithoutFreeSlots() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			new JobID(),
			new AllocationID(),
			resourceProfile,
			"localhost");

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {

			slotManager.registerSlotRequest(slotRequest);

			verify(resourceManagerActions).allocateResource(eq(resourceProfile));
		}
	}

	/**
	 * Tests that the slot request fails if we cannot allocate more resources.
	 */
	@Test
	public void testSlotRequestWithResourceAllocationFailure() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			new JobID(),
			new AllocationID(),
			resourceProfile,
			"localhost");

		ResourceActions resourceManagerActions = mock(ResourceActions.class);
		doThrow(new ResourceManagerException("Test exception")).when(resourceManagerActions).allocateResource(any(ResourceProfile.class));

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {

			slotManager.registerSlotRequest(slotRequest);

			fail("The slot request should have failed with a ResourceManagerException.");

		} catch (ResourceManagerException e) {
			// expected exception
		}
	}

	/**
	 * Tests that a slot request which can be fulfilled will trigger a slot allocation.
	 */
	@Test
	public void testSlotRequestWithFreeSlot() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final SlotID slotId = new SlotID(resourceID, 0);
		final String targetAddress = "localhost";
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			jobId,
			allocationId,
			resourceProfile,
			targetAddress);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {

			// accept an incoming slot request
			final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

			final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

			final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
			final SlotReport slotReport = new SlotReport(slotStatus);

			slotManager.registerTaskManager(
				taskExecutorConnection,
				slotReport);

			assertTrue("The slot request should be accepted", slotManager.registerSlotRequest(slotRequest));

			verify(taskExecutorGateway).requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				eq(targetAddress),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals("The slot has not been allocated to the expected allocation id.", allocationId, slot.getAllocationId());
		}
	}

	/**
	 * Checks that un-registering a pending slot request will cancel it, removing it from all
	 * assigned task manager slots and then remove it from the slot manager.
	 */
	@Test
	public void testUnregisterPendingSlotRequest() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final ResourceID resourceID = ResourceID.generate();
		final SlotID slotId = new SlotID(resourceID, 0);
		final AllocationID allocationId = new AllocationID();

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			any(JobID.class),
			any(AllocationID.class),
			any(ResourceProfile.class),
			anyString(),
			eq(Collections.emptyList()),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(new CompletableFuture<>());

		final ResourceProfile resourceProfile = new ResourceProfile(1.0, 1);
		final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
		final SlotReport slotReport = new SlotReport(slotStatus);

		final SlotRequest slotRequest = new SlotRequest(new JobID(), allocationId, resourceProfile, "foobar");

		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			slotManager.registerSlotRequest(slotRequest);

			assertNotNull(slotManager.getSlotRequest(allocationId));

			assertTrue(slot.getState() == TaskManagerSlot.State.PENDING);

			slotManager.unregisterSlotRequest(allocationId);

			assertNull(slotManager.getSlotRequest(allocationId));

			slot = slotManager.getSlot(slotId);
			assertEquals(TaskManagerSlot.State.SYNCING, slot.getState());
		}
	}

	/**
	 * Tests that pending slot requests are tried to be fulfilled upon new slot registrations.
	 */
	@Test
	public void testFulfillingPendingSlotRequest() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final SlotID slotId = new SlotID(resourceID, 0);
		final String targetAddress = "localhost";
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			jobId,
			allocationId,
			resourceProfile,
			targetAddress);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		// accept an incoming slot request
		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			eq(slotId),
			eq(jobId),
			eq(allocationId),
			any(ResourceProfile.class),
			anyString(),
			eq(Collections.emptyList()),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

		final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

		final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
		final SlotReport slotReport = new SlotReport(slotStatus);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {

			assertTrue("The slot request should be accepted", slotManager.registerSlotRequest(slotRequest));

			verify(resourceManagerActions, times(1)).allocateResource(eq(resourceProfile));

			slotManager.registerTaskManager(
				taskExecutorConnection,
				slotReport);

			verify(taskExecutorGateway).requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				eq(targetAddress),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals("The slot has not been allocated to the expected allocation id.", allocationId, slot.getAllocationId());
		}
	}

	/**
	 * Tests that freeing a slot will correctly reset the slot and mark it as a free slot
	 */
	@Test
	public void testFreeSlot() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final SlotID slotId = new SlotID(resourceID, 0);
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		// accept an incoming slot request
		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);

		final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

		final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile, jobId, allocationId, resourceProfile, 0L);
		final SlotReport slotReport = new SlotReport(slotStatus);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {

			slotManager.registerTaskManager(
				taskExecutorConnection,
				slotReport);

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals("The slot has not been allocated to the expected allocation id.", allocationId, slot.getAllocationId());

			// this should be ignored since the allocation id does not match
			slotManager.freeSlot(slotId, new AllocationID());

			assertTrue(slot.getState() == TaskManagerSlot.State.ALLOCATED);
			assertEquals("The slot has not been allocated to the expected allocation id.", allocationId, slot.getAllocationId());

			slotManager.freeSlot(slotId, allocationId);

			assertTrue(slot.getState() == TaskManagerSlot.State.FREE);
			assertNull(slot.getAllocationId());
		}
	}

	/**
	 * Tests that a second pending slot request is detected as a duplicate if the allocation ids are
	 * the same.
	 */
	@Test
	public void testDuplicatePendingSlotRequest() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile1 = new ResourceProfile(1.0, 2);
		final ResourceProfile resourceProfile2 = new ResourceProfile(2.0, 1);
		final SlotRequest slotRequest1 = new SlotRequest(new JobID(), allocationId, resourceProfile1, "foobar");
		final SlotRequest slotRequest2 = new SlotRequest(new JobID(), allocationId, resourceProfile2, "barfoo");

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			assertTrue(slotManager.registerSlotRequest(slotRequest1));
			assertFalse(slotManager.registerSlotRequest(slotRequest2));
		}

		// check that we have only called the resource allocation only for the first slot request,
		// since the second request is a duplicate
		verify(resourceManagerActions, times(1)).allocateResource(any(ResourceProfile.class));
	}

	/**
	 * Tests that if we have received a slot report with some allocated slots, then we don't accept
	 * slot requests with allocated allocation ids.
	 */
	@Test
	public void testDuplicatePendingSlotRequestAfterSlotReport() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final JobID jobId = new JobID();
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(1.0, 1);
		final ResourceID resourceID = ResourceID.generate();
		final SlotID slotId = new SlotID(resourceID, 0);

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

		final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile, jobId, allocationId, resourceProfile, 0L);
		final SlotReport slotReport = new SlotReport(slotStatus);

		final SlotRequest slotRequest = new SlotRequest(jobId, allocationId, resourceProfile, "foobar");

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			assertFalse(slotManager.registerSlotRequest(slotRequest));
		}
	}

	/**
	 * Tests that duplicate slot requests (requests with an already registered allocation id) are
	 * also detected after a pending slot request has been fulfilled but not yet freed.
	 */
	@Test
	public void testDuplicatePendingSlotRequestAfterSuccessfulAllocation() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile1 = new ResourceProfile(1.0, 2);
		final ResourceProfile resourceProfile2 = new ResourceProfile(2.0, 1);
		final SlotRequest slotRequest1 = new SlotRequest(new JobID(), allocationId, resourceProfile1, "foobar");
		final SlotRequest slotRequest2 = new SlotRequest(new JobID(), allocationId, resourceProfile2, "barfoo");

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			any(JobID.class),
			any(AllocationID.class),
			any(ResourceProfile.class),
			anyString(),
			eq(Collections.emptyList()),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

		final ResourceID resourceID = ResourceID.generate();

		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

		final SlotID slotId = new SlotID(resourceID, 0);
		final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile1);
		final SlotReport slotReport = new SlotReport(slotStatus);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskManagerConnection, slotReport);
			assertTrue(slotManager.registerSlotRequest(slotRequest1));

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals("The slot has not been allocated to the expected allocation id.", allocationId, slot.getAllocationId());

			assertFalse(slotManager.registerSlotRequest(slotRequest2));
		}

		// check that we have only called the resource allocation only for the first slot request,
		// since the second request is a duplicate
		verify(resourceManagerActions, never()).allocateResource(any(ResourceProfile.class));
	}

	/**
	 * Tests that an already registered allocation id can be reused after the initial slot request
	 * has been freed.
	 */
	@Test
	public void testAcceptingDuplicateSlotRequestAfterAllocationRelease() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile1 = new ResourceProfile(1.0, 2);
		final ResourceProfile resourceProfile2 = new ResourceProfile(2.0, 1);
		final SlotRequest slotRequest1 = new SlotRequest(new JobID(), allocationId, resourceProfile1, "foobar");
		final SlotRequest slotRequest2 = new SlotRequest(new JobID(), allocationId, resourceProfile2, "barfoo");

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			any(JobID.class),
			any(AllocationID.class),
			any(ResourceProfile.class),
			anyString(),
			any(List.class),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

		final ResourceID resourceID = ResourceID.generate();
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

		final SlotID slotId = new SlotID(resourceID, 0);
		final SlotStatus slotStatus = new SlotStatus(slotId, new ResourceProfile(2.0, 2));
		final SlotReport slotReport = new SlotReport(slotStatus);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskManagerConnection, slotReport);
			assertTrue(slotManager.registerSlotRequest(slotRequest1));

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals("The slot has not been allocated to the expected allocation id.", allocationId, slot.getAllocationId());

			slotManager.freeSlot(slotId, allocationId);

			// check that the slot has been freed
			assertTrue(slot.getState() == TaskManagerSlot.State.FREE);
			assertNull(slot.getAllocationId());

			assertTrue(slotManager.registerSlotRequest(slotRequest2));

			assertEquals("The slot has not been allocated to the expected allocation id.", allocationId, slot.getAllocationId());
		}

		// check that we have only called the resource allocation only for the first slot request,
		// since the second request is a duplicate
		verify(resourceManagerActions, never()).allocateResource(any(ResourceProfile.class));
	}

	/**
	 * Tests that the slot manager ignores slot reports of unknown origin (not registered
	 * task managers).
	 */
	@Test
	public void testReceivingUnknownSlotReport() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);

		final InstanceID unknownInstanceID = new InstanceID();
		final SlotID unknownSlotId = new SlotID(ResourceID.generate(), 0);
		final ResourceProfile unknownResourceProfile = new ResourceProfile(1.0, 1);
		final SlotStatus unknownSlotStatus = new SlotStatus(unknownSlotId, unknownResourceProfile);
		final SlotReport unknownSlotReport = new SlotReport(unknownSlotStatus);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			// check that we don't have any slots registered
			assertTrue(0 == slotManager.getNumberRegisteredSlots());

			// this should not update anything since the instance id is not known to the slot manager
			assertFalse(slotManager.reportSlotStatus(unknownInstanceID, unknownSlotReport));

			assertTrue(0 == slotManager.getNumberRegisteredSlots());
		}
	}

	/**
	 * Tests that slots are updated with respect to the latest incoming slot report. This means that
	 * slots for which a report was received are updated accordingly.
	 */
	@Test
	public void testUpdateSlotReport() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);

		final JobID jobId = new JobID();
		final AllocationID allocationId = new AllocationID();

		final ResourceID resourceId = ResourceID.generate();
		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);


		final ResourceProfile resourceProfile = new ResourceProfile(1.0, 1);
		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile);

		final SlotStatus newSlotStatus2 = new SlotStatus(slotId2, resourceProfile, jobId, allocationId, resourceProfile, 0L);

		final SlotReport slotReport1 = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));
		final SlotReport slotReport2 = new SlotReport(Arrays.asList(newSlotStatus2, slotStatus1));

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			// check that we don't have any slots registered
			assertTrue(0 == slotManager.getNumberRegisteredSlots());

			slotManager.registerTaskManager(taskManagerConnection, slotReport1);

			TaskManagerSlot slot1 = slotManager.getSlot(slotId1);
			TaskManagerSlot slot2 = slotManager.getSlot(slotId2);

			assertTrue(2 == slotManager.getNumberRegisteredSlots());

			assertTrue(slot1.getState() == TaskManagerSlot.State.FREE);
			assertTrue(slot2.getState() == TaskManagerSlot.State.FREE);

			assertTrue(slotManager.reportSlotStatus(taskManagerConnection.getInstanceID(), slotReport2));

			assertTrue(2 == slotManager.getNumberRegisteredSlots());

			assertNotNull(slotManager.getSlot(slotId1));
			assertNotNull(slotManager.getSlot(slotId2));

			// slotId2 should have been allocated for allocationId
			assertEquals(allocationId, slotManager.getSlot(slotId2).getAllocationId());
		}
	}

	/**
	 * Tests that idle task managers time out after the configured timeout. A timed out task manager
	 * will be removed from the slot manager and the resource manager will be notified about the
	 * timeout.
	 */
	@Test
	public void testTaskManagerTimeout() throws Exception {
		final long tmTimeout = 500L;

		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

		final SlotID slotId = new SlotID(resourceID, 0);
		final ResourceProfile resourceProfile = new ResourceProfile(1.0, 1);
		final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
		final SlotReport slotReport = new SlotReport(slotStatus);

		final Executor mainThreadExecutor = TestingUtils.defaultExecutor();

		try (SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			Time.milliseconds(tmTimeout))) {

			slotManager.start(resourceManagerId, mainThreadExecutor, resourceManagerActions);

			mainThreadExecutor.execute(new Runnable() {
				@Override
				public void run() {
					slotManager.registerTaskManager(taskManagerConnection, slotReport);
				}
			});

			verify(resourceManagerActions, timeout(100L * tmTimeout).times(1))
				.releaseResource(eq(taskManagerConnection.getInstanceID()), any(Exception.class));
		}
	}

	/**
	 * Tests that slot requests time out after the specified request timeout. If a slot request
	 * times out, then the request is cancelled, removed from the slot manager and the resource
	 * manager is notified about the failed allocation.
	 */
	@Test
	public void testSlotRequestTimeout() throws Exception {
		final long allocationTimeout = 50L;

		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final JobID jobId = new JobID();
		final AllocationID allocationId = new AllocationID();

		final ResourceProfile resourceProfile = new ResourceProfile(1.0, 1);
		final SlotRequest slotRequest = new SlotRequest(jobId, allocationId, resourceProfile, "foobar");

		final Executor mainThreadExecutor = TestingUtils.defaultExecutor();

		try (SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			Time.milliseconds(allocationTimeout),
			TestingUtils.infiniteTime())) {

			slotManager.start(resourceManagerId, mainThreadExecutor, resourceManagerActions);

			final AtomicReference<Exception> atomicException = new AtomicReference<>(null);

			mainThreadExecutor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						assertTrue(slotManager.registerSlotRequest(slotRequest));
					} catch (Exception e) {
						atomicException.compareAndSet(null, e);
					}
				}
			});

			verify(resourceManagerActions, timeout(100L * allocationTimeout).times(1)).notifyAllocationFailure(
				eq(jobId),
				eq(allocationId),
				any(TimeoutException.class));

			if (atomicException.get() != null) {
				throw atomicException.get();
			}
		}
	}

	/**
	 * Tests that a slot request is retried if it times out on the task manager side
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testTaskManagerSlotRequestTimeoutHandling() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);

		final JobID jobId = new JobID();
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(jobId, allocationId, resourceProfile, "foobar");
		final CompletableFuture<Acknowledge> slotRequestFuture1 = new CompletableFuture<>();
		final CompletableFuture<Acknowledge> slotRequestFuture2 = new CompletableFuture<>();

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			any(JobID.class),
			eq(allocationId),
			eq(resourceProfile),
			anyString(),
			any(List.class),
			any(ResourceManagerId.class),
			anyLong(),
			any(Time.class))).thenReturn(slotRequestFuture1, slotRequestFuture2);

		final ResourceID resourceId = ResourceID.generate();
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);
		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile);
		final SlotReport slotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {

			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			slotManager.registerSlotRequest(slotRequest);

			ArgumentCaptor<SlotID> slotIdCaptor = ArgumentCaptor.forClass(SlotID.class);

			verify(taskExecutorGateway, times(1)).requestSlot(
				slotIdCaptor.capture(),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			TaskManagerSlot failedSlot = slotManager.getSlot(slotIdCaptor.getValue());

			// let the first attempt fail --> this should trigger a second attempt
			slotRequestFuture1.completeExceptionally(new SlotAllocationException("Test exception."));

			verify(taskExecutorGateway, times(2)).requestSlot(
				slotIdCaptor.capture(),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			// the second attempt succeeds
			slotRequestFuture2.complete(Acknowledge.get());

			TaskManagerSlot slot = slotManager.getSlot(slotIdCaptor.getValue());

			assertTrue(slot.getState() == TaskManagerSlot.State.ALLOCATED);
			assertEquals(allocationId, slot.getAllocationId());

			if (!failedSlot.getSlotId().equals(slot.getSlotId())) {
				assertTrue(failedSlot.getState() == TaskManagerSlot.State.FREE);
			}
		}
	}

	/**
	 * Tests that pending slot requests are rejected if a slot report with a different allocation
	 * is received.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testSlotReportWhileActiveSlotRequest() throws Exception {
		final long verifyTimeout = 10000L;
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);

		final JobID jobId = new JobID();
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(jobId, allocationId, resourceProfile, "foobar");
		final CompletableFuture<Acknowledge> slotRequestFuture1 = new CompletableFuture<>();

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			any(JobID.class),
			eq(allocationId),
			eq(resourceProfile),
			anyString(),
			any(List.class),
			any(ResourceManagerId.class),
			anyLong(),
			any(Time.class))).thenReturn(slotRequestFuture1, CompletableFuture.completedFuture(Acknowledge.get()));

		final ResourceID resourceId = ResourceID.generate();
		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);
		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile);
		final SlotReport slotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

		final Executor mainThreadExecutor = TestingUtils.defaultExecutor();

		try (final SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime())) {

			slotManager.start(resourceManagerId, mainThreadExecutor, resourceManagerActions);

			CompletableFuture<Void> registrationFuture = CompletableFuture.supplyAsync(
				() -> {
					slotManager.registerTaskManager(taskManagerConnection, slotReport);

					return null;
				},
				mainThreadExecutor)
			.thenAccept(
				(Object value) -> {
					try {
						slotManager.registerSlotRequest(slotRequest);
					} catch (SlotManagerException e) {
						throw new RuntimeException("Could not register slots.", e);
					}
				});

			// check that no exception has been thrown
			registrationFuture.get();

			ArgumentCaptor<SlotID> slotIdCaptor = ArgumentCaptor.forClass(SlotID.class);

			verify(taskExecutorGateway, times(1)).requestSlot(
				slotIdCaptor.capture(),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class));

			final SlotID requestedSlotId = slotIdCaptor.getValue();
			final SlotID freeSlotId = requestedSlotId.equals(slotId1) ? slotId2 : slotId1;

			CompletableFuture<Boolean> freeSlotFuture = CompletableFuture.supplyAsync(
				() -> slotManager.getSlot(freeSlotId).getState() == TaskManagerSlot.State.FREE,
				mainThreadExecutor);

			assertTrue(freeSlotFuture.get());

			final SlotStatus newSlotStatus1 = new SlotStatus(slotIdCaptor.getValue(), resourceProfile, new JobID(), new AllocationID(), resourceProfile, 1L);
			final SlotStatus newSlotStatus2 = new SlotStatus(freeSlotId, resourceProfile, null, null, null, 0L);
			final SlotReport newSlotReport = new SlotReport(Arrays.asList(newSlotStatus1, newSlotStatus2));

			CompletableFuture<Boolean> reportSlotStatusFuture = CompletableFuture.supplyAsync(
				// this should update the slot with the pending slot request triggering the reassignment of it
				() -> slotManager.reportSlotStatus(taskManagerConnection.getInstanceID(), newSlotReport),
				mainThreadExecutor);

			assertTrue(reportSlotStatusFuture.get());

			verify(taskExecutorGateway, timeout(verifyTimeout).times(2)).requestSlot(
				slotIdCaptor.capture(),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class));

			final SlotID requestedSlotId2 = slotIdCaptor.getValue();

			assertEquals(slotId2, requestedSlotId2);

			CompletableFuture<TaskManagerSlot> requestedSlotFuture = CompletableFuture.supplyAsync(
				() -> slotManager.getSlot(requestedSlotId2),
				mainThreadExecutor);

			TaskManagerSlot slot = requestedSlotFuture.get();

			assertTrue(slot.getState() == TaskManagerSlot.State.ALLOCATED);
			assertEquals(allocationId, slot.getAllocationId());
		}
	}

	/**
	 * Tests that formerly used task managers can again timeout after all of their slots have
	 * been freed.
	 */
	@Test
	public void testTimeoutForUnusedTaskManager() throws Exception {
		final long taskManagerTimeout = 50L;
		final long verifyTimeout = taskManagerTimeout * 10L;

		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final ScheduledExecutor scheduledExecutor = TestingUtils.defaultScheduledExecutor();

		final ResourceID resourceId = ResourceID.generate();

		final JobID jobId = new JobID();
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(1.0, 1);
		final SlotRequest slotRequest = new SlotRequest(jobId, allocationId, resourceProfile, "foobar");

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			eq(jobId),
			eq(allocationId),
			eq(resourceProfile),
			anyString(),
			eq(Collections.emptyList()),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);
		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile);
		final SlotReport initialSlotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

		final Executor mainThreadExecutor = TestingUtils.defaultExecutor();

		try (final SlotManager slotManager = new SlotManager(
			scheduledExecutor,
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			Time.of(taskManagerTimeout, TimeUnit.MILLISECONDS))) {

			slotManager.start(resourceManagerId, mainThreadExecutor, resourceManagerActions);

			CompletableFuture.supplyAsync(
				() -> {
					try {
						return slotManager.registerSlotRequest(slotRequest);
					} catch (SlotManagerException e) {
						throw new CompletionException(e);
					}
				},
				mainThreadExecutor)
			.thenAccept((Object value) -> slotManager.registerTaskManager(taskManagerConnection, initialSlotReport));

			ArgumentCaptor<SlotID> slotIdArgumentCaptor = ArgumentCaptor.forClass(SlotID.class);

			verify(taskExecutorGateway, timeout(verifyTimeout)).requestSlot(
				slotIdArgumentCaptor.capture(),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			CompletableFuture<Boolean> idleFuture = CompletableFuture.supplyAsync(
				() -> slotManager.isTaskManagerIdle(taskManagerConnection.getInstanceID()),
				mainThreadExecutor);

			// check that the TaskManager is not idle
			assertFalse(idleFuture.get());

			final SlotID slotId = slotIdArgumentCaptor.getValue();

			CompletableFuture<TaskManagerSlot> slotFuture = CompletableFuture.supplyAsync(
				() -> slotManager.getSlot(slotId),
				mainThreadExecutor);

			TaskManagerSlot slot = slotFuture.get();

			assertTrue(slot.getState() == TaskManagerSlot.State.ALLOCATED);
			assertEquals(allocationId, slot.getAllocationId());

			CompletableFuture<Boolean> idleFuture2 = CompletableFuture.runAsync(
				() -> slotManager.freeSlot(slotId, allocationId),
				mainThreadExecutor)
			.thenApply((Object value) -> slotManager.isTaskManagerIdle(taskManagerConnection.getInstanceID()));

			assertTrue(idleFuture2.get());

			verify(resourceManagerActions, timeout(verifyTimeout).times(1)).releaseResource(eq(taskManagerConnection.getInstanceID()), any(Exception.class));
		}
	}

	/**
	 * Tests that a task manager timeout does not remove the slots from the SlotManager.
	 * A timeout should only trigger the {@link ResourceActions#releaseResource(InstanceID, Exception)}
	 * callback. The receiver of the callback can then decide what to do with the TaskManager.
	 *
	 * FLINK-7793
	 */
	@Test
	public void testTaskManagerTimeoutDoesNotRemoveSlots() throws Exception {
		final Time taskManagerTimeout = Time.milliseconds(10L);
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final ResourceActions resourceActions = mock(ResourceActions.class);
		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);

		final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);
		final SlotStatus slotStatus = new SlotStatus(
			new SlotID(resourceID, 0),
			new ResourceProfile(1.0, 1));
		final SlotReport initialSlotReport = new SlotReport(slotStatus);

		try (final SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			taskManagerTimeout)) {

			slotManager.start(resourceManagerId, Executors.directExecutor(), resourceActions);

			slotManager.registerTaskManager(taskExecutorConnection, initialSlotReport);

			assertEquals(1, slotManager.getNumberRegisteredSlots());

			// wait for the timeout call to happen
			verify(resourceActions, timeout(taskManagerTimeout.toMilliseconds() * 20L).atLeast(1)).releaseResource(eq(taskExecutorConnection.getInstanceID()), any(Exception.class));

			assertEquals(1, slotManager.getNumberRegisteredSlots());

			slotManager.unregisterTaskManager(taskExecutorConnection.getInstanceID());

			assertEquals(0, slotManager.getNumberRegisteredSlots());
		}
	}

	/**
	 * Tests that free slots which are reported as allocated won't be considered for fulfilling
	 * other pending slot requests.
	 *
	 * <p>See: FLINK-8505
	 */
	@Test
	public void testReportAllocatedSlot() throws Exception {
		final ResourceID taskManagerId = ResourceID.generate();
		final ResourceActions resourceActions = mock(ResourceActions.class);
		final TestingTaskExecutorGateway taskExecutorGateway = new TestingTaskExecutorGatewayBuilder().createTestingTaskExecutorGateway();
		final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(taskManagerId, taskExecutorGateway);

		try (final SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime())) {

			slotManager.start(ResourceManagerId.generate(), Executors.directExecutor(), resourceActions);

			// initially report a single slot as free
			final SlotID slotId = new SlotID(taskManagerId, 0);
			final SlotStatus initialSlotStatus = new SlotStatus(
				slotId,
				ResourceProfile.UNKNOWN);
			final SlotReport initialSlotReport = new SlotReport(initialSlotStatus);

			slotManager.registerTaskManager(taskExecutorConnection, initialSlotReport);

			assertThat(slotManager.getNumberRegisteredSlots(), is(equalTo(1)));

			// Now report this slot as allocated
			final SlotStatus slotStatus = new SlotStatus(
				slotId,
				ResourceProfile.UNKNOWN,
				new JobID(),
				new AllocationID(),
				new ResourceProfile(1, 100),
				0L);
			final SlotReport slotReport = new SlotReport(
				slotStatus);

			slotManager.reportSlotStatus(
				taskExecutorConnection.getInstanceID(),
				slotReport);

			// this slot request should not be fulfilled
			final AllocationID allocationId = new AllocationID();
			final SlotRequest slotRequest = new SlotRequest(
				new JobID(),
				allocationId,
				ResourceProfile.UNKNOWN,
				"foobar");

			// This triggered an IllegalStateException before
			slotManager.registerSlotRequest(slotRequest);

			assertThat(slotManager.getSlotRequest(allocationId).isAssigned(), is(false));
		}
	}

	/**
	 * Tests the slot manager ignores the outdated slot report with a lower version.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testIgnoringOutdatedSlotReport() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final SlotID slotId = new SlotID(resourceID, 0);
		final String targetAddress = "localhost";
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			jobId,
			allocationId,
			resourceProfile,
			targetAddress);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

			final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

			// Create a report with version = 0 and allocationId = null
			final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
			final SlotReport slotReport = new SlotReport(slotStatus);
			assertEquals(0, slotStatus.getVersion());
			assertNull(slotStatus.getAllocationID());

			slotManager.registerTaskManager(
				taskExecutorConnection,
				slotReport);

			// accept an incoming slot request
			assertTrue("The slot request should be accepted", slotManager.registerSlotRequest(slotRequest));

			verify(taskExecutorGateway).requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				eq(targetAddress),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));
			assertEquals(1, slotManager.getSlot(slotId).getVersion());

			slotManager.reportSlotStatus(
				taskExecutorConnection.getInstanceID(),
				slotReport
			);

			// Slot manager should ignore this message
			assertEquals("Outdated FREE status report should be ignored",
				TaskManagerSlot.State.ALLOCATED, slotManager.getSlot(slotId).getState());
		}
	}

	/**
	 * Verify that an syncing slot is updated to ALLOCATED after receiving a valid slot report.
	 */
	@Test
	public void testUpdateToAllocatedInSyncingState() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final SlotID slotId = new SlotID(resourceID, 0);
		final String targetAddress = "localhost";
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			jobId,
			allocationId,
			resourceProfile,
			targetAddress);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (final SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime())) {

			slotManager.start(resourceManagerId, Executors.directExecutor(), resourceManagerActions);

			final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(FutureUtils.completedExceptionally(new AskTimeoutException("Time out!")));

			final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

			final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
			final SlotReport slotReport = new SlotReport(slotStatus);

			slotManager.registerTaskManager(taskExecutorConnection, slotReport);
			slotManager.registerSlotRequest(slotRequest);

			verify(taskExecutorGateway).requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				eq(targetAddress),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals(TaskManagerSlot.State.SYNCING, slot.getState());

			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

			final SlotStatus newSlotStatus = new SlotStatus(slotId, resourceProfile, jobId, allocationId, resourceProfile,1L);
			final SlotReport newSlotReport = new SlotReport(newSlotStatus);
			slotManager.reportSlotStatus(taskExecutorConnection.getInstanceID(), newSlotReport);

			assertEquals(TaskManagerSlot.State.ALLOCATED, slot.getState());
			assertEquals(allocationId, slot.getAllocationId());
		}
	}

	/**
	 * Verify that an syncing slot is updated to FREE after receiving a valid slot report.
	 */
	@Test
	public void testUpdateToFreeInSyncingState() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final SlotID slotId = new SlotID(resourceID, 0);
		final String targetAddress = "localhost";
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			jobId,
			allocationId,
			resourceProfile,
			targetAddress);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (final SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime())) {

			slotManager.start(resourceManagerId, Executors.directExecutor(), resourceManagerActions);

			final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(FutureUtils.completedExceptionally(new AskTimeoutException("Time out!")));

			final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

			final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
			final SlotReport slotReport = new SlotReport(slotStatus);

			slotManager.registerTaskManager(taskExecutorConnection, slotReport);
			slotManager.registerSlotRequest(slotRequest);

			verify(taskExecutorGateway).requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				eq(targetAddress),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals(TaskManagerSlot.State.SYNCING, slot.getState());

			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

			final SlotStatus newSlotStatus = new SlotStatus(slotId, resourceProfile, null, null, null, 1L);
			final SlotReport newSlotReport = new SlotReport(newSlotStatus);
			slotManager.reportSlotStatus(taskExecutorConnection.getInstanceID(), newSlotReport);

			assertEquals(TaskManagerSlot.State.FREE, slot.getState());
			assertNull(slot.getAssignedSlotRequest());
			assertNull(slot.getAllocationId());
		}
	}

	/**
	 * Verify the slot manager re-send the allocation request when received
	 * outdated report.
	 */
	@Test
	public void testOutdatedReportInSyncingState() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final SlotID slotId = new SlotID(resourceID, 0);
		final String targetAddress = "localhost";
		final AllocationID allocationId = new AllocationID();
		final ResourceProfile resourceProfile = new ResourceProfile(42.0, 1337);
		final SlotRequest slotRequest = new SlotRequest(
			jobId,
			allocationId,
			resourceProfile,
			targetAddress);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (final SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime())) {

			slotManager.start(resourceManagerId, Executors.directExecutor(), resourceManagerActions);

			final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(FutureUtils.completedExceptionally(new AskTimeoutException("Time out!")));

			final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

			final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
			final SlotReport slotReport = new SlotReport(slotStatus);

			slotManager.registerTaskManager(taskExecutorConnection, slotReport);
			slotManager.registerSlotRequest(slotRequest);

			verify(taskExecutorGateway).requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				eq(targetAddress),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			TaskManagerSlot slot = slotManager.getSlot(slotId);

			assertEquals(TaskManagerSlot.State.SYNCING, slot.getState());

			when(taskExecutorGateway.requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

			final SlotStatus newSlotStatus = new SlotStatus(slotId, resourceProfile, null, null, null, 0L);
			final SlotReport newSlotReport = new SlotReport(newSlotStatus);
			slotManager.reportSlotStatus(taskExecutorConnection.getInstanceID(), newSlotReport);

			// Verify the request is sent again
			verify(taskExecutorGateway, times(2)).requestSlot(
				eq(slotId),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile),
				eq(targetAddress),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				eq(1L),
				any(Time.class));

			assertEquals(TaskManagerSlot.State.ALLOCATED, slot.getState());
		}
	}
	/**
	 * Tests that the SlotManager retries allocating a slot if the TaskExecutor#requestSlot call
	 * fails.
	 */
	@Test
	public void testSlotRequestFailure() throws Exception {
		try (final SlotManager slotManager = createSlotManager(ResourceManagerId.generate(),
			new TestingResourceActionsBuilder().createTestingResourceActions())) {

			final SlotRequest slotRequest = new SlotRequest(new JobID(), new AllocationID(), ResourceProfile.UNKNOWN, "foobar");
			slotManager.registerSlotRequest(slotRequest);

			final BlockingQueue<Tuple6<SlotID, JobID, AllocationID, String, List<SlotTag>, ResourceManagerId>> requestSlotQueue = new ArrayBlockingQueue<>(1);
			final BlockingQueue<CompletableFuture<Acknowledge>> responseQueue = new ArrayBlockingQueue<>(1);

			final TestingTaskExecutorGateway testingTaskExecutorGateway = new TestingTaskExecutorGatewayBuilder()
				.setRequestSlotFunction(slotIDJobIDAllocationIDStringResourceManagerIdTuple6 -> {
					requestSlotQueue.offer(slotIDJobIDAllocationIDStringResourceManagerIdTuple6);
					try {
						return responseQueue.take();
					} catch (InterruptedException ignored) {
						return FutureUtils.completedExceptionally(new FlinkException("Response queue was interrupted."));
					}
				})
				.createTestingTaskExecutorGateway();

			final ResourceID taskExecutorResourceId = ResourceID.generate();
			final TaskExecutorConnection taskExecutionConnection = new TaskExecutorConnection(taskExecutorResourceId, testingTaskExecutorGateway);
			final SlotReport slotReport = new SlotReport(new SlotStatus(new SlotID(taskExecutorResourceId, 0), ResourceProfile.UNKNOWN));

			final CompletableFuture<Acknowledge> firstManualSlotRequestResponse = new CompletableFuture<>();
			responseQueue.offer(firstManualSlotRequestResponse);

			slotManager.registerTaskManager(taskExecutionConnection, slotReport);

			final Tuple6<SlotID, JobID, AllocationID, String, List<SlotTag>, ResourceManagerId> firstRequest = requestSlotQueue.take();

			final CompletableFuture<Acknowledge> secondManualSlotRequestResponse = new CompletableFuture<>();
			responseQueue.offer(secondManualSlotRequestResponse);

			// fail first request
			firstManualSlotRequestResponse.completeExceptionally(new SlotAllocationException("Test exception"));

			final Tuple6<SlotID, JobID, AllocationID, String, List<SlotTag>, ResourceManagerId> secondRequest = requestSlotQueue.take();

			assertThat(secondRequest.f2, equalTo(firstRequest.f2));
			assertThat(secondRequest.f0, equalTo(firstRequest.f0));

			secondManualSlotRequestResponse.complete(Acknowledge.get());

			final TaskManagerSlot slot = slotManager.getSlot(secondRequest.f0);
			assertThat(slot.getState(), equalTo(TaskManagerSlot.State.ALLOCATED));
			assertThat(slot.getAllocationId(), equalTo(secondRequest.f2));
		}
	}

	/**
	 * Tests notify the job manager of the allocations when the task manager is failed/killed.
	 */
	@Test
	public void testNotifyFailedAllocationWhenTaskManagerTerminated() throws Exception {

		final Queue<Tuple2<JobID, AllocationID>> allocationFailures = new ArrayDeque<>(5);

		final TestingResourceActions resourceManagerActions = new TestingResourceActionsBuilder()
			.setNotifyAllocationFailureConsumer(
				(Tuple3<JobID, AllocationID, Exception> failureMessage) ->
					allocationFailures.offer(Tuple2.of(failureMessage.f0, failureMessage.f1)))
			.createTestingResourceActions();

		try (final SlotManager slotManager = createSlotManager(
			ResourceManagerId.generate(),
			resourceManagerActions)) {

			// register slot request for job1.
			JobID jobId1 = new JobID();
			final SlotRequest slotRequest11 = createSlotRequest(jobId1);
			final SlotRequest slotRequest12 = createSlotRequest(jobId1);
			slotManager.registerSlotRequest(slotRequest11);
			slotManager.registerSlotRequest(slotRequest12);

			// create task-manager-1 with 2 slots.
			final ResourceID taskExecutorResourceId1 = ResourceID.generate();
			final TestingTaskExecutorGateway testingTaskExecutorGateway1 = new TestingTaskExecutorGatewayBuilder().createTestingTaskExecutorGateway();
			final TaskExecutorConnection taskExecutionConnection1 = new TaskExecutorConnection(taskExecutorResourceId1, testingTaskExecutorGateway1);
			final SlotReport slotReport1 = createSlotReport(taskExecutorResourceId1, 2);

			// register the task-manager-1 to the slot manager, this will trigger the slot allocation for job1.
			slotManager.registerTaskManager(taskExecutionConnection1, slotReport1);

			// register slot request for job2.
			JobID jobId2 = new JobID();
			final SlotRequest slotRequest21 = createSlotRequest(jobId2);
			final SlotRequest slotRequest22 = createSlotRequest(jobId2);
			slotManager.registerSlotRequest(slotRequest21);
			slotManager.registerSlotRequest(slotRequest22);

			// register slot request for job3.
			JobID jobId3 = new JobID();
			final SlotRequest slotRequest31 = createSlotRequest(jobId3);
			slotManager.registerSlotRequest(slotRequest31);

			// create task-manager-2 with 3 slots.
			final ResourceID taskExecutorResourceId2 = ResourceID.generate();
			final TestingTaskExecutorGateway testingTaskExecutorGateway2 = new TestingTaskExecutorGatewayBuilder().createTestingTaskExecutorGateway();
			final TaskExecutorConnection taskExecutionConnection2 = new TaskExecutorConnection(taskExecutorResourceId2, testingTaskExecutorGateway2);
			final SlotReport slotReport2 = createSlotReport(taskExecutorResourceId2, 3);

			// register the task-manager-2 to the slot manager, this will trigger the slot allocation for job2 and job3.
			slotManager.registerTaskManager(taskExecutionConnection2, slotReport2);

			// validate for job1.
			slotManager.unregisterTaskManager(taskExecutionConnection1.getInstanceID());

			assertThat(allocationFailures, hasSize(2));

			Tuple2<JobID, AllocationID> allocationFailure;
			final Set<AllocationID> failedAllocations = new HashSet<>(2);

			while ((allocationFailure = allocationFailures.poll()) != null) {
				assertThat(allocationFailure.f0, equalTo(jobId1));
				failedAllocations.add(allocationFailure.f1);
			}

			assertThat(failedAllocations, containsInAnyOrder(slotRequest11.getAllocationId(), slotRequest12.getAllocationId()));

			// validate the result for job2 and job3.
			slotManager.unregisterTaskManager(taskExecutionConnection2.getInstanceID());

			assertThat(allocationFailures, hasSize(3));

			Map<JobID, List<Tuple2<JobID, AllocationID>>> job2AndJob3FailedAllocationInfo = allocationFailures.stream().collect(Collectors.groupingBy(tuple -> tuple.f0));

			assertThat(job2AndJob3FailedAllocationInfo.entrySet(), hasSize(2));

			final Set<AllocationID> job2FailedAllocations = extractFailedAllocationsForJob(jobId2, job2AndJob3FailedAllocationInfo);
			final Set<AllocationID> job3FailedAllocations = extractFailedAllocationsForJob(jobId3, job2AndJob3FailedAllocationInfo);

			assertThat(job2FailedAllocations, containsInAnyOrder(slotRequest21.getAllocationId(), slotRequest22.getAllocationId()));
			assertThat(job3FailedAllocations, containsInAnyOrder(slotRequest31.getAllocationId()));
		}
	}

	/**
	 * Tests that the repeat registration from TM will be handled as reporting slot status.
	 * The report with older version will be ignored.
	 */
	@Test
	public void testTaskManagerRepeatRegistration() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);

		ResourceID resourceId = ResourceID.generate();
		final SlotID slotId1 = new SlotID(resourceId, 0);
		final SlotID slotId2 = new SlotID(resourceId, 1);

		final ResourceProfile resourceProfile1 = new ResourceProfile(42.0, 1337);
		final ResourceProfile resourceProfile2 = new ResourceProfile(43.0, 1338);

		final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile1);
		final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile2);
		final SlotReport slotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

		final JobID jobId = new JobID();
		final AllocationID allocationId = new AllocationID();

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
				eq(slotId2),
				eq(jobId),
				eq(allocationId),
				eq(resourceProfile2),
				anyString(),
				eq(Collections.emptyList()),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

		final TaskExecutorConnection taskManagerConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			// First registration
			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			assertEquals("The number registered slots does not equal the expected number.", 2, slotManager.getNumberRegisteredSlots());
			assertNotNull(slotManager.getSlot(slotId1));
			assertNotNull(slotManager.getSlot(slotId2));

			assertEquals(TaskManagerSlot.State.FREE, slotManager.getSlot(slotId1).getState());
			assertEquals(0L, slotManager.getSlot(slotId1).getVersion());
			assertEquals(TaskManagerSlot.State.FREE, slotManager.getSlot(slotId2).getState());
			assertEquals(0L, slotManager.getSlot(slotId2).getVersion());

			// Allocate an slot
			final SlotRequest slotRequest = new SlotRequest(
					jobId,
					allocationId,
					resourceProfile2,
					"foobar");
			assertTrue(slotManager.registerSlotRequest(slotRequest));

			assertEquals(TaskManagerSlot.State.FREE, slotManager.getSlot(slotId1).getState());
			assertEquals(0L, slotManager.getSlot(slotId1).getVersion());
			assertEquals(TaskManagerSlot.State.ALLOCATED, slotManager.getSlot(slotId2).getState());
			assertEquals(1L, slotManager.getSlot(slotId2).getVersion());
			assertEquals(allocationId, slotManager.getSlot(slotId2).getAllocationId());

			// Repeat registration
			slotManager.registerTaskManager(taskManagerConnection, slotReport);

			// The state of slots should not be merged with timestamp logic
			assertEquals(TaskManagerSlot.State.FREE, slotManager.getSlot(slotId1).getState());
			assertEquals(0L, slotManager.getSlot(slotId1).getVersion());
			assertEquals(TaskManagerSlot.State.ALLOCATED, slotManager.getSlot(slotId2).getState());
			assertEquals(1L, slotManager.getSlot(slotId2).getVersion());
			assertEquals(allocationId, slotManager.getSlot(slotId2).getAllocationId());
		}
	}

	/**
	 * Tests that SlotManager maintains correct tags of slot requests.
	 */
	@Test
	public void testSlotTagOfPendingSlotRequest() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceProfile resourceProfile = new ResourceProfile(1, 100);
		final JobID jobId = new JobID();
		final List<SlotTag> slotTags = Arrays.asList(new SlotTag("tag1", jobId));
		final SlotRequest slotRequest1 = new SlotRequest(
			jobId,
			new AllocationID(),
			resourceProfile,
			"localhost",
			Collections.emptyList());
		final SlotRequest slotRequest2 = new SlotRequest(
			jobId,
			new AllocationID(),
			resourceProfile,
			"localhost",
			slotTags);
		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerSlotRequest(slotRequest1);
			Assert.assertEquals(slotRequest1.getTags(), slotManager.getTagsForSlotRequest(slotRequest1));
			Assert.assertNull(slotManager.getTagsForSlotRequest(slotRequest2));

			slotManager.registerSlotRequest(slotRequest2);
			Assert.assertEquals(slotRequest1.getTags(), slotManager.getTagsForSlotRequest(slotRequest1));
			Assert.assertEquals(slotRequest2.getTags(), slotManager.getTagsForSlotRequest(slotRequest2));
		}
	}

	/**
	 * Tests that SlotManager maintains correct tags of allocated slots.
	 */
	@Test
	public void testSlotTagOfAllocatedSlot() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceProfile resourceProfile = new ResourceProfile(1, 100);
		final ResourceID resourceId = ResourceID.generate();
		final SlotID slotId = new SlotID(resourceId, 0);
		final AllocationID allocationId = new AllocationID();
		final JobID jobId = new JobID();
		final List<SlotTag> slotTags = Arrays.asList(new SlotTag("tag1", jobId));
		final SlotRequest slotRequest = new SlotRequest(
			jobId,
			allocationId,
			resourceProfile,
			"localhost",
			slotTags);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			eq(slotId),
			eq(jobId),
			eq(allocationId),
			eq(resourceProfile),
			anyString(),
			any(List.class),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));
		final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceId, taskExecutorGateway);
		final SlotStatus slotStatus = new SlotStatus(slotId, resourceProfile);
		final SlotReport slotReport = new SlotReport(slotStatus);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {
			slotManager.registerTaskManager(taskExecutorConnection, slotReport);
			Assert.assertEquals(Collections.emptyList(), slotManager.getTagsForTaskExecutor(resourceId));

			assertTrue("The slot request should be accepted", slotManager.registerSlotRequest(slotRequest));
			Assert.assertEquals(Arrays.asList(slotTags), slotManager.getTagsForTaskExecutor(resourceId));
		}
	}

	/**
	 * Test that get total and available resources of cluster or task manager.
	 */
	@Test
	public void testGetTotalAndAvailableResources() {
		ResourceProfile resourceProfile = new ResourceProfile(0.3, 512);

		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceActions resourceManagerActions = mock(ResourceActions.class);
		final JobID jobId = new JobID();
		final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
		when(taskExecutorGateway.requestSlot(
			any(SlotID.class),
			eq(jobId),
			any(AllocationID.class),
			any(ResourceProfile.class),
			anyString(),
			any(List.class),
			eq(resourceManagerId),
			anyLong(),
			any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

		ResourceID resourceId1 = ResourceID.generate();
		TaskExecutorConnection taskExecutorConnection1 = new TaskExecutorConnection(resourceId1, taskExecutorGateway);

		SlotStatus slotStatus1 = new SlotStatus(new SlotID(resourceId1, 0), resourceProfile, jobId, new AllocationID(), resourceProfile, 1L);

		SlotReport slotReport1 = new SlotReport(Collections.singletonList(slotStatus1));

		SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions);

		slotManager.registerTaskManager(taskExecutorConnection1, slotReport1);

		assertEquals(resourceProfile, slotManager.getTotalResource());
		assertEquals(new ResourceProfile(0, 0), slotManager.getAvailableResource());
		assertEquals(resourceProfile, slotManager.getTotalResourceOf(resourceId1));
		assertEquals(new ResourceProfile(0, 0), slotManager.getAvailableResourceOf(resourceId1));

		slotManager.freeSlot(slotStatus1.getSlotID(), slotStatus1.getAllocationID());
		assertEquals(resourceProfile, slotManager.getTotalResource());
		assertEquals(resourceProfile, slotManager.getAvailableResource());
		assertEquals(resourceProfile, slotManager.getTotalResourceOf(resourceId1));
		assertEquals(resourceProfile, slotManager.getAvailableResourceOf(resourceId1));
	}

	/**
	 * Tests that placement constraint check consider slot tags of assigned pending slots
	 */
	@Test
	public void testCheckSlotAssignedRequestTags() throws Exception {
		final ResourceManagerId resourceManagerId = ResourceManagerId.generate();
		final ResourceID resourceID = ResourceID.generate();
		final JobID jobId = new JobID();
		final String targetAddress = "localhost";
		final ResourceProfile resourceProfile = new ResourceProfile(1.0, 1);
		final SlotTag slotTag = new SlotTag("tag", jobId);
		final List<SlotTag> taglist = Arrays.asList(slotTag);

		final SlotID slotId1 = new SlotID(resourceID, 0);
		final SlotID slotId2 = new SlotID(resourceID, 1);
		final AllocationID allocationId1 = new AllocationID();
		final AllocationID allocationId2 = new AllocationID();
		final SlotRequest slotRequest1 = new SlotRequest(
			jobId,
			allocationId1,
			resourceProfile,
			targetAddress,
			taglist);
		final SlotRequest slotRequest2 = new SlotRequest(
			jobId,
			allocationId2,
			resourceProfile,
			targetAddress,
			taglist);

		TaggedSlot slotWithTag = new TaggedSlot(true, taglist, SlotTagScope.JOB);
		TaggedSlotContext contextNotContainSlotWithTag = new TaggedSlotContext(false, slotWithTag);
		InterSlotPlacementConstraint constraint = new InterSlotPlacementConstraint(slotWithTag, contextNotContainSlotWithTag);
		List<PlacementConstraint> placementConstraints = Arrays.asList(constraint);

		ResourceActions resourceManagerActions = mock(ResourceActions.class);

		try (SlotManager slotManager = createSlotManager(resourceManagerId, resourceManagerActions)) {

			// accept an incoming slot request
			final TaskExecutorGateway taskExecutorGateway = mock(TaskExecutorGateway.class);
			when(taskExecutorGateway.requestSlot(
				any(SlotID.class),
				any(JobID.class),
				any(AllocationID.class),
				any(ResourceProfile.class),
				anyString(),
				any(List.class),
				any(ResourceManagerId.class),
				anyLong(),
				any(Time.class))).thenReturn(CompletableFuture.completedFuture(Acknowledge.get()));

			final TaskExecutorConnection taskExecutorConnection = new TaskExecutorConnection(resourceID, taskExecutorGateway);

			final SlotStatus slotStatus1 = new SlotStatus(slotId1, resourceProfile);
			final SlotStatus slotStatus2 = new SlotStatus(slotId2, resourceProfile);
			final SlotReport slotReport = new SlotReport(Arrays.asList(slotStatus1, slotStatus2));

			slotManager.registerTaskManager(
				taskExecutorConnection,
				slotReport);

			slotManager.setJobConstraints(jobId, placementConstraints);

			slotManager.registerSlotRequest(slotRequest1);
			slotManager.registerSlotRequest(slotRequest2);

			verify(taskExecutorGateway, times(1)).requestSlot(
				eq(slotId1),
				eq(jobId),
				eq(allocationId1),
				eq(resourceProfile),
				eq(targetAddress),
				eq(taglist),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class));

			verify(taskExecutorGateway, times(0)).requestSlot(
				eq(slotId2),
				eq(jobId),
				eq(allocationId2),
				eq(resourceProfile),
				eq(targetAddress),
				eq(taglist),
				eq(resourceManagerId),
				anyLong(),
				any(Time.class));
		}
	}

	private Set<AllocationID> extractFailedAllocationsForJob(JobID jobId2, Map<JobID, List<Tuple2<JobID, AllocationID>>> job2AndJob3FailedAllocationInfo) {
		return job2AndJob3FailedAllocationInfo.get(jobId2).stream().map(t -> t.f1).collect(Collectors.toSet());
	}

	@Nonnull
	private SlotReport createSlotReport(ResourceID taskExecutorResourceId, int numberSlots) {
		final Set<SlotStatus> slotStatusSet = new HashSet<>(numberSlots);
		for (int i = 0; i < numberSlots; i++) {
			slotStatusSet.add(new SlotStatus(new SlotID(taskExecutorResourceId, i), ResourceProfile.UNKNOWN));
		}

		return new SlotReport(slotStatusSet);
	}

	@Nonnull
	private SlotRequest createSlotRequest(JobID jobId1) {
		return new SlotRequest(jobId1, new AllocationID(), ResourceProfile.UNKNOWN, "foobar1");
	}

	private SlotManager createSlotManager(ResourceManagerId resourceManagerId, ResourceActions resourceManagerActions) {
		SlotManager slotManager = new SlotManager(
			TestingUtils.defaultScheduledExecutor(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime(),
			TestingUtils.infiniteTime());

		slotManager.start(resourceManagerId, Executors.directExecutor(), resourceManagerActions);

		return slotManager;
	}
}
