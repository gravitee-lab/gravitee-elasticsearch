/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.elasticsearch.client.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.elasticsearch.client.Client;
import io.gravitee.elasticsearch.config.Endpoint;
import io.gravitee.elasticsearch.exception.ElasticsearchException;
import io.gravitee.elasticsearch.model.CountResponse;
import io.gravitee.elasticsearch.model.Health;
import io.gravitee.elasticsearch.model.Response;
import io.gravitee.elasticsearch.model.SearchResponse;
import io.gravitee.elasticsearch.model.bulk.BulkResponse;
import io.gravitee.elasticsearch.version.ElasticsearchInfo;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.impl.WebClientInternal;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static java.lang.String.format;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpClient implements Client {

    /**
     * Logger.
     */
    private final Logger logger = LoggerFactory.getLogger(HttpClient.class);

    private static final String HTTPS_SCHEME = "https";
    private static final String CONTENT_TYPE = MediaType.APPLICATION_JSON + ";charset=UTF-8";

    private static String URL_ROOT;
    private static String URL_STATE_CLUSTER;
    private static String URL_BULK;
    private static String URL_TEMPLATE;
    private static String URL_INGEST;
    private static String URL_SEARCH;
    private static String URL_COUNT;

    @Autowired
    private Vertx vertx;

    /**
     * Configuration of Elasticsearch (cluster name, addresses, ...)
     */
    private HttpClientConfiguration configuration;

    /**
     * HTTP client.
     */
    private WebClient httpClient;

    /**
     * Authorization header if Elasticsearch is protected.
     */
    private String authorizationHeader;

    private final ObjectMapper mapper = new ObjectMapper();

    public HttpClient() {
        this(new HttpClientConfiguration());
    }

    public HttpClient(final HttpClientConfiguration configuration) {
        this.configuration = configuration;
    }

    @PostConstruct
    public void initialize() {
        if (! configuration.getEndpoints().isEmpty()) {
            final Endpoint endpoint = configuration.getEndpoints().get(0);
            final URI elasticEdpt = URI.create(endpoint.getUrl());
            initializePaths(elasticEdpt);

            WebClientOptions options = new WebClientOptions()
                    .setDefaultHost(elasticEdpt.getHost())
                    .setDefaultPort(elasticEdpt.getPort() != -1 ? elasticEdpt.getPort() :
                            (HTTPS_SCHEME.equalsIgnoreCase(elasticEdpt.getScheme()) ? 443 : 80));

            if (HTTPS_SCHEME.equalsIgnoreCase(elasticEdpt.getScheme())) {
                options
                    .setSsl(true)
                    .setTrustAll(true);

                if (this.configuration.getSslConfig() != null) {
                    options.setKeyCertOptions(this.configuration.getSslConfig().getVertxWebClientSslKeystoreOptions());
                }
            }

            if (configuration.isProxyConfigured()) {
                ProxyOptions proxyOptions = new ProxyOptions();
                proxyOptions.setType(ProxyType.valueOf(configuration.getProxyType()));
                if (HTTPS_SCHEME.equalsIgnoreCase(elasticEdpt.getScheme())) {
                    proxyOptions.setHost(configuration.getProxyHttpsHost());
                    proxyOptions.setPort(configuration.getProxyHttpsPort());
                    proxyOptions.setUsername(configuration.getProxyHttpsUsername());
                    proxyOptions.setPassword(configuration.getProxyHttpsPassword());
                } else {
                    proxyOptions.setHost(configuration.getProxyHttpHost());
                    proxyOptions.setPort(configuration.getProxyHttpPort());
                    proxyOptions.setUsername(configuration.getProxyHttpUsername());
                    proxyOptions.setPassword(configuration.getProxyHttpPassword());
                }
                options.setProxyOptions(proxyOptions);
            }

            this.httpClient = WebClient.create(vertx, options);

            // Read configuration to authenticate calls to Elasticsearch (basic authentication only)
            if (this.configuration.getUsername() != null) {
                this.authorizationHeader = this.initEncodedAuthorization(this.configuration.getUsername(),
                        this.configuration.getPassword());
            }

            ((WebClientInternal) this.httpClient.getDelegate()).addInterceptor(context -> {
                context.request()
                        .timeout(configuration.getRequestTimeout())
                        .putHeader(HttpHeaders.ACCEPT, CONTENT_TYPE)
                        .putHeader(HttpHeaders.ACCEPT_CHARSET, StandardCharsets.UTF_8.name());

                // Basic authentication
                if (authorizationHeader != null) {
                    context.request().putHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
                }

                context.next();
            });

        }
    }

    private void initializePaths(URI uri) {
        String urlPrefix = uri.getPath().replaceAll("/$", "");

        URL_ROOT = urlPrefix + "/";
        URL_STATE_CLUSTER = urlPrefix + "/_cluster/health";
        URL_BULK = urlPrefix + "/_bulk";
        URL_TEMPLATE = urlPrefix + "/_template";
        URL_INGEST = urlPrefix + "/_ingest/pipeline";
        URL_SEARCH = urlPrefix + "/_search?ignore_unavailable=true";
        URL_COUNT = urlPrefix + "/_count?ignore_unavailable=true";
    }

    @Override
    public Single<ElasticsearchInfo> getInfo() throws ElasticsearchException {
        return httpClient
                .get(URL_ROOT)
                .rxSend()
                .doOnError(throwable -> logger.error("Unable to get a connection to Elasticsearch", throwable))
                .map(response -> mapper.readValue(response.bodyAsString(), ElasticsearchInfo.class));
    }

    /**
     * Get the cluster health
     *
     * @return the cluster health
     * @throws ElasticsearchException error occurs during ES call
     */
    @Override
    public Single<Health> getClusterHealth() {
        return httpClient
                .get(URL_STATE_CLUSTER)
                .rxSend()
                .map(response -> mapper.readValue(response.bodyAsString(), Health.class));
    }

    @Override
    public Single<BulkResponse> bulk(final List<io.vertx.core.buffer.Buffer> data) {
        // Compact buffer
        Buffer payload = Buffer.buffer();
        data.forEach(buffer -> payload.appendBuffer(Buffer.newInstance(buffer)));

        return httpClient
                .post(URL_BULK)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/x-ndjson")
                .rxSendBuffer(payload)
                .map(response -> {
                    if (response.statusCode() != HttpStatusCode.OK_200) {
                        logger.error("Unable to bulk index data: status[{}] response[{}]",
                                response.statusCode(), response.body());
                        throw new ElasticsearchException("Unable to bulk index data");
                    }

                    BulkResponse bulkResponse = mapper.readValue(response.bodyAsString(), BulkResponse.class);
                    if (bulkResponse.getErrors()) {
                        bulkResponse.getItems().stream()
                                .filter(bulkItemResponse -> bulkItemResponse.getIndex().getError() != null)
                                .forEach(bulkItemResponse ->
                                        logger.error("An error occurs while indexing data into ES: indice[{}] error[{}]",
                                                bulkItemResponse.getIndex().getIndexName(),
                                                bulkItemResponse.getIndex().getError().getReason()));
                    }

                        return bulkResponse;
                });
    }

    @Override
    public Completable putTemplate(String templateName, String template) {
        return httpClient
                .put(URL_TEMPLATE + '/' + templateName)
                .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .rxSendBuffer(Buffer.buffer(template))
                .flatMapCompletable(response -> {
                    if (response.statusCode() != HttpStatusCode.OK_200) {
                        logger.error("Unable to put template mapping: status[{}] template[{}] response[{}]",
                                response.statusCode(), template, response.body());
                        return Completable.error(new ElasticsearchException("Unable to put template mapping"));
                    }

                    return Completable.complete();
                });
    }

    /**
     * Perform an HTTP count query
     * @param indexes indexes names. If null count on all indexes
     * @param type document type separated by comma. If null count on all types
     * @param query json body query
     * @return elasticsearch response
     */
    public Single<CountResponse> count(final String indexes, final String type, final String query) {
        // index can be null _count on all index
        final StringBuilder url = new StringBuilder()
                .append('/')
                .append(indexes);

        if (type != null) {
            url.append('/').append(type);
        }

        url.append(URL_COUNT);
        return httpClient
                .post(url.toString())
                .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .rxSendBuffer(Buffer.buffer(query))
                .map(response -> {
                    if (response.statusCode() != HttpStatusCode.OK_200) {
                        logger.error("Unable to count: url[{}] status[{}] query[{}] response[{}]",
                                url.toString(), response.statusCode(), query, response.body());
                        throw new ElasticsearchException("Unable to count");
                    }

                    return mapper.readValue(response.bodyAsString(), CountResponse.class);
                });
    }

    /**
     * Perform an HTTP search query
     * @param indexes indexes names. If null search on all indexes
     * @param type document type separated by comma. If null search on all types
     * @param query json body query
     * @return elasticsearch response
     */
    public Single<SearchResponse> search(final String indexes, final String type, final String query) {
        // index can be null _search on all index
        final StringBuilder url = new StringBuilder()
                .append('/')
                .append(indexes);

        if (type != null) {
            url.append('/').append(type);
        }

        url.append(URL_SEARCH);
        return httpClient
                .post(url.toString())
                .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .rxSendBuffer(Buffer.buffer(query))
                .map(response -> {
                    if (response.statusCode() != HttpStatusCode.OK_200) {
                        logger.error("Unable to search: url[{}] status[{}] query[{}] response[{}]",
                                url.toString(), response.statusCode(), query, response.body());
                        throw new ElasticsearchException("Unable to search");
                    }

                    return mapper.readValue(response.bodyAsString(), SearchResponse.class);
                });
    }

    /**
     * Perform an HTTP count query
     * @param url URL to call
     * @param query json body query
     * @return elasticsearch response
     */
    public Single<Response> count(final String url, final String query) {
        return httpClient
                .post(url)
                .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .rxSendBuffer(Buffer.buffer(query))
                .map(response -> {
                    if (response.statusCode() != HttpStatusCode.OK_200) {
                        logger.error("Unable to count: url[{}] status[{}] query[{}] response[{}]",
                                url, response.statusCode(), query, response.body());
                        throw new ElasticsearchException("Unable to count");
                    }

                    return mapper.readValue(response.bodyAsString(), CountResponse.class);
                });
    }

    @Override
    public Completable putPipeline(String pipelineName, String pipeline) {
        return httpClient
                .put(URL_INGEST + '/' + pipelineName)
                .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .rxSendBuffer(Buffer.buffer(pipeline))
                .flatMapCompletable(response -> {
                    switch (response.statusCode()) {
                        case HttpStatusCode.OK_200:
                            return Completable.complete();
                        case HttpStatusCode.BAD_REQUEST_400:
                            logger.warn("Unable to create ES pipeline: {}", pipelineName);
                            break;
                        default:
                            logger.error("Unable to put pipeline: status[{}] pipeline[{}] response[{}]",
                                    response.statusCode(), pipeline, response.body());
                            break;
                    }

                    return Completable.error(new ElasticsearchException(
                            format("Unable to create ES pipeline '%s': status[%s] response[%s]",
                                    pipelineName, response.statusCode(), response.body())));
                });
    }

    /**
     * Create the Basic HTTP auth
     *
     * @param username
     *            username
     * @param password
     *            password
     * @return Basic auth string
     */
    private String initEncodedAuthorization(final String username, final String password) {
        final String auth = username + ":" + password;
        final String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedAuth;
    }

    public void setConfiguration(HttpClientConfiguration configuration) {
        this.configuration = configuration;
    }
}
