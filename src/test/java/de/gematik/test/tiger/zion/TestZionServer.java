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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.common.data.config.tigerproxy.TigerConfigurationRoute;
import de.gematik.test.tiger.common.data.config.tigerproxy.TigerProxyConfiguration;
import de.gematik.test.tiger.proxy.TigerProxy;
import de.gematik.test.tiger.testenvmgr.junit.TigerTest;
import de.gematik.test.tiger.zion.config.TigerMockResponse;
import de.gematik.test.tiger.zion.config.TigerMockResponseDescription;
import de.gematik.test.tiger.zion.config.ZionBackendRequestDescription;
import de.gematik.test.tiger.zion.config.ZionConfiguration;
import kong.unirest.core.HttpResponse;
import kong.unirest.core.Unirest;
import kong.unirest.core.UnirestInstance;
import lombok.SneakyThrows;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static de.gematik.rbellogger.data.RbelElementAssertion.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestZionServer {

  @Autowired private ZionConfiguration configuration;
  @LocalServerPort private int port;

  @AfterEach
  void resetMockResponses() {
    configuration.setMockResponses(new HashMap<>());
    configuration.setSpy(null);
  }

  @SneakyThrows
  @Test
  void binaryContent_shouldWork() {
    final byte[] data = DigestUtils.sha256("Hello World".getBytes());
    final String filename = "target/blob.bin";
    FileUtils.writeByteArrayToFile(new File(filename), data);

    configuration.setMockResponses(
        Map.of(
            "test",
            TigerMockResponse.builder()
                .nestedResponses(
                    Map.of(
                        "regularResponse",
                        TigerMockResponse.builder()
                            .response(
                                TigerMockResponseDescription.builder().bodyFile(filename).build())
                            .build()))
                .build()));

    final HttpResponse<byte[]> bytes = Unirest.post("http://localhost:" + port + "/test").asBytes();

    assertThat(bytes.getBody()).isEqualTo(data);
  }

  /**
   * Incoming requests to Zion are modified by the spring framework.
   * A form-urlencoded '%20' gets decoded to a '+' before it gets received by our controller.
   * This makes that the lenght of the body differs from the reported content-length.
   * <p>
   * This test ensures checks that the response is correctly generated when the request includes a '%20'
   * */
  @Test
  void requestWithUrlEncForm(){
    configuration.setMockResponses(
        Map.of(
            "test",
            TigerMockResponse.builder()
                .requestCriterions(List.of("message.method == 'POST'", "message.url =$ '/test'"))
                .response(TigerMockResponseDescription.builder().body("?{$.body.scope}").build())
                .build()));

    HttpResponse<String> response = Unirest.post("http://localhost:" + port + "/test")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body("scope=openid%20profile").asString();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo("openid+profile");
  }

  @SneakyThrows
  @Test
  void zipEncodedContent_shouldWork() {
    final String body = "{\"foo\": \"bar\"}";
    configuration.setMockResponses(
        Map.of(
            "test",
            TigerMockResponse.builder()
                .nestedResponses(
                    Map.of(
                        "regularResponse",
                        TigerMockResponse.builder()
                            .response(
                                TigerMockResponseDescription.builder()
                                    .body(body)
                                    .encoding("gzip")
                                    .build())
                            .build()))
                .build()));

    final int proxyPort =
        TigerGlobalConfiguration.readIntegerOptional("free.port.123").orElseThrow();
    try (var tigerProxy =
        new TigerProxy(
            TigerProxyConfiguration.builder()
                .proxyPort(proxyPort)
                .proxyRoutes(
                    List.of(
                        TigerConfigurationRoute.builder()
                            .from("/")
                            .to("http://localhost:" + port + "/")
                            .build()))
                .build())) {

      final HttpResponse<String> bytes =
          Unirest.post("http://localhost:" + proxyPort + "/test").asString();
      await().until(() -> tigerProxy.getRbelMessagesList().size() == 2);

      assertThat(tigerProxy.getRbelMessagesList().get(1))
          .extractChildWithPath("$.header.[~'content-encoding']")
          .hasStringContentEqualTo("gzip");

      assertThat(bytes.getBody()).isEqualTo(body);
    }
  }

  @SneakyThrows
  @Test
  void backendRequest_withChunkedTransferEncoding_shouldPreserveBody() {
    final String expectedBody = "chunked-response-body";
    final String rawChunkedResponse =
        "HTTP/1.1 200 OK\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "Connection: close\r\n"
            + "\r\n"
            + Integer.toHexString(expectedBody.getBytes(StandardCharsets.UTF_8).length)
            + "\r\n"
            + expectedBody
            + "\r\n0\r\n\r\n";

    try (ServerSocket backendSocket = new ServerSocket(0)) {
      backendSocket.setSoTimeout(5000);
      Thread backendThread =
          new Thread(
              () -> {
                try (Socket socket = backendSocket.accept();
                    InputStream inputStream = socket.getInputStream();
                    OutputStream outputStream = socket.getOutputStream()) {
                  byte[] buffer = new byte[2048];
                  StringBuilder request = new StringBuilder();
                  while (!request.toString().contains("\r\n\r\n")) {
                    int read = inputStream.read(buffer);
                    if (read < 0) {
                      break;
                    }
                    request.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                  }
                  outputStream.write(rawChunkedResponse.getBytes(StandardCharsets.ISO_8859_1));
                  outputStream.flush();
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      backendThread.setDaemon(true);
      backendThread.start();

      configuration.setMockResponses(
          Map.of(
              "chunked",
              TigerMockResponse.builder()
                  .requestCriterions(
                      List.of("message.method == 'GET'", "message.path == '/chunked'"))
                  .backendRequests(
                      Map.of(
                          "chunkedBackendRequest",
                          ZionBackendRequestDescription.builder()
                              .method("GET")
                              .url("http://localhost:" + backendSocket.getLocalPort() + "/chunked")
                              .assignments(Map.of("chunkedBody", "$.body"))
                              .build()))
                  .response(
                      TigerMockResponseDescription.builder()
                          .statusCode("200")
                          .body("${chunkedBody}")
                          .build())
                  .build()));

      final HttpResponse<String> response =
          Unirest.get("http://localhost:" + port + "/chunked").asString();

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getBody()).isEqualTo(expectedBody);
    }
  }

  @TigerTest(
      tigerYaml =
          """
        servers:
          zionServer:
            type: zion
            zionConfiguration:
              serverPort: "${free.port.10}"
              mockResponses:
                 helloExampleCom:
                    requestCriterions:
                      - message.method == 'GET'
                      - message.path == '/helloExampleCom'
                    backendRequests:
                      sayHello:
                        method: GET
                        url: "http://www.example.com"
                        assignments:
                          exampleComResponseBody: "$.body"
                        executeAfterSelection: true
                    response:
                      statusCode: 200
                      body: ${exampleComResponseBody}
        """)
  @Test
  void testZionServer_backendRequest(UnirestInstance unirestInstance) {
    final HttpResponse<String> response200 =
        unirestInstance
            .get(
                TigerGlobalConfiguration.resolvePlaceholders(
                    "http://zionServer/helloExampleCom"))
            .asString();
    assertThat(response200.getStatus()).isEqualTo(200);
    assertThat(response200.getBody()).contains("Example Domain");
  }

}
