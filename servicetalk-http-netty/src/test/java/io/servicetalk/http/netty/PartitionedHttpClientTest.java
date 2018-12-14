/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.client.api.ClientGroup;
import io.servicetalk.client.api.ServiceDiscoverer;
import io.servicetalk.client.api.partition.PartitionAttributes;
import io.servicetalk.client.api.partition.PartitionAttributesBuilder;
import io.servicetalk.client.api.partition.PartitionedServiceDiscovererEvent;
import io.servicetalk.client.internal.partition.DefaultPartitionAttributesBuilder;
import io.servicetalk.concurrent.api.AsyncCloseables;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.concurrent.api.TestPublisher;
import io.servicetalk.http.api.BlockingHttpClient;
import io.servicetalk.http.api.DefaultHttpHeadersFactory;
import io.servicetalk.http.api.DefaultStreamingHttpRequestResponseFactory;
import io.servicetalk.http.api.HttpClient;
import io.servicetalk.http.api.HttpRequestMetaData;
import io.servicetalk.http.api.HttpRequestMethod;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpRequestFactory;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.transport.api.ExecutionContext;
import io.servicetalk.transport.api.HostAndPort;
import io.servicetalk.transport.api.ServerContext;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static io.servicetalk.concurrent.api.AsyncCloseables.newCompositeCloseable;
import static io.servicetalk.http.api.HttpExecutionStrategies.defaultStrategy;
import static io.servicetalk.http.api.HttpSerializationProviders.textSerializer;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PartitionedHttpClientTest {

    private static final PartitionAttributes.Key<String> SRV_NAME = PartitionAttributes.Key.newKey();
    private static final PartitionAttributes.Key<Boolean> SRV_LEADER = PartitionAttributes.Key.newKey();
    private static final String SRV_1 = "srv1";
    private static final String SRV_2 = "srv2";
    private static final String X_SERVER = "X-SERVER";
    private static ServerContext srv1;
    private static ServerContext srv2;
    private TestPublisher<PartitionedServiceDiscovererEvent<ServerAddress>> sdPublisher;
    private ServiceDiscoverer<String, InetSocketAddress,
            ? extends PartitionedServiceDiscovererEvent<InetSocketAddress>> psd;

    @BeforeClass
    public static void setUpServers() throws Exception {
        srv1 = HttpServers.forPort(0)
                .listenBlockingAndAwait((ctx, request, responseFactory) ->
                        responseFactory.ok().setHeader(X_SERVER, SRV_1));

        srv2 = HttpServers.forPort(0)
                .listenBlockingAndAwait((ctx, request, responseFactory) ->
                        responseFactory.ok().setHeader(X_SERVER, SRV_2));
    }

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        sdPublisher = new TestPublisher<>();
        psd = mock(ServiceDiscoverer.class);
        Publisher<PartitionedServiceDiscovererEvent<InetSocketAddress>> mappedSd =
                sdPublisher.map(psde -> new PartitionedServiceDiscovererEvent<InetSocketAddress>() {
                    @Override
                    public InetSocketAddress address() {
                        return psde.address().isa;
                    }

                    @Override
                    public boolean available() {
                        return psde.available();
                    }

                    @Override
                    public PartitionAttributes partitionAddress() {
                        return psde.partitionAddress();
                    }
                });
        when(psd.discover("test-cluster")).then(__ -> mappedSd);
    }

    @AfterClass
    public static void tearDownServers() throws Exception {
        newCompositeCloseable().mergeAll(srv1, srv2).close();
    }

    private static int port(ServerContext ctx) {
        return ((InetSocketAddress) ctx.listenAddress()).getPort();
    }

    @Test
    public void testPartitionByHeader() throws Exception {

        final Function<HttpRequestMetaData, PartitionAttributesBuilder> selector = req ->
                new DefaultPartitionAttributesBuilder(1)
                        .add(SRV_NAME, requireNonNull(req.headers().get(X_SERVER)).toString());

        try (BlockingHttpClient clt = HttpClients.forPartitionedAddress(psd, "test-cluster", selector)
                // TODO(jayv) This *hack* only works because SRV_NAME is part of the selection criteria,
                // we need to consider adding metadata to PartitionAttributes.
                .appendClientBuilderFilter((pa, builder) ->
                        builder.enableHostHeaderFallback(pa.get(SRV_NAME)))
                .buildBlocking()) {

            sdPublisher.sendOnSubscribe().sendItems(
                    new TestPSDE(SRV_1, (InetSocketAddress) srv1.listenAddress()),
                    new TestPSDE(SRV_2, (InetSocketAddress) srv2.listenAddress()));

            final HttpResponse httpResponse1 = clt.request(clt.get("/").addHeader(X_SERVER, SRV_2));
            final HttpResponse httpResponse2 = clt.request(clt.get("/").addHeader(X_SERVER, SRV_1));

            assertThat(httpResponse1.headers().get(X_SERVER), hasToString(SRV_2));
            assertThat(httpResponse2.headers().get(X_SERVER), hasToString(SRV_1));
        }
    }

    @Test
    public void testPartitionByTarget() throws Exception {

        final Function<HttpRequestMetaData, PartitionAttributesBuilder> selector = req ->
                new DefaultPartitionAttributesBuilder(1)
                        .add(SRV_NAME, req.requestTarget().substring(1));

        try (BlockingHttpClient clt = HttpClients.forPartitionedAddress(psd, "test-cluster", selector)
                // TODO(jayv) This *hack* only works because SRV_NAME is part of the selection criteria,
                // we need to consider adding metadata to PartitionAttributes.
                .appendClientBuilderFilter((pa, builder) ->
                        builder.enableHostHeaderFallback(pa.get(SRV_NAME)))
                .buildBlocking()) {

            sdPublisher.sendOnSubscribe().sendItems(
                    new TestPSDE(SRV_1, (InetSocketAddress) srv1.listenAddress()),
                    new TestPSDE(SRV_2, (InetSocketAddress) srv2.listenAddress()));

            final HttpResponse httpResponse1 = clt.request(clt.get("/" + SRV_2));
            final HttpResponse httpResponse2 = clt.request(clt.get("/" + SRV_1));

            assertThat(httpResponse1.headers().get(X_SERVER), hasToString(SRV_2));
            assertThat(httpResponse2.headers().get(X_SERVER), hasToString(SRV_1));
        }
    }

    @Test
    public void testPartitionByLeader() throws Exception {
        final Function<HttpRequestMetaData, PartitionAttributesBuilder> selector = req ->
                new DefaultPartitionAttributesBuilder(1).add(SRV_LEADER, true);

        try (BlockingHttpClient clt = HttpClients.forPartitionedAddress(psd, "test-cluster", selector)
                // TODO(jayv) This *hack* doesn't work because SRV_NAME is NOT part of the selection criteria,
                // we need to consider adding metadata to PartitionAttributes.
                // .appendClientFactoryFilter((pa, builder) ->
                //         builder.enableHostHeaderFallback(pa.get(SRV_NAME)))
                .buildBlocking()) {

            sdPublisher.sendOnSubscribe().sendItems(
                    new TestPSDE(SRV_1, false, (InetSocketAddress) srv1.listenAddress()),
                    new TestPSDE(SRV_2, true, (InetSocketAddress) srv2.listenAddress()));

            final HttpResponse httpResponse1 = clt.request(clt.get("/foo"));
            final HttpResponse httpResponse2 = clt.request(clt.get("/bar"));

            assertThat(httpResponse1.headers().get(X_SERVER), hasToString(SRV_2));
            assertThat(httpResponse2.headers().get(X_SERVER), hasToString(SRV_2));
        }
    }

    /**
     * Custom address type.
     */
    private static final class ServerAddress {
        public final InetSocketAddress isa;
        public final String name; // some metadata

        ServerAddress(final InetSocketAddress isa, final String name) {
            this.isa = isa;
            this.name = name;
        }
    }

    /**
     * Discoverer for custom address type {@link ServerAddress} that is partition-aware.
     */
    private static final class TestPSDE implements PartitionedServiceDiscovererEvent<ServerAddress> {
        private final PartitionAttributes pa;
        private final ServerAddress sa;

        private TestPSDE(final String srvName, final InetSocketAddress isa) {
            this.pa = new DefaultPartitionAttributesBuilder(1).add(SRV_NAME, srvName).build();
            this.sa = new ServerAddress(isa, srvName);
        }

        private TestPSDE(final String srvName, final boolean leader, final InetSocketAddress isa) {
            this.pa = new DefaultPartitionAttributesBuilder(2)
                    .add(SRV_LEADER, leader)
                    .build();
            this.sa = new ServerAddress(isa, srvName);
        }

        @Override
        public PartitionAttributes partitionAddress() {
            return pa;
        }

        @Override
        public ServerAddress address() {
            return sa;
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    @Test
    public void testClientGroupPartitioning() throws Exception {
        // user partition discovery service, userId=1 => srv1 | userId=2 => srv2
        try (ServerContext userDisco = HttpServers.forPort(0)
                .listenBlockingAndAwait((ctx, request, responseFactory) -> {
                    if ("/partition".equals(request.path())) {
                        int userId = Integer.parseInt(request.queryParameter("userId"));
                        ServerContext dSrv = userId == 1 ? srv1 : userId == 2 ? srv2 : null;
                        return responseFactory.ok().payloadBody(port(dSrv) + "", textSerializer());
                    }
                    return responseFactory.notFound();
                })) {

            try (PartitioningHttpClientWithOutOfBandDiscovery client =
                         new PartitioningHttpClientWithOutOfBandDiscovery(userDisco)) {

                StreamingHttpResponse httpResponse1 = client.request(new User(1), client.get("/foo")).toFuture().get();
                StreamingHttpResponse httpResponse2 = client.request(new User(2), client.get("/foo")).toFuture().get();

                assertThat(httpResponse1.headers().get(X_SERVER), hasToString(SRV_1));
                assertThat(httpResponse2.headers().get(X_SERVER), hasToString(SRV_2));
            }
        }
    }

    /**
     * A user defined object influencing partitioning yet not part of the request metadata.
     */
    private static final class User {
        private final int id;

        User(final int id) {
            this.id = id;
        }

        int id() {
            return id;
        }
    }

    /**
     * Example of a user defined ClientGroup Key.
     */
    private static final class Group {
        private final HostAndPort address;
        private final ExecutionContext executionContext;

        Group(final HostAndPort address, final ExecutionContext executionContext) {
            this.address = address;
            this.executionContext = executionContext;
        }

        HostAndPort address() {
            return address;
        }
    }

    /**
     * The sort of composite HttpClient we expect users to write to solve such use-cases.
     */
    private static class PartitioningHttpClientWithOutOfBandDiscovery
            implements AutoCloseable, StreamingHttpRequestFactory {

        private final ClientGroup<Group, StreamingHttpClient> clients;
        private final HttpClient udClient;
        private final StreamingHttpRequestFactory requestFactory;

        PartitioningHttpClientWithOutOfBandDiscovery(ServerContext disco) {
            // User Partition Discovery Service - IRL this client should typically cache responses
            udClient = HttpClients.forSingleAddress("localhost", port(disco)).build();

            requestFactory = new DefaultStreamingHttpRequestResponseFactory(
                    udClient.executionContext().bufferAllocator(), DefaultHttpHeadersFactory.INSTANCE);

            clients = ClientGroup.from(group ->
                    HttpClients.forSingleAddress(group.address())
                            .ioExecutor(group.executionContext.ioExecutor())
                            .executionStrategy(defaultStrategy(group.executionContext.executor()))
                            .bufferAllocator(group.executionContext.bufferAllocator())
                            .buildStreaming());
        }

        public Single<StreamingHttpResponse> request(User user, StreamingHttpRequest req) {
            return udClient
                    .request(udClient.get("/partition?userId=" + user.id()))
                    .flatMap(resp -> {
                        // user discovery returns the port of the server (both run on localhost)
                        int port = Integer.parseInt(resp.payloadBody().toString(StandardCharsets.UTF_8));
                        Group key = new Group(HostAndPort.of("localhost", port), udClient.executionContext());
                        return clients.get(key).request(req);
                    });
        }

        @Override
        public StreamingHttpRequest newRequest(final HttpRequestMethod method, final String requestTarget) {
            return requestFactory.newRequest(method, requestTarget);
        }

        @Override
        public void close() throws Exception {
            AsyncCloseables.newCompositeCloseable().prependAll(udClient, clients).closeAsync().toFuture().get();
        }
    }
}
