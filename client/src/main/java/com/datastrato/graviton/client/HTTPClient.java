/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.datastrato.graviton.client;

import com.datastrato.graviton.dto.responses.BaseResponse;
import com.datastrato.graviton.dto.responses.ErrorType;
import com.datastrato.graviton.exceptions.RESTException;
import com.datastrato.graviton.json.JsonUtils;
import com.datastrato.graviton.rest.RESTRequest;
import com.datastrato.graviton.rest.RESTResponse;
import com.datastrato.graviton.rest.RESTUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.impl.EnglishReasonPhraseCatalog;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HttpClient for usage with the REST catalog.
 *
 * <p>Referred from core/src/main/java/org/apache/iceberg/rest/HTTPClient.java
 */
public class HTTPClient implements RESTClient {

  private static final Logger LOG = LoggerFactory.getLogger(HTTPClient.class);

  private static final String VERSION_HEADER = "application/vnd.graviton.v1+json";

  private final String uri;
  private final CloseableHttpClient httpClient;
  private final ObjectMapper mapper;

  private HTTPClient(String uri, Map<String, String> baseHeaders, ObjectMapper objectMapper) {
    this.uri = uri;
    this.mapper = objectMapper;

    HttpClientBuilder clientBuilder = HttpClients.custom();

    if (baseHeaders != null) {
      clientBuilder.setDefaultHeaders(
          baseHeaders.entrySet().stream()
              .map(e -> new BasicHeader(e.getKey(), e.getValue()))
              .collect(Collectors.toList()));
    }

    this.httpClient = clientBuilder.build();
  }

  private static String extractResponseBodyAsString(CloseableHttpResponse response) {
    try {
      if (response.getEntity() == null) {
        return null;
      }

      // EntityUtils.toString returns null when HttpEntity.getContent returns null.
      return EntityUtils.toString(response.getEntity(), "UTF-8");
    } catch (IOException | ParseException e) {
      throw new RESTException(e, "Failed to convert HTTP response body to string");
    }
  }

  // Per the spec, the only currently defined / used "success" responses are 200 and 202.
  private static boolean isSuccessful(CloseableHttpResponse response) {
    int code = response.getCode();
    return code == HttpStatus.SC_OK
        || code == HttpStatus.SC_ACCEPTED
        || code == HttpStatus.SC_NO_CONTENT;
  }

  private static BaseResponse buildDefaultErrorResponse(CloseableHttpResponse response) {
    String responseReason = response.getReasonPhrase();
    String message =
        responseReason != null && !responseReason.isEmpty()
            ? responseReason
            : EnglishReasonPhraseCatalog.INSTANCE.getReason(response.getCode(), null /* ignored */);
    return BaseResponse.error(ErrorType.DEFAULT_ERROR, message);
  }

  // Process a failed response through the provided errorHandler, and throw a RESTException.java if
  // the
  // provided error handler doesn't already throw.
  private static void throwFailure(
      CloseableHttpResponse response, String responseBody, Consumer<BaseResponse> errorHandler) {
    BaseResponse errorResponse = null;

    if (responseBody != null) {
      try {
        if (errorHandler instanceof ErrorHandler) {
          errorResponse =
              ((ErrorHandler) errorHandler).parseResponse(response.getCode(), responseBody);
        } else {
          LOG.warn(
              "Unknown error handler {}, response body won't be parsed",
              errorHandler.getClass().getName());
          errorResponse = BaseResponse.error(ErrorType.UNKNOWN, responseBody);
        }

      } catch (UncheckedIOException | IllegalArgumentException e) {
        // It's possible to receive a non-successful response that isn't a properly defined
        // BaseResponse
        // without any bugs in the server implementation. So we ignore this exception and build an
        // error response for the user.
        //
        // For example, the connection could time out before every reaching the server, in which
        // case we'll
        // likely get a 5xx with the load balancers default 5xx response.
        LOG.error("Failed to parse an error response. Will create one instead.", e);
      }
    }

    if (errorResponse == null) {
      errorResponse = buildDefaultErrorResponse(response);
    }

    errorHandler.accept(errorResponse);

    // Throw an exception in case the provided error handler does not throw.
    throw new RESTException("Unhandled error: %s", errorResponse);
  }

  private URI buildUri(String path, Map<String, String> params) {
    String baseUri = String.format("%s/%s", uri, path);
    try {
      URIBuilder builder = new URIBuilder(baseUri);
      if (params != null) {
        params.forEach(builder::addParameter);
      }
      return builder.build();
    } catch (URISyntaxException e) {
      throw new RESTException(
          "Failed to create request URI from base %s, params %s", baseUri, params);
    }
  }

  /**
   * Method to execute an HTTP request and process the corresponding response.
   *
   * @param method - HTTP method, such as GET, POST, HEAD, etc.
   * @param queryParams - A map of query parameters
   * @param path - URL path to send the request to
   * @param requestBody - Content to place in the request body
   * @param responseType - Class of the Response type. Needs to have serializer registered with
   *     ObjectMapper
   * @param errorHandler - Error handler delegated for HTTP responses which handles server error
   *     responses
   * @param <T> - Class type of the response for deserialization. Must be registered with the
   *     ObjectMapper.
   * @return The response entity, parsed and converted to its type T
   */
  private <T> T execute(
      Method method,
      String path,
      Map<String, String> queryParams,
      Object requestBody,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler) {
    return execute(
        method, path, queryParams, requestBody, responseType, headers, errorHandler, h -> {});
  }

  /**
   * Method to execute an HTTP request and process the corresponding response.
   *
   * @param method - HTTP method, such as GET, POST, HEAD, etc.
   * @param queryParams - A map of query parameters
   * @param path - URL path to send the request to
   * @param requestBody - Content to place in the request body
   * @param responseType - Class of the Response type. Needs to have serializer registered with
   *     ObjectMapper
   * @param errorHandler - Error handler delegated for HTTP responses which handles server error
   *     responses
   * @param responseHeaders The consumer of the response headers
   * @param <T> - Class type of the response for deserialization. Must be registered with the
   *     ObjectMapper.
   * @return The response entity, parsed and converted to its type T
   */
  private <T> T execute(
      Method method,
      String path,
      Map<String, String> queryParams,
      Object requestBody,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler,
      Consumer<Map<String, String>> responseHeaders) {
    if (path.startsWith("/")) {
      throw new RESTException(
          "Received a malformed path for a REST request: %s. Paths should not start with /", path);
    }

    HttpUriRequestBase request = new HttpUriRequestBase(method.name(), buildUri(path, queryParams));

    if (requestBody instanceof Map) {
      // encode maps as form data, application/x-www-form-urlencoded
      addRequestHeaders(request, headers, ContentType.APPLICATION_FORM_URLENCODED.getMimeType());
      request.setEntity(toFormEncoding((Map<?, ?>) requestBody));
    } else if (requestBody != null) {
      // other request bodies are serialized as JSON, application/json
      addRequestHeaders(request, headers, ContentType.APPLICATION_JSON.getMimeType());
      request.setEntity(toJson(requestBody));
    } else {
      addRequestHeaders(request, headers, ContentType.APPLICATION_JSON.getMimeType());
    }

    try (CloseableHttpResponse response = httpClient.execute(request)) {
      Map<String, String> respHeaders = Maps.newHashMap();
      for (Header header : response.getHeaders()) {
        respHeaders.put(header.getName(), header.getValue());
      }

      responseHeaders.accept(respHeaders);

      // Skip parsing the response stream for any successful request not expecting a response body
      if (response.getCode() == HttpStatus.SC_NO_CONTENT
          || (responseType == null && isSuccessful(response))) {
        return null;
      }

      String responseBody = extractResponseBodyAsString(response);

      if (!isSuccessful(response)) {
        // The provided error handler is expected to throw, but a RESTException.java is thrown if
        // not.
        throwFailure(response, responseBody, errorHandler);
      }

      if (responseBody == null) {
        throw new RESTException(
            "Invalid (null) response body for request (expected %s): method=%s, path=%s, status=%d",
            responseType.getSimpleName(), method.name(), path, response.getCode());
      }

      try {
        return mapper.readValue(responseBody, responseType);
      } catch (JsonProcessingException e) {
        throw new RESTException(
            e,
            "Received a success response code of %d, but failed to parse response body into %s",
            response.getCode(),
            responseType.getSimpleName());
      }
    } catch (IOException e) {
      throw new RESTException(e, "Error occurred while processing %s request", method);
    }
  }

  @Override
  public void head(String path, Map<String, String> headers, Consumer<BaseResponse> errorHandler) {
    execute(Method.HEAD, path, null, null, null, headers, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T get(
      String path,
      Map<String, String> queryParams,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler) {
    return execute(Method.GET, path, queryParams, null, responseType, headers, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T post(
      String path,
      RESTRequest body,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler) {
    return execute(Method.POST, path, null, body, responseType, headers, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T post(
      String path,
      RESTRequest body,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler,
      Consumer<Map<String, String>> responseHeaders) {
    return execute(
        Method.POST, path, null, body, responseType, headers, errorHandler, responseHeaders);
  }

  @Override
  public <T extends RESTResponse> T delete(
      String path,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler) {
    return execute(Method.DELETE, path, null, null, responseType, headers, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T delete(
      String path,
      Map<String, String> queryParams,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler) {
    return execute(Method.DELETE, path, queryParams, null, responseType, headers, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T postForm(
      String path,
      Map<String, String> formData,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<BaseResponse> errorHandler) {
    return execute(Method.POST, path, null, formData, responseType, headers, errorHandler);
  }

  private void addRequestHeaders(
      HttpUriRequest request, Map<String, String> requestHeaders, String bodyMimeType) {
    // Many systems require that content type is set regardless and will fail, even on an empty
    // bodied request.
    request.setHeader(HttpHeaders.CONTENT_TYPE, bodyMimeType);
    request.setHeader(HttpHeaders.ACCEPT, VERSION_HEADER);
    requestHeaders.forEach(request::setHeader);
  }

  @Override
  public void close() throws IOException {
    httpClient.close(CloseMode.GRACEFUL);
  }

  public static Builder builder(Map<String, String> properties) {
    return new Builder(properties);
  }

  public static class Builder {
    private final Map<String, String> properties;
    private final Map<String, String> baseHeaders = Maps.newHashMap();
    private String uri;
    private ObjectMapper mapper = JsonUtils.objectMapper();

    private Builder(Map<String, String> properties) {
      this.properties = properties;
    }

    public Builder uri(String baseUri) {
      Preconditions.checkNotNull(baseUri, "Invalid uri for http client: null");
      this.uri = RESTUtils.stripTrailingSlash(baseUri);
      return this;
    }

    public Builder withHeader(String key, String value) {
      baseHeaders.put(key, value);
      return this;
    }

    public Builder withHeaders(Map<String, String> headers) {
      baseHeaders.putAll(headers);
      return this;
    }

    public Builder withObjectMapper(ObjectMapper objectMapper) {
      this.mapper = objectMapper;
      return this;
    }

    public HTTPClient build() {
      return new HTTPClient(uri, baseHeaders, mapper);
    }
  }

  private StringEntity toJson(Object requestBody) {
    try {
      return new StringEntity(mapper.writeValueAsString(requestBody));
    } catch (JsonProcessingException e) {
      throw new RESTException(e, "Failed to write request body: %s", requestBody);
    }
  }

  private StringEntity toFormEncoding(Map<?, ?> formData) {
    return new StringEntity(RESTUtils.encodeFormData(formData));
  }
}