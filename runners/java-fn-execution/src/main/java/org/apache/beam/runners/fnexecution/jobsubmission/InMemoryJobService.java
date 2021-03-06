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
package org.apache.beam.runners.fnexecution.jobsubmission;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.beam.model.jobmanagement.v1.JobApi.CancelJobRequest;
import org.apache.beam.model.jobmanagement.v1.JobApi.CancelJobResponse;
import org.apache.beam.model.jobmanagement.v1.JobApi.GetJobStateRequest;
import org.apache.beam.model.jobmanagement.v1.JobApi.GetJobStateResponse;
import org.apache.beam.model.jobmanagement.v1.JobApi.JobMessage;
import org.apache.beam.model.jobmanagement.v1.JobApi.JobMessagesRequest;
import org.apache.beam.model.jobmanagement.v1.JobApi.JobMessagesResponse;
import org.apache.beam.model.jobmanagement.v1.JobApi.JobState;
import org.apache.beam.model.jobmanagement.v1.JobApi.PrepareJobRequest;
import org.apache.beam.model.jobmanagement.v1.JobApi.PrepareJobResponse;
import org.apache.beam.model.jobmanagement.v1.JobApi.RunJobRequest;
import org.apache.beam.model.jobmanagement.v1.JobApi.RunJobResponse;
import org.apache.beam.model.jobmanagement.v1.JobServiceGrpc;
import org.apache.beam.model.pipeline.v1.Endpoints;
import org.apache.beam.runners.core.construction.graph.PipelineValidator;
import org.apache.beam.runners.fnexecution.FnService;
import org.apache.beam.sdk.fn.function.ThrowingConsumer;
import org.apache.beam.sdk.fn.stream.SynchronizedStreamObserver;
import org.apache.beam.vendor.grpc.v1.io.grpc.Status;
import org.apache.beam.vendor.grpc.v1.io.grpc.StatusException;
import org.apache.beam.vendor.grpc.v1.io.grpc.StatusRuntimeException;
import org.apache.beam.vendor.grpc.v1.io.grpc.stub.StreamObserver;
import org.apache.beam.vendor.protobuf.v3.com.google.protobuf.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A InMemoryJobService that prepares and runs jobs on behalf of a client using a {@link
 * JobInvoker}.
 *
 * <p>Job management is handled in-memory rather than any persistent storage, running the risk of
 * leaking jobs if the InMemoryJobService crashes.
 *
 * <p>TODO: replace in-memory job management state with persistent solution.
 */
public class InMemoryJobService extends JobServiceGrpc.JobServiceImplBase implements FnService {
  private static final Logger LOG = LoggerFactory.getLogger(InMemoryJobService.class);

  /**
   * Creates an InMemoryJobService.
   *
   * @param stagingServiceDescriptor Endpoint for the staging service.
   * @param stagingServiceTokenProvider Function mapping a preparationId to a staging service token.
   * @param invoker A JobInvoker that will actually create the jobs.
   * @return A new InMemoryJobService.
   */
  public static InMemoryJobService create(
      Endpoints.ApiServiceDescriptor stagingServiceDescriptor,
      Function<String, String> stagingServiceTokenProvider,
      ThrowingConsumer<String> cleanupJobFn,
      JobInvoker invoker) {
    return new InMemoryJobService(
        stagingServiceDescriptor, stagingServiceTokenProvider, cleanupJobFn, invoker);
  }

  private final ConcurrentMap<String, JobPreparation> preparations;
  private final ConcurrentMap<String, JobInvocation> invocations;
  private final ConcurrentMap<String, String> stagingSessionTokens;
  private final Endpoints.ApiServiceDescriptor stagingServiceDescriptor;
  private final Function<String, String> stagingServiceTokenProvider;
  private final ThrowingConsumer<String> cleanupJobFn;
  private final JobInvoker invoker;

  private InMemoryJobService(
      Endpoints.ApiServiceDescriptor stagingServiceDescriptor,
      Function<String, String> stagingServiceTokenProvider,
      ThrowingConsumer<String> cleanupJobFn,
      JobInvoker invoker) {
    this.stagingServiceDescriptor = stagingServiceDescriptor;
    this.stagingServiceTokenProvider = stagingServiceTokenProvider;
    this.cleanupJobFn = cleanupJobFn;
    this.invoker = invoker;

    this.preparations = new ConcurrentHashMap<>();
    this.invocations = new ConcurrentHashMap<>();

    // Map "preparation ID" to staging token
    this.stagingSessionTokens = new ConcurrentHashMap<>();
  }

  @Override
  public void prepare(
      PrepareJobRequest request, StreamObserver<PrepareJobResponse> responseObserver) {
    try {
      LOG.trace("{} {}", PrepareJobRequest.class.getSimpleName(), request);
      // insert preparation
      String preparationId =
          String.format("%s_%s", request.getJobName(), UUID.randomUUID().toString());
      Struct pipelineOptions = request.getPipelineOptions();
      if (pipelineOptions == null) {
        throw new NullPointerException("Encountered null pipeline options.");
      }
      LOG.trace("PIPELINE OPTIONS {} {}", pipelineOptions.getClass(), pipelineOptions);
      JobPreparation preparation =
          JobPreparation.builder()
              .setId(preparationId)
              .setPipeline(request.getPipeline())
              .setOptions(pipelineOptions)
              .build();
      JobPreparation previous = preparations.putIfAbsent(preparationId, preparation);
      if (previous != null) {
        // this should never happen with a UUID
        String errMessage =
            String.format("A job with the preparation ID \"%s\" already exists.", preparationId);
        StatusException exception = Status.NOT_FOUND.withDescription(errMessage).asException();
        responseObserver.onError(exception);
        return;
      }

      String stagingSessionToken = stagingServiceTokenProvider.apply(preparationId);
      stagingSessionTokens.putIfAbsent(preparationId, stagingSessionToken);

      // send response
      PrepareJobResponse response =
          PrepareJobResponse.newBuilder()
              .setPreparationId(preparationId)
              .setArtifactStagingEndpoint(stagingServiceDescriptor)
              .setStagingSessionToken(stagingSessionToken)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      LOG.error("Could not prepare job with name {}", request.getJobName(), e);
      responseObserver.onError(Status.INTERNAL.withCause(e).asException());
    }
  }

  @Override
  public void run(RunJobRequest request, StreamObserver<RunJobResponse> responseObserver) {
    LOG.trace("{} {}", RunJobRequest.class.getSimpleName(), request);

    String preparationId = request.getPreparationId();
    try {
      // retrieve job preparation
      JobPreparation preparation = preparations.get(preparationId);
      if (preparation == null) {
        String errMessage = String.format("Unknown Preparation Id \"%s\".", preparationId);
        StatusException exception = Status.NOT_FOUND.withDescription(errMessage).asException();
        responseObserver.onError(exception);
        return;
      }
      try {
        PipelineValidator.validate(preparation.pipeline());
      } catch (Exception e) {
        responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT.withCause(e)));
      }

      // create new invocation
      JobInvocation invocation =
          invoker.invoke(
              preparation.pipeline(), preparation.options(), request.getRetrievalToken());
      String invocationId = invocation.getId();

      invocation.addStateListener(
          (state) -> {
            if (!JobInvocation.isTerminated(state)) {
              return;
            }
            String stagingSessionToken = stagingSessionTokens.get(preparationId);
            stagingSessionTokens.remove(preparationId);
            if (cleanupJobFn != null) {
              try {
                cleanupJobFn.accept(stagingSessionToken);
              } catch (Exception e) {
                LOG.error(
                    "Failed to remove job staging directory for token {}: {}",
                    stagingSessionToken,
                    e);
              }
            }
          });

      invocation.start();
      invocations.put(invocationId, invocation);
      RunJobResponse response = RunJobResponse.newBuilder().setJobId(invocationId).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      LOG.warn("Encountered Status Exception", e);
      responseObserver.onError(e);
    } catch (Exception e) {
      String errMessage =
          String.format("Encountered Unexpected Exception for Preparation %s", preparationId);
      LOG.error(errMessage, e);
      responseObserver.onError(Status.INTERNAL.withCause(e).asException());
    }
  }

  @Override
  public void getState(
      GetJobStateRequest request, StreamObserver<GetJobStateResponse> responseObserver) {
    LOG.trace("{} {}", GetJobStateRequest.class.getSimpleName(), request);
    String invocationId = request.getJobId();
    try {
      JobInvocation invocation = getInvocation(invocationId);
      JobState.Enum state = invocation.getState();
      GetJobStateResponse response = GetJobStateResponse.newBuilder().setState(state).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      String errMessage =
          String.format("Encountered Unexpected Exception for Invocation %s", invocationId);
      LOG.error(errMessage, e);
      responseObserver.onError(Status.INTERNAL.withCause(e).asException());
    }
  }

  @Override
  public void cancel(CancelJobRequest request, StreamObserver<CancelJobResponse> responseObserver) {
    LOG.trace("{} {}", CancelJobRequest.class.getSimpleName(), request);
    String invocationId = request.getJobId();
    try {
      JobInvocation invocation = getInvocation(invocationId);
      invocation.cancel();
      JobState.Enum state = invocation.getState();
      CancelJobResponse response = CancelJobResponse.newBuilder().setState(state).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      String errMessage =
          String.format("Encountered Unexpected Exception for Invocation %s", invocationId);
      LOG.error(errMessage, e);
      responseObserver.onError(Status.INTERNAL.withCause(e).asException());
    }
  }

  @Override
  public void getStateStream(
      GetJobStateRequest request, StreamObserver<GetJobStateResponse> responseObserver) {
    LOG.trace("{} {}", GetJobStateRequest.class.getSimpleName(), request);
    String invocationId = request.getJobId();
    try {
      JobInvocation invocation = getInvocation(invocationId);
      Function<JobState.Enum, GetJobStateResponse> responseFunction =
          state -> GetJobStateResponse.newBuilder().setState(state).build();
      Consumer<JobState.Enum> stateListener =
          state -> responseObserver.onNext(responseFunction.apply(state));
      invocation.addStateListener(stateListener);
    } catch (Exception e) {
      String errMessage =
          String.format("Encountered Unexpected Exception for Invocation %s", invocationId);
      LOG.error(errMessage, e);
      responseObserver.onError(Status.INTERNAL.withCause(e).asException());
    }
  }

  @Override
  public void getMessageStream(
      JobMessagesRequest request, StreamObserver<JobMessagesResponse> responseObserver) {
    String invocationId = request.getJobId();
    try {
      JobInvocation invocation = getInvocation(invocationId);
      // synchronization is necessary since we use this stream observer in both the state listener
      // and message listener.
      StreamObserver<JobMessagesResponse> syncResponseObserver =
          SynchronizedStreamObserver.wrapping(responseObserver);
      Consumer<JobState.Enum> stateListener =
          state ->
              syncResponseObserver.onNext(
                  JobMessagesResponse.newBuilder()
                      .setStateResponse(GetJobStateResponse.newBuilder().setState(state).build())
                      .build());
      Consumer<JobMessage> messageListener =
          message ->
              syncResponseObserver.onNext(
                  JobMessagesResponse.newBuilder().setMessageResponse(message).build());

      invocation.addStateListener(stateListener);
      invocation.addMessageListener(messageListener);
    } catch (Exception e) {
      String errMessage =
          String.format("Encountered Unexpected Exception for Invocation %s", invocationId);
      LOG.error(errMessage, e);
      responseObserver.onError(Status.INTERNAL.withCause(e).asException());
    }
  }

  @Override
  public void close() throws Exception {
    // TODO: throw error if jobs are running
  }

  private JobInvocation getInvocation(String invocationId) throws StatusException {
    JobInvocation invocation = invocations.get(invocationId);
    if (invocation == null) {
      throw Status.NOT_FOUND.asException();
    }
    return invocation;
  }
}
