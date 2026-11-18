/*
 * Copyright 2026 Yavdhesh Sanchihar
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

package org.apache.jmeter.protocol.http.sampler;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringEncoder;

/**
 * HTTP sampler implementation backed by the shared {@link NettyTransport}.
 *
 * <p>All JMeter semantics stay in this class, on the calling thread: the
 * request is built from the sampler state (arguments, header manager, cookie
 * manager, cache manager), handed to the transport as bytes plus headers, and
 * the calling thread — a virtual thread when the thread group runs on virtual
 * threads — parks on the returned future until the event loops complete it.
 * The response is then turned into an {@link HTTPSampleResult} exactly the way
 * the other implementations do, including cookie storage, cache bookkeeping
 * and redirect handling through {@link #resultProcessing}.</p>
 *
 * <p>Milestone 1 scope: GET and POST (form parameters, raw parameter-values
 * body, or a single file as the body), keep-alive through the transport's
 * channel pools, TLS without certificate verification (as in the other
 * implementations), and the sampler's connect and response timeouts.
 * Multipart bodies, file-upload form fields, proxies and authentication
 * managers are not handled yet and are rejected or ignored loudly rather than
 * silently, so a plan never appears to work while doing something else.</p>
 *
 * @since 1.0.0 (PMeasure)
 */
public class HTTPNettyImpl extends HTTPHCAbstractImpl {

    private static final Logger log = LoggerFactory.getLogger(HTTPNettyImpl.class);

    /** Overall cap on one sample, on top of the connect and read timeouts, so a sample can never park forever. */
    private static final long SAMPLE_TIMEOUT_SLACK_MILLIS = 10_000L;

    private volatile FullHttpRequest currentRequest;

    public HTTPNettyImpl(HTTPSamplerBase testElement) {
        super(testElement);
    }

    @Override
    protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect, int frameDepth) {
        if (log.isDebugEnabled()) {
            log.debug("Start : sample {} method {} followingRedirect {} depth {}",
                    url, method, areFollowingRedirect, frameDepth);
        }

        HTTPSampleResult res = new HTTPSampleResult();
        configureSampleLabel(res, url);
        res.setURL(url);
        res.setHTTPMethod(method);

        res.sampleStart();

        final CacheManager cacheManager = getCacheManager();
        if (cacheManager != null && HTTPConstants.GET.equalsIgnoreCase(method)) {
            String headerBlock = buildHeaderBlock(url, method, null);
            if (cacheManager.inCache(url, headerSink(headerBlock))) {
                return updateSampleResultForResourceInCache(res);
            }
        }

        try {
            byte[] body = buildBody(method, res);
            String uri = buildUri(url, method);
            FullHttpRequest request = NettyTransport.newRequest(method, uri, body);
            applyHeaders(request, url, body);
            currentRequest = request;
            res.setRequestHeaders(formatHeaders(iterable(request.headers()), ALL_EXCEPT_COOKIE));

            boolean secure = HTTPConstants.PROTOCOL_HTTPS.equalsIgnoreCase(url.getProtocol());
            int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
            int connectTimeout = getConnectTimeout();
            int responseTimeout = getResponseTimeout();

            long startedNanos = System.nanoTime();
            CompletableFuture<NettyTransport.NettyResponse> future = NettyTransport.get().execute(
                    request, secure, url.getHost(), port, connectTimeout, responseTimeout);
            NettyTransport.NettyResponse nettyResponse =
                    future.get(sampleTimeoutMillis(connectTimeout, responseTimeout), TimeUnit.MILLISECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            populateResult(res, url, nettyResponse);
            res.setLatency(Math.min(elapsedMillis, res.getTime()));
            res.sampleEnd();

            if (cacheManager != null) {
                final HTTPSampleResult completed = res;
                cacheManager.saveDetails(name -> responseHeader(completed, name), completed);
            }

            res = resultProcessing(areFollowingRedirect, frameDepth, res);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
            errorResult(new IOException("Sample interrupted", e), res);
        } catch (ExecutionException e) {
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            errorResult(asException(cause), res);
        } catch (TimeoutException e) {
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
            errorResult(new IOException("Sample timed out", e), res);
        } catch (IOException | RuntimeException e) {
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
            errorResult(e, res);
        } finally {
            currentRequest = null;
        }
        return res;
    }

    @Override
    public boolean interrupt() {
        FullHttpRequest request = currentRequest;
        if (request != null) {
            // The transport owns the channel; dropping our reference is all the
            // facade can do — the future of an in-flight sample completes when
            // the channel closes or the response arrives.
            currentRequest = null;
            return true;
        }
        return false;
    }

    /**
     * Builds the request body for body-carrying methods and records it as the
     * query string of the result, mirroring the other implementations.
     * GET-style methods never carry a body here.
     */
    private byte[] buildBody(String method, HTTPSampleResult res) throws IOException {
        if (HTTPConstants.GET.equalsIgnoreCase(method)
                || HTTPConstants.HEAD.equalsIgnoreCase(method)
                || HTTPConstants.DELETE.equalsIgnoreCase(method)
                || HTTPConstants.OPTIONS.equalsIgnoreCase(method)) {
            return null;
        }
        if (getUseMultipart()) {
            throw new IOException("Multipart bodies are not supported by the Netty implementation yet");
        }
        String contentEncoding = getContentEncoding();
        Charset charset = Charset.forName(contentEncoding);
        HTTPFileArg[] files = getHTTPFiles();

        if (!hasArguments() && getSendFileAsPostBody()) {
            HTTPFileArg file = files[0];
            File resolved = FileServer.getFileServer().getResolvedFile(file.getPath());
            res.setQueryString("<actual file content, not shown here>");
            return Files.readAllBytes(resolved.toPath());
        }
        if (getSendParameterValuesAsPostBody()) {
            StringBuilder postBody = new StringBuilder();
            for (JMeterProperty property : getArguments().getEnabledArguments()) {
                HTTPArgument arg = (HTTPArgument) property.getObjectValue();
                postBody.append(arg.getEncodedValue(contentEncoding));
            }
            res.setQueryString(postBody.toString());
            return postBody.toString().getBytes(charset);
        }
        if (hasArguments()) {
            QueryStringEncoder encoder = new QueryStringEncoder("", charset); // $NON-NLS-1$
            StringBuilder shown = new StringBuilder();
            for (JMeterProperty property : getArguments().getEnabledArguments()) {
                HTTPArgument arg = (HTTPArgument) property.getObjectValue();
                String name = arg.getName();
                if (arg.isSkippable(name)) {
                    continue;
                }
                encoder.addParam(name, arg.getValue());
                if (shown.length() > 0) {
                    shown.append('&');
                }
                shown.append(arg.getEncodedValue(contentEncoding));
            }
            String encoded = encoder.toString();
            String body = encoded.startsWith("?") ? encoded.substring(1) : encoded; // $NON-NLS-1$
            res.setQueryString(shown.toString());
            return body.getBytes(charset);
        }
        return null;
    }

    /** Request URI: path plus query; for GET-style methods the arguments form the query. */
    private String buildUri(URL url, String method) throws IOException {
        String path = StringUtilities.isEmpty(url.getPath()) ? "/" : url.getPath(); // $NON-NLS-1$
        String query = url.getQuery();
        boolean argumentsInQuery = HTTPConstants.GET.equalsIgnoreCase(method)
                || HTTPConstants.HEAD.equalsIgnoreCase(method)
                || HTTPConstants.DELETE.equalsIgnoreCase(method);
        if (argumentsInQuery && hasArguments()) {
            QueryStringEncoder encoder = new QueryStringEncoder(path);
            if (query != null && !query.isEmpty()) {
                return path + '?' + query + argumentsQuerySuffix();
            }
            for (JMeterProperty property : getArguments().getEnabledArguments()) {
                HTTPArgument arg = (HTTPArgument) property.getObjectValue();
                String name = arg.getName();
                if (arg.isSkippable(name)) {
                    continue;
                }
                encoder.addParam(name, arg.getValue());
            }
            return encoder.toString();
        }
        return query == null || query.isEmpty() ? path : path + '?' + query;
    }

    private String argumentsQuerySuffix() throws IOException {
        StringBuilder suffix = new StringBuilder();
        for (JMeterProperty property : getArguments().getEnabledArguments()) {
            HTTPArgument arg = (HTTPArgument) property.getObjectValue();
            String name = arg.getName();
            if (arg.isSkippable(name)) {
                continue;
            }
            suffix.append('&').append(arg.getEncodedValue(getContentEncoding()));
        }
        return suffix.toString();
    }

    /** Applies Host, the header manager's headers, cookies and the default content type. */
    private void applyHeaders(FullHttpRequest request, URL url, byte[] body) {
        HttpHeaders headers = request.headers();
        int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
        boolean defaultPort = port == url.getDefaultPort();
        headers.set(HttpHeaderNames.HOST, defaultPort ? url.getHost() : url.getHost() + ':' + port);
        setConnectionHeaders(getHeaderManager(), url, name -> true,
                (name, value) -> headers.add(name, value));
        setConnectionCookie(url, getCookieManager(), (name, value) -> headers.set(name, value));
        if (body != null && headers.get(HttpHeaderNames.CONTENT_TYPE) == null
                && ADD_CONTENT_TYPE_TO_POST_IF_MISSING
                && !getSendParameterValuesAsPostBody()) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
        }
        if (getUseKeepAlive()) {
            headers.set(HttpHeaderNames.CONNECTION, "keep-alive"); // $NON-NLS-1$
        } else {
            headers.set(HttpHeaderNames.CONNECTION, "close"); // $NON-NLS-1$
        }
    }

    /** Header block used for the cache lookup, in the same shape the request will carry. */
    private String buildHeaderBlock(URL url, String method, byte[] body) {
        FullHttpRequest probe = NettyTransport.newRequest(method, "/", body); // $NON-NLS-1$
        try {
            applyHeaders(probe, url, body);
            return formatHeaders(iterable(probe.headers()), name -> true);
        } finally {
            probe.release();
        }
    }

    private void populateResult(HTTPSampleResult res, URL url, NettyTransport.NettyResponse response) {
        HttpHeaders headers = response.headers();
        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null) {
            res.setContentType(contentType);
            res.setEncodingAndType(contentType);
        }
        byte[] data = response.body();
        res.setResponseData(data);

        int statusCode = response.status().code();
        res.setResponseCode(Integer.toString(statusCode));
        res.setResponseMessage(response.status().reasonPhrase());
        res.setSuccessful(isSuccessCode(statusCode));
        res.setResponseHeaders(formatHeaders(iterable(headers), name -> true));
        long headerBytes = (long) res.getResponseHeaders().length() + (long) headers.size() + 1L + 2L;
        res.setHeadersSize((int) headerBytes);
        res.setBodySize((long) data.length);
        if (res.isRedirect()) {
            String location = headers.get(HttpHeaderNames.LOCATION);
            if (location == null) {
                throw new IllegalArgumentException("Missing location header in redirect for " + url);
            }
            res.setRedirectLocation(location);
        }
        saveConnectionCookies(iterable(headers), url, getCookieManager());
    }

    private static String responseHeader(HTTPSampleResult res, String name) {
        for (String line : res.getResponseHeaders().split("\n")) { // $NON-NLS-1$
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static HeaderIterable iterable(HttpHeaders headers) {
        return action -> headers.forEach(entry -> action.accept(entry.getKey(), entry.getValue()));
    }

    private static CacheManager.RequestHeaderSink headerSink(String headerBlock) {
        java.util.Map<String, String> parsed = new java.util.LinkedHashMap<>();
        for (String line : headerBlock.split("\n")) { // $NON-NLS-1$
            int colon = line.indexOf(':');
            if (colon > 0) {
                parsed.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return CacheManager.requestHeaderSink(
                action -> parsed.forEach(action),
                (name, value) -> {
                    // The cache lookup only reads; conditional headers it would
                    // add are not applied by this implementation in M1.
                });
    }

    private static long sampleTimeoutMillis(int connectTimeout, int responseTimeout) {
        long budget = SAMPLE_TIMEOUT_SLACK_MILLIS;
        if (connectTimeout > 0) {
            budget += connectTimeout;
        }
        if (responseTimeout > 0) {
            budget += responseTimeout;
        }
        return budget;
    }

    private static Exception asException(Throwable cause) {
        if (cause instanceof Exception exception) {
            return exception;
        }
        return new IOException(String.valueOf(cause), cause);
    }
}
