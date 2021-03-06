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

package org.apache.flink.runtime.resourcemanager;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.blob.BlobServer;
import org.apache.flink.runtime.blob.TransientBlobKey;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.instance.HardwareDescription;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.jobmaster.JobMaster;
import org.apache.flink.runtime.jobmaster.JobMasterId;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.metrics.dump.MetricQueryService;
import org.apache.flink.runtime.registration.RegistrationResponse;
import org.apache.flink.runtime.resourcemanager.placementconstraint.PlacementConstraint;
import org.apache.flink.runtime.rest.messages.taskmanager.TaskManagerInfo;
import org.apache.flink.runtime.rpc.FencedRpcGateway;
import org.apache.flink.runtime.rpc.RpcTimeout;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.TaskExecutor;
import org.apache.flink.runtime.util.FileOffsetRange;

import javax.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link ResourceManager}'s RPC gateway interface.
 */
public interface ResourceManagerGateway extends FencedRpcGateway<ResourceManagerId> {

	/**
	 * Register a {@link JobMaster} at the resource manager.
	 *
	 * @param jobMasterId The fencing token for the JobMaster leader
	 * @param jobMasterResourceId The resource ID of the JobMaster that registers
	 * @param jobMasterAddress The address of the JobMaster that registers
	 * @param jobId The Job ID of the JobMaster that registers
	 * @param timeout Timeout for the future to complete
	 * @return Future registration response
	 */
	CompletableFuture<RegistrationResponse> registerJobManager(
		JobMasterId jobMasterId,
		ResourceID jobMasterResourceId,
		String jobMasterAddress,
		JobID jobId,
		@RpcTimeout Time timeout);

	/**
	 * Set/Update a job's {@link PlacementConstraint}s to the resource manager.
	 * This is a full synchronization, all constraints of a job should be set/updated in one invoke.
	 *
	 * @param jobId ID of the job.
	 * @param constraints Slot placement constraints of the job.
	 * @param timeout Timeout for the acknowledgement.
	 * @return The confirmation that placement constraints are set/updated.
	 */
	CompletableFuture<Acknowledge> setPlacementConstraints(
		JobID jobId,
		List<PlacementConstraint> constraints,
		@RpcTimeout Time timeout);

	/**
	 * Requests a slot from the resource manager.
	 *
	 * @param jobMasterId id of the JobMaster
	 * @param slotRequest The slot to request
	 * @return The confirmation that the slot gets allocated
	 */
	CompletableFuture<Acknowledge> requestSlot(
		JobMasterId jobMasterId,
		SlotRequest slotRequest,
		@RpcTimeout Time timeout);

	/**
	 * Cancel the slot allocation requests from the resource manager.
	 *
	 * @param allocationID The slot to request
	 */
	void cancelSlotRequest(AllocationID allocationID);

	/**
	 * Register a {@link TaskExecutor} at the resource manager.
	 *
	 * @param taskExecutorAddress The address of the TaskExecutor that registers
	 * @param resourceId The resource ID of the TaskExecutor that registers
	 * @param dataPort port used for data communication between TaskExecutors
	 * @param hardwareDescription of the registering TaskExecutor
	 * @param timeout The timeout for the response.
	 *
	 * @return The future to the response by the ResourceManager.
	 */
	CompletableFuture<RegistrationResponse> registerTaskExecutor(
		String taskExecutorAddress,
		ResourceID resourceId,
		int dataPort,
		HardwareDescription hardwareDescription,
		@RpcTimeout Time timeout);

	/**
	 * Sends the given {@link SlotReport} to the ResourceManager.
	 *
	 * @param taskManagerRegistrationId id identifying the sending TaskManager
	 * @param slotReport which is sent to the ResourceManager
	 * @param timeout for the operation
	 * @return Future which is completed with {@link Acknowledge} once the slot report has been received.
	 */
	CompletableFuture<Acknowledge> sendSlotReport(
		ResourceID taskManagerResourceId,
		InstanceID taskManagerRegistrationId,
		SlotReport slotReport,
		@RpcTimeout Time timeout);

	/**
	 * Sent by the TaskExecutor to notify the ResourceManager that a slot has become available.
	 *
	 * @param instanceId TaskExecutor's instance id
	 * @param slotID The SlotID of the freed slot
	 * @param oldAllocationId to which the slot has been allocated
	 */
	void notifySlotAvailable(
		InstanceID instanceId,
		SlotID slotID,
		AllocationID oldAllocationId);

	/**
	 * Registers an infoMessage listener
	 *
	 * @param infoMessageListenerAddress address of infoMessage listener to register to this resource manager
	 */
	void registerInfoMessageListener(String infoMessageListenerAddress);

	/**
	 * Unregisters an infoMessage listener
	 *
	 * @param infoMessageListenerAddress address of infoMessage listener to unregister from this resource manager
	 *
	 */
	void unRegisterInfoMessageListener(String infoMessageListenerAddress);

	/**
	 * Deregister Flink from the underlying resource management system.
	 *
	 * @param finalStatus final status with which to deregister the Flink application
	 * @param diagnostics additional information for the resource management system, can be {@code null}
	 */
	CompletableFuture<Acknowledge> deregisterApplication(final ApplicationStatus finalStatus, @Nullable final String diagnostics);

	/**
	 * Gets the currently registered number of TaskManagers.
	 * 
	 * @return The future to the number of registered TaskManagers.
	 */
	CompletableFuture<Integer> getNumberOfRegisteredTaskManagers();

	/**
	 * Sends the heartbeat to resource manager from task manager
	 *
	 * @param heartbeatOrigin unique id of the task manager
	 * @param slotReport Current slot allocation on the originating TaskManager
	 */
	void heartbeatFromTaskManager(final ResourceID heartbeatOrigin, final SlotReport slotReport);

	/**
	 * Sends the heartbeat to resource manager from job manager
	 *
	 * @param heartbeatOrigin unique id of the job manager
	 */
	void heartbeatFromJobManager(final ResourceID heartbeatOrigin);

	/**
	 * Disconnects a TaskManager specified by the given resourceID from the {@link ResourceManager}.
	 *
	 * @param resourceID identifying the TaskManager to disconnect
	 * @param cause for the disconnection of the TaskManager
	 */
	void disconnectTaskManager(ResourceID resourceID, Exception cause);

	/**
	 * Disconnects a JobManager specified by the given resourceID from the {@link ResourceManager}.
	 *
	 * @param jobId JobID for which the JobManager was the leader
	 * @param cause for the disconnection of the JobManager
	 */
	void disconnectJobManager(JobID jobId, Exception cause);

	/**
	 * Requests information about the registered {@link TaskExecutor}.
	 *
	 * @param timeout of the request
	 * @return Future collection of TaskManager information
	 */
	CompletableFuture<Collection<TaskManagerInfo>> requestTaskManagerInfo(@RpcTimeout Time timeout);

	/**
	 * Requests information about the given {@link TaskExecutor}.
	 *
	 * @param taskManagerId identifying the TaskExecutor for which to return information
	 * @param timeout of the request
	 * @return Future TaskManager information
	 */
	CompletableFuture<TaskManagerInfo> requestTaskManagerInfo(ResourceID taskManagerId, @RpcTimeout Time timeout);
	 
	/**
	 * Requests the resource overview. The resource overview provides information about the
	 * connected TaskManagers, the total number of slots and the number of available slots.
	 *
	 * @param timeout of the request
	 * @return Future containing the resource overview
	 */
	CompletableFuture<ResourceOverview> requestResourceOverview(@RpcTimeout Time timeout);

	/**
	 * Requests the paths for the TaskManager's {@link MetricQueryService} to query.
	 *
	 * @param timeout for the asynchronous operation
	 * @return Future containing the collection of resource ids and the corresponding metric query service path
	 */
	CompletableFuture<Collection<Tuple2<ResourceID, String>>> requestTaskManagerMetricQueryServicePaths(@RpcTimeout Time timeout);

	/**
	 * Request the file upload from the given {@link TaskExecutor} to the cluster's {@link BlobServer}. The
	 * corresponding {@link TransientBlobKey} is returned.
	 *
	 * @param taskManagerId identifying the {@link TaskExecutor} to upload the specified file
	 * @param filename name of the file to upload
	 * @param fileOffsetRange the offset of file
	 * @param timeout for the asynchronous operation
	 * @return Future which is completed with the {@link TransientBlobKey} after uploading the file to the
	 * {@link BlobServer}.
	 */
	CompletableFuture<TransientBlobKey> requestTaskManagerFileUpload(ResourceID taskManagerId, String filename, FileOffsetRange fileOffsetRange, @RpcTimeout Time timeout);

	/**
	 * Request the file upload from the given {@link TaskExecutor} to the cluster's {@link BlobServer}. The
	 * corresponding {@link TransientBlobKey} is returned.
	 *
	 * @param taskManagerId identifying the {@link TaskExecutor} to upload the specified file
	 * @param filename name of the file to upload
	 * @param fileOffsetRange the offset of file
	 * @param timeout for the asynchronous operation
	 * @return Future which is completed with the {@link TransientBlobKey} and file length after uploading the file to the
	 * {@link BlobServer}.
	 */
	CompletableFuture<Tuple2<TransientBlobKey, Long>> requestTaskManagerFileUploadReturnLength(ResourceID taskManagerId, String filename, FileOffsetRange fileOffsetRange, @RpcTimeout Time timeout);

	/**
	 * Request log list from the given {@link TaskExecutor}.
	 * @param taskManagerId identifying the {@link TaskExecutor} to get log list from
	 * @param timeout for the asynchronous operation
	 * @return Future which is completed with the historical log list
	 */
	CompletableFuture<Collection<Tuple2<String, Long>>> requestTaskManagerLogList(ResourceID taskManagerId, @RpcTimeout Time timeout);

	/**
	 * Request jmx port from the given {@link TaskExecutor}.
	 * @param taskManagerId
	 * @param timeout
	 * @return
	 */
	CompletableFuture<Tuple2<String, Long>> requestJmx(ResourceID taskManagerId, @RpcTimeout Time timeout);

	/**
	 * Request log and stdout file name from the given {@link TaskExecutor}.
	 * @param taskManagerId
	 * @param timeout
	 * @return
	 */
	CompletableFuture<Tuple2<String, String>> requestTmLogAndStdoutFileName(ResourceID taskManagerId, @RpcTimeout Time timeout);

}
