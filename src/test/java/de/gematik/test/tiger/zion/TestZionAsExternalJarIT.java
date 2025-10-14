/*
 *
 * Copyright 2021-2025 gematik GmbH
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
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.test.tiger.zion;

import static org.assertj.core.api.Assertions.assertThat;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.config.ResetTigerConfiguration;
import de.gematik.test.tiger.testenvmgr.junit.TigerTest;
import kong.unirest.core.HttpResponse;
import kong.unirest.core.JsonNode;
import kong.unirest.core.UnirestInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * To make sure that the tests find the already built tiger-zion-executable.jar we should run them
 * in the integrationtest stage which comes after the package stage.
 */
@ResetTigerConfiguration
class TestZionAsExternalJarIT {

  @BeforeEach
  @AfterEach
  public void resetConfig() {
    TigerGlobalConfiguration.reset();
  }

  @TigerTest(
      tigerYaml =
          """
                        servers:
                          mainServer:
                            type: externalJar
                            healthcheckUrl:
                              http://127.0.0.1:${free.port.30}
                            externalJarOptions:
                              arguments:
                                - --server.port=${free.port.30}
                                - --backendServer.port=${free.port.20}
                                - --spring.profiles.active=mainserver
                              workingDir: src/test/resources
                            source:
                              - local:../../../target/tiger-zion-*-executable.jar
                            startupTimeoutSec: 40
                          backendServer:
                            type: externalJar
                            healthcheckUrl:
                              http://127.0.0.1:${free.port.20}
                            externalJarOptions:
                              arguments:
                                - --server.port=${free.port.20}
                                - --spring.profiles.active=backendserver
                              workingDir: src/test/resources
                            source:
                              - local:../../../target/tiger-zion-*-executable.jar
                            startupTimeoutSec: 40
                        """)
  @Test
  void testMultipleZionServerWithProfiles(UnirestInstance unirestInstance) {
    final HttpResponse<JsonNode> response =
        unirestInstance
            .get(
                TigerGlobalConfiguration.resolvePlaceholders(
                    "http://localhost:${free.port.30}/helloWorld"))
            .header("password", "secret")
            .asJson();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getBody().getObject().getString("Hello")).isEqualTo("World");
  }

  @TigerTest(
      tigerYaml =
          """
              servers:
                zionExternal:
                  type: externalJar
                  healthcheckUrl:
                    http://127.0.0.1:${free.port.10}
                  externalJarOptions:
                    arguments:
                      - --server.port=${free.port.10}
                      - --spring.profiles.active=echoserver
                    workingDir: src/test/resources
                  source:
                    - local:../../../target/tiger-zion-*-executable.jar
              """)
  @Test
  void testExternalZionServer(UnirestInstance unirest) {
    final HttpResponse<JsonNode> response =
        unirest
            .get(
                TigerGlobalConfiguration.resolvePlaceholders(
                    "http://localhost:${free.port.10}/blubBlab/helloWorld"))
            .asJson();

    assertThat(response.getStatus()).isEqualTo(222);
    assertThat(response.getBody().getObject().getString("Hello")).isEqualTo("World");
  }
}
