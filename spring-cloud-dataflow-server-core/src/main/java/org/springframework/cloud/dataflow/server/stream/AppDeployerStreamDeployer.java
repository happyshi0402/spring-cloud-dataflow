/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.dataflow.server.stream;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.dataflow.core.StreamAppDefinition;
import org.springframework.cloud.dataflow.core.StreamDefinition;
import org.springframework.cloud.dataflow.core.StreamDeployment;
import org.springframework.cloud.dataflow.server.controller.StreamDefinitionController;
import org.springframework.cloud.dataflow.server.repository.DeploymentIdRepository;
import org.springframework.cloud.dataflow.server.repository.DeploymentKey;
import org.springframework.cloud.dataflow.server.repository.StreamDefinitionRepository;
import org.springframework.cloud.dataflow.server.repository.StreamDeploymentRepository;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.app.MultiStateAppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.data.domain.Pageable;
import org.springframework.util.Assert;

import static java.util.stream.Collectors.toList;

/**
 * Uses an AppDeployer instance to deploy the stream.
 *
 * @author Mark Pollack
 * @author Ilayaperumal Gopinathan
 * @author Christian Tzolov
 */
public class AppDeployerStreamDeployer implements StreamDeployer {

	private static Log logger = LogFactory.getLog(AppDeployerStreamDeployer.class);

	private static String deployLoggingString = "Deploying application named [%s] as part of stream named [%s] "
			+ "with resource URI [%s]";

	/**
	 * The deployer this controller will use to deploy stream apps.
	 */
	private final AppDeployer appDeployer;

	/**
	 * The repository this controller will use for deployment IDs.
	 */
	private final DeploymentIdRepository deploymentIdRepository;

	/**
	 * The repository this controller will use for stream CRUD operations.
	 */
	private final StreamDefinitionRepository streamDefinitionRepository;

	/**
	 * The repository for Stream deployments
	 */
	private final StreamDeploymentRepository streamDeploymentRepository;

	/**
	 *
	 */
	private final ForkJoinPool forkJoinPool;

	public AppDeployerStreamDeployer(AppDeployer appDeployer, DeploymentIdRepository deploymentIdRepository,
			StreamDefinitionRepository streamDefinitionRepository,
			StreamDeploymentRepository streamDeploymentRepository, ForkJoinPool forkJoinPool) {
		Assert.notNull(appDeployer, "AppDeployer must not be null");
		Assert.notNull(deploymentIdRepository, "DeploymentIdRepository must not be null");
		Assert.notNull(streamDefinitionRepository, "StreamDefinitionRepository must not be null");
		Assert.notNull(streamDeploymentRepository, "StreamDeploymentRepository must not be null");
		Assert.notNull(forkJoinPool, "ForkJoinPool must not be null");
		this.appDeployer = appDeployer;
		this.deploymentIdRepository = deploymentIdRepository;
		this.streamDefinitionRepository = streamDefinitionRepository;
		this.streamDeploymentRepository = streamDeploymentRepository;
		this.forkJoinPool = forkJoinPool;
	}

	public void deployStream(StreamDeploymentRequest streamDeploymentRequest) {
		for (AppDeploymentRequest appDeploymentRequest : streamDeploymentRequest.getAppDeploymentRequests()) {
			try {
				logger.info(String.format(deployLoggingString, appDeploymentRequest.getDefinition().getName(),
						streamDeploymentRequest.getStreamName(), appDeploymentRequest.getResource().getURI()));
				String id = this.appDeployer.deploy(appDeploymentRequest);
				this.deploymentIdRepository.save(DeploymentKey
								.forAppDeploymentRequest(streamDeploymentRequest.getStreamName(),
										appDeploymentRequest.getDefinition()),
						id);
			}
			catch (Exception e) {
				String errorMessage = String.format(
						"[stream name = %s, application name = %s, application properties = %s",
						streamDeploymentRequest.getStreamName(),
						appDeploymentRequest.getDefinition().getName(),
						appDeploymentRequest.getDefinition().getProperties());
				logger.error(
						String.format("Exception when deploying the app %s: %s", errorMessage, e.getMessage()),
						e);
			}
		}
		StreamDeployment streamDeployment = new StreamDeployment(streamDeploymentRequest.getStreamName(),
				StreamDeployers.appdeployer.name());
		this.streamDeploymentRepository.save(streamDeployment);
	}

	@Override
	public void undeployStream(String streamName) {
		StreamDefinition streamDefinition = this.streamDefinitionRepository.findOne(streamName);
		for (StreamAppDefinition appDefinition : streamDefinition.getAppDefinitions()) {
			String key = DeploymentKey.forStreamAppDefinition(appDefinition);
			String id = this.deploymentIdRepository.findOne(key);
			// if id is null, assume nothing is deployed
			if (id != null) {
				AppStatus status = this.appDeployer.status(id);
				if (!EnumSet.of(DeploymentState.unknown, DeploymentState.undeployed).contains(status.getState())) {
					this.appDeployer.undeploy(id);
				}
				this.deploymentIdRepository.delete(key);
			}
		}
		this.streamDeploymentRepository.delete(streamDefinition.getName());
	}

	@Override
	public String calculateStreamState(String streamName) {
		Set<DeploymentState> appStates = EnumSet.noneOf(DeploymentState.class);
		StreamDefinition stream = this.streamDefinitionRepository.findOne(streamName);
		for (StreamAppDefinition appDefinition : stream.getAppDefinitions()) {
			String key = DeploymentKey.forStreamAppDefinition(appDefinition);
			String id = this.deploymentIdRepository.findOne(key);
			if (id != null) {
				AppStatus status = this.appDeployer.status(id);
				appStates.add(status.getState());
			}
			else {
				appStates.add(DeploymentState.undeployed);
			}
		}
		return StreamDefinitionController.aggregateState(appStates).toString();
	}

	@Override
	public Map<StreamDefinition, DeploymentState> state(List<StreamDefinition> streamDefinitions) {
		Map<StreamDefinition, List<String>> deploymentIdsPerStream = streamDefinitions.stream()
				.collect(Collectors.toMap(Function.identity(),
						sd -> sd.getAppDefinitions().stream().map(
								sad -> deploymentIdRepository.findOne(DeploymentKey.forStreamAppDefinition(sad)))
								.collect(Collectors.toList())));

		// Map from app deployment id to state
		Map<String, DeploymentState> statePerApp = gatherDeploymentStates(deploymentIdsPerStream.values().stream()
				.flatMap(Collection::stream).filter(Objects::nonNull).toArray(String[]::new));

		// Map from SCDF Stream to aggregate state
		return deploymentIdsPerStream.entrySet().stream()
				.map(kv -> new AbstractMap.SimpleImmutableEntry<>(kv.getKey(),
						StreamDefinitionController.aggregateState(kv.getValue().stream()
								.map(deploymentId -> statePerApp.getOrDefault(deploymentId, DeploymentState.unknown))
								.collect(Collectors.toSet()))))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private Map<String, DeploymentState> gatherDeploymentStates(String... ids) {
		if (appDeployer instanceof MultiStateAppDeployer) {
			return ((MultiStateAppDeployer) appDeployer).states(ids);
		}
		else {
			return Arrays.stream(ids)
					.collect(Collectors.toMap(Function.identity(), id -> appDeployer.status(id).getState()));
		}
	}

	@Override
	public List<AppStatus> getAppStatuses(Pageable pageable) throws ExecutionException, InterruptedException {

		Iterable<StreamDefinition> streamDefinitions = this.streamDefinitionRepository.findAll();
		Iterable<StreamDeployment> streamDeployments = this.streamDeploymentRepository.findAll();

		List<String> appDeployerStreams = new ArrayList<>();
		for (StreamDeployment streamDeployment : streamDeployments) {
			appDeployerStreams.add(streamDeployment.getStreamName());

		}

		List<StreamDefinition> appDeployerStreamDefinitions = new ArrayList<>();
		for (StreamDefinition streamDefinition : streamDefinitions) {
			if (appDeployerStreams.contains(streamDefinition.getName())) {
				appDeployerStreamDefinitions.add(streamDefinition);
			}
		}

		// First build a sorted list of deployment id's so that we have a predictable paging order.
		List<String> deploymentIds = appDeployerStreamDefinitions.stream()
				.flatMap(sd -> sd.getAppDefinitions().stream()).flatMap(sad -> {
					String key = DeploymentKey.forStreamAppDefinition(sad);
					String id = this.deploymentIdRepository.findOne(key);
					return id != null ? Stream.of(id) : Stream.empty();
				}).sorted(String::compareTo).collect(toList());

		// Running this this inside the FJP will make sure it is used by the parallel stream
		// Skip first items depending on page size, then take page and discard rest.
		// todo: Use correct pageable values based on the number of apps deployed via all supported StreamDeployers
		return this.forkJoinPool.submit(() -> deploymentIds.stream()
				.skip(pageable.getPageNumber() * pageable.getPageSize())
				.limit(pageable.getPageSize()).parallel().map(appDeployer::status).collect(toList()))
				.get();
	}

	@Override
	public AppStatus getAppStatus(String id) {
		return appDeployer.status(id);
	}

	@Override
	public RuntimeEnvironmentInfo environmentInfo() {
		return appDeployer.environmentInfo();
	}
}
