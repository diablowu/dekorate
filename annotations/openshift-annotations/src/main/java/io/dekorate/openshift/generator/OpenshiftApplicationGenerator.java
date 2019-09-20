/**
 * Copyright 2018 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.dekorate.openshift.generator;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;

import io.dekorate.BuildService;
import io.dekorate.BuildServiceFactories;
import io.dekorate.BuildServiceFactory;
import io.dekorate.Generator;
import io.dekorate.Session;
import io.dekorate.SessionListener;
import io.dekorate.WithProject;
import io.dekorate.WithSession;
import io.dekorate.config.AnnotationConfiguration;
import io.dekorate.config.ConfigurationSupplier;
import io.dekorate.config.PropertyConfiguration;
import io.dekorate.deps.kubernetes.api.model.HasMetadata;
import io.dekorate.deps.kubernetes.api.model.KubernetesList;
import io.dekorate.hook.ImageBuildHook;
import io.dekorate.kubernetes.config.ImageConfiguration;
import io.dekorate.kubernetes.config.ImageConfigurationBuilder;
import io.dekorate.openshift.adapter.OpenshiftConfigAdapter;
import io.dekorate.openshift.annotation.OpenshiftApplication;
import io.dekorate.openshift.config.OpenshiftConfig;
import io.dekorate.openshift.config.OpenshiftConfigBuilder;
import io.dekorate.openshift.config.OpenshiftConfigCustomAdapter;
import io.dekorate.openshift.configurator.ApplySourceToImageHook;
import io.dekorate.openshift.handler.OpenshiftHandler;
import io.dekorate.project.ApplyProjectInfo;

public interface OpenshiftApplicationGenerator extends Generator, WithSession, WithProject, SessionListener {

  String OPENSHIFT = "openshift";
  String DEFAULT_S2I_BUILDER_IMAGE = "fabric8/s2i-java:2.3";

  OpenshiftConfig DEFAULT_SOURCE_TO_IMAGE_CONFIG = new OpenshiftConfigBuilder()
    .withBuilderImage(DEFAULT_S2I_BUILDER_IMAGE)
    .build();


  default void add(Element element) {
    OpenshiftApplication openshiftApplication = element.getAnnotation(OpenshiftApplication.class);
    OpenshiftConfig openshiftConfig = OpenshiftConfigCustomAdapter.newBuilder(getProject(), openshiftApplication).build();

    on(new AnnotationConfiguration<>(OpenshiftConfigAdapter.newBuilder(element.getAnnotation(OpenshiftApplication.class))
        .accept(new ApplyProjectInfo(getProject()))
        .accept(new ApplySourceToImageHook(openshiftConfig))));
  }

  default void add(Map map) {
    OpenshiftConfig openshiftConfig = OpenshiftConfigAdapter.newBuilder((Map) map.get(OpenshiftApplication.class.getName())).build();
    on(new PropertyConfiguration<>(OpenshiftConfigAdapter.newBuilder(propertiesMap(map, OpenshiftApplication.class))
        .accept(new ApplyProjectInfo(getProject()))
        .accept(new ApplySourceToImageHook(openshiftConfig))));
  }

    default void on(ConfigurationSupplier<OpenshiftConfig> config) {
      Session session = getSession();
      session.configurators().add(config);
      session.handlers().add(new OpenshiftHandler(session.resources()));
      session.addListener(this);
  }

  default void onClosed() {
    Session session = getSession();
    //We ned to set the TTCL, becuase the KubenretesClient used in this part of code, needs TTCL so that java.util.ServiceLoader can work.
    ClassLoader tccl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      OpenshiftConfig config = session.configurators().get(OpenshiftConfig.class).orElse(DEFAULT_SOURCE_TO_IMAGE_CONFIG);
      String name = session.configurators().get(OpenshiftConfig.class).map(c -> c.getName()).orElse(getProject().getBuildInfo().getName());
      if (config.isAutoBuildEnabled() || config.isAutoDeployEnabled()) {

        ImageConfiguration imageConfiguration = new ImageConfigurationBuilder().withName(config.getName())
            .withGroup(config.getGroup()).withVersion(config.getVersion())
            .build();

        KubernetesList list = session.getGeneratedResources().get("openshift");
        List<HasMetadata> generated = list != null ? list.getItems() : Collections.emptyList();

        BuildServiceFactory buildServiceFactory = BuildServiceFactories.find(getProject(), imageConfiguration)
          .orElseThrow(() -> new IllegalStateException("No applicable BuildServiceFactory found."));
        BuildService buildService = buildServiceFactory.create(getProject(), imageConfiguration, generated);
        new ImageBuildHook(getProject(), buildService).register();
      }
    } finally {
      Thread.currentThread().setContextClassLoader(tccl);
    }
  }

}
