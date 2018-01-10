/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharing;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonParseException;
import com.redhat.jenkins.nodesharing.transport.AbstractEntity;
import com.redhat.jenkins.nodesharing.transport.CrumbResponse;
import com.redhat.jenkins.nodesharing.transport.Entity;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rest endpoint representing "the other side" to talk to.
 *
 * @author ogondza.
 */
public class RestEndpoint {
    private static final Logger LOGGER = Logger.getLogger(RestEndpoint.class.getName());

    private final @Nonnull String endpoint;
    private final @Nonnull String crumbIssuerEndpoint;

    public RestEndpoint(@Nonnull String jenkinsUrl, @Nonnull String endpointPath) {
        this.endpoint = jenkinsUrl + endpointPath;
        this.crumbIssuerEndpoint = jenkinsUrl + "crumbIssuer/api/json";
    }

    public HttpPost post(@Nonnull String path) {
        return new HttpPost(endpoint + '/' + path);
    }

    public HttpGet get(@Nonnull String path) {
        return new HttpGet(endpoint + '/' + path);
    }

    /**
     * Execute HttpRequest.
     *
     * @param method Method and url to be invoked.
     * @param requestEntity Entity to be sent in request body.
     * @param returnType Type the response should be converted at.
     *
     * @throws ActionFailed.CommunicationError When there ware problems executing the request.
     * @throws ActionFailed.ProtocolMismatch When there is a problem reading the response.
     * @throws ActionFailed.RequestFailed When status code different from 200 was returned.
     */
    public <T extends AbstractEntity> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nonnull Entity requestEntity,
            @Nonnull Class<T> returnType
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        method.setEntity(new WrappingEntity(requestEntity));
        return _executeRequest(method, new DefaultResponseHandler<>(method, returnType));
    }

    /**
     * Execute HttpRequest.
     *
     * @param method Method and url to be invoked.
     * @param requestEntity Entity to be sent in request body.
     * @param handler Response handler to be used.
     *
     * @throws ActionFailed.CommunicationError When there ware problems executing the request.
     * @throws ActionFailed.ProtocolMismatch When there is a problem reading the response.
     * @throws ActionFailed.RequestFailed When status code different from 200 was returned.
     */
    public <T> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nonnull Entity requestEntity,
            @Nonnull ResponseHandler<T> handler
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        method.setEntity(new WrappingEntity(requestEntity));
        return _executeRequest(method, handler);
    }

    @VisibleForTesting
    /*package*/ <T> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nonnull ResponseHandler<T> handler
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        return _executeRequest(method, handler);
    }

    @CheckForNull
    private <T> T _executeRequest(@Nonnull HttpRequestBase method, @Nonnull ResponseHandler<T> handler) {
        CloseableHttpClient client = HttpClients.createSystem();
        try {
            return client.execute(method, handler);
        } catch (IOException e) {
            throw new ActionFailed.CommunicationError("Failed executing REST call: " + method, e);
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to close HttpClient", e);
            }
        }
    }

    private Header getCrumbHeader() {
        CrumbResponse crumbResponse;
        try {
            HttpGet method = new HttpGet(crumbIssuerEndpoint);
            crumbResponse = _executeRequest(method, new DefaultResponseHandler<>(method, CrumbResponse.class));
        } catch (ActionFailed.RequestFailed e) {
            if (e.getStatusCode() == 404) { // No crumb issuer used
                return new BasicHeader("Jenkins-Crumb", "Not-Used");
            }
            throw e;
        }
        assert crumbResponse != null;
        return new BasicHeader(crumbResponse.getCrumbRequestField(), crumbResponse.getCrumb());
    }

    @VisibleForTesting
    /*package*/ static String getPayloadAsString(@Nonnull HttpResponse response) throws IOException {
        try (InputStream is = response.getEntity().getContent()) {
            return IOUtils.toString(is);
        }
    }

    /**
     * Fail in case of non-200 status code and create response entity.
     */
    private static final class DefaultResponseHandler<T extends Entity> implements ResponseHandler<T> {

        private final @Nonnull HttpRequestBase method;
        private final @Nonnull Class<? extends T> returnType;

        private DefaultResponseHandler(@Nonnull HttpRequestBase method, @Nonnull Class<? extends T> returnType) {
            this.method = method;
            this.returnType = returnType;
        }

        @Override
        public final @Nonnull T handleResponse(HttpResponse response) throws IOException {
            // Check exit code
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                ActionFailed.RequestFailed requestFailed = new ActionFailed.RequestFailed(
                        method, response.getStatusLine(), getPayloadAsString(response)
                );
                LOGGER.info(requestFailed.getMessage());
                throw requestFailed;
            }

            // Build Entity
            try (InputStream is = response.getEntity().getContent()) {
                return Entity.fromInputStream(is, returnType);
            } catch (JsonParseException ex) {
                throw new ActionFailed.ProtocolMismatch("Unable to create entity: " + returnType, ex);
            }
        }

    }

    // Wrap transport.Entity into HttpEntity
    private static final class WrappingEntity extends AbstractHttpEntity {

        private final @Nonnull Entity entity;

        private WrappingEntity(@Nonnull Entity entity) {
            this.entity = entity;
        }

        @Override public boolean isRepeatable() {
            return false;
        }

        @Override public long getContentLength() {
            return -1;
        }

        @Override public InputStream getContent() throws IOException {
            throw new UnsupportedOperationException(
                    "We should not need this as presumably this is used for receiving entities only"
            );
        }

        @Override public void writeTo(OutputStream outstream) throws IOException {
            entity.toOutputStream(outstream);
        }

        @Override public boolean isStreaming() {
            return false;
        }
    }
}