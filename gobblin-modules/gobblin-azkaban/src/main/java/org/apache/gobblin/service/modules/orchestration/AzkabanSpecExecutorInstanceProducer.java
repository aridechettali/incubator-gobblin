/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gobblin.service.modules.orchestration;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Future;

import org.apache.commons.codec.EncoderException;
import org.apache.gobblin.runtime.api.JobSpec;
import org.apache.gobblin.runtime.api.Spec;
import org.apache.gobblin.runtime.api.SpecExecutorInstanceProducer;
import org.apache.gobblin.util.CompletedFuture;
import org.apache.gobblin.util.ConfigUtils;
import org.slf4j.Logger;

import com.google.common.base.Optional;
import com.typesafe.config.Config;


public class AzkabanSpecExecutorInstanceProducer extends AzkabanSpecExecutorInstance
    implements SpecExecutorInstanceProducer<Spec>, Closeable {

  // Session Id for GaaS User
  private String sessionId;


  public AzkabanSpecExecutorInstanceProducer(Config config, Optional<Logger> log) {
    super(config, log);

    try {
      // Initialize Azkaban client / producer and cache credentials
      String azkabanUsername = config.getString(ServiceAzkabanConfigKeys.AZKABAN_USERNAME_KEY);
      String azkabanPassword = config.getString(ServiceAzkabanConfigKeys.AZKABAN_PASSWORD_KEY);
      String azkabanServerUrl = config.getString(ServiceAzkabanConfigKeys.AZKABAN_SERVER_URL_KEY);

      sessionId = AzkabanAjaxAPIClient.authenticateAndGetSessionId(azkabanUsername, azkabanPassword, azkabanServerUrl);
    } catch (IOException | EncoderException e) {
      throw new RuntimeException("Could not authenticate with Azkaban", e);
    }
  }

  public AzkabanSpecExecutorInstanceProducer(Config config, Logger log) {
    this(config, Optional.of(log));
  }

  /** Constructor with no logging */
  public AzkabanSpecExecutorInstanceProducer(Config config) {
    this(config, Optional.<Logger>absent());
  }

  @Override
  public void close() throws IOException {

  }

  @Override
  public Future<?> addSpec(Spec addedSpec) {
    // If project already exists, execute it

    // If project does not already exists, create and execute it
    AzkabanProjectConfig azkabanProjectConfig = new AzkabanProjectConfig((JobSpec) addedSpec);
    try {
      _log.info("Setting up your Azkaban Project for: " + azkabanProjectConfig.getAzkabanProjectName());

      // Deleted project also returns true if-project-exists check, so optimistically first create the project
      // .. (it will create project if it was never created or deleted), if project exists it will fail with
      // .. appropriate exception message, catch that and run in replace project mode if force overwrite is
      // .. specified
      try {
        createNewAzkabanProject(sessionId, azkabanProjectConfig);
      } catch (IOException e) {
        if ("Project already exists.".equalsIgnoreCase(e.getMessage())) {
          if (ConfigUtils.getBoolean(((JobSpec) addedSpec).getConfig(),
              ServiceAzkabanConfigKeys.AZKABAN_PROJECT_OVERWRITE_IF_EXISTS_KEY, false)) {
            _log.info("Project already exists for this Spec, but force overwrite specified");
            updateExistingAzkabanProject(sessionId, azkabanProjectConfig);
          } else {
            _log.info(String.format("Azkaban project already exists: " + "%smanager?project=%s",
                azkabanProjectConfig.getAzkabanServerUrl(), azkabanProjectConfig.getAzkabanProjectName()));
          }
        } else {
          throw e;
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Issue in setting up Azkaban project.", e);
    }

    return null;
  }

  @Override
  public Future<?> updateSpec(Spec updatedSpec) {
    // Re-create project
    AzkabanProjectConfig azkabanProjectConfig = new AzkabanProjectConfig((JobSpec) updatedSpec);

    try {
      updateExistingAzkabanProject(sessionId, azkabanProjectConfig);
    } catch (IOException e) {
      throw new RuntimeException("Issue in setting up Azkaban project.", e);
    }

    return new CompletedFuture<>(_config, null);
  }

  @Override
  public Future<?> deleteSpec(URI deletedSpecURI) {
    // Delete project
    throw new UnsupportedOperationException();
  }

  @Override
  public Future<? extends List<Spec>> listSpecs() {
    throw new UnsupportedOperationException();
  }

  private void createNewAzkabanProject(String sessionId, AzkabanProjectConfig azkabanProjectConfig) throws IOException {
    // Create Azkaban Job
    String azkabanProjectId = AzkabanJobHelper.createAzkabanJob(sessionId, azkabanProjectConfig);

    // Schedule Azkaban Job
    AzkabanJobHelper.scheduleJob(sessionId, azkabanProjectId, azkabanProjectConfig);

    _log.info(String.format("Azkaban project created: %smanager?project=%s",
        azkabanProjectConfig.getAzkabanServerUrl(), azkabanProjectConfig.getAzkabanProjectName()));
  }

  private void updateExistingAzkabanProject(String sessionId, AzkabanProjectConfig azkabanProjectConfig) throws IOException {
    _log.info(String.format("Updating project: %smanager?project=%s", azkabanProjectConfig.getAzkabanServerUrl(),
        azkabanProjectConfig.getAzkabanProjectName()));

    // Get project Id
    String azkabanProjectId = AzkabanJobHelper.getProjectId(sessionId, azkabanProjectConfig);

    // Replace Azkaban Job
    AzkabanJobHelper.replaceAzkabanJob(sessionId, azkabanProjectId, azkabanProjectConfig);

    // Change schedule
    AzkabanJobHelper.changeJobSchedule(sessionId, azkabanProjectId, azkabanProjectConfig);
  }
}
