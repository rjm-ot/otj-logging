/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.opentable.logging.jetty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.net.HttpHeaders;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.opentable.httpheaders.OTHeaders;
import com.opentable.logging.CommonLogFields;
import com.opentable.logging.CommonLogHolder;
import com.opentable.logging.LogbackLogging;
import com.opentable.logging.otl.HttpV1;

/**
 * A Jetty RequestLog that emits to Logback, for tranport to centralized logging.
 */
@Singleton
public class JsonRequestLog extends AbstractLifeCycle implements RequestLog
{
    private static final Logger LOG = LoggerFactory.getLogger(JsonRequestLog.class);

    private final Set<String> startsWithBlackList;
    private final Set<String> equalityBlackList;

    private final Clock clock;

    @Inject
    public JsonRequestLog(final Clock clock,
                          final JsonRequestLogConfig config)
    {
        this.clock = clock;
        this.startsWithBlackList = config.getStartsWithBlacklist();
        this.equalityBlackList = config.getEqualityBlacklist();
    }

    @Override
    public void log(final Request request, final Response response)
    {
        final String requestUri = request.getRequestURI();

        for (String blackListEntry : startsWithBlackList) {
            if (StringUtils.startsWithIgnoreCase(requestUri, blackListEntry)) {
                return;
            }
        }

        for (String blackListEntry : equalityBlackList) {
            if (StringUtils.equalsIgnoreCase(requestUri, blackListEntry)) {
                return;
            }
        }

        final HttpV1 payload = createEvent(request, response);
        final RequestLogEvent event = new RequestLogEvent(payload, constructMessage(payload));

        // TODO: this is a bit of a hack.  The RequestId filter happens inside of the
        // servlet dispatcher which is a Jetty handler.  Since the request log is generated
        // as a separate handler, the scope has already exited and thus the MDC lost its token.
        // But we want it there when we log, so let's put it back temporarily...
        MDC.put(CommonLogFields.REQUEST_ID_KEY, Objects.toString(payload.getRequestId(), null));
        try {
            event.prepareForDeferredProcessing();
            LogbackLogging.log(LOG, event);
        } finally {
            MDC.remove(CommonLogFields.REQUEST_ID_KEY);
        }
    }

    @Nonnull
    protected HttpV1 createEvent(Request request, Response response) {
        final String query = request.getQueryString();
        return HttpV1.builder()
            .logName("request")
            .serviceType(CommonLogHolder.getServiceType())
            .uuid(UUID.randomUUID())
            .timestamp(Instant.ofEpochMilli(request.getTimeStamp()))

            .method(request.getMethod())
            .status(response.getStatus())
            .incoming(true)
            .url(fullUrl(request))
            .urlQuerystring(query)

            .duration(TimeUnit.NANOSECONDS.toMicros(
                Duration.between(
                    Instant.ofEpochMilli(request.getTimeStamp()),
                    clock.instant())
                .toNanos()))

            .bodySize(request.getContentLengthLong())
            .responseSize(response.getContentCount())

            .acceptLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE))
            .anonymousId(request.getHeader(OTHeaders.ANONYMOUS_ID))
            // mind your r's and rr's
            .referer(request.getHeader(HttpHeaders.REFERER))
            .referringHost(request.getHeader(OTHeaders.REFERRING_HOST))
            .referringService(request.getHeader(OTHeaders.REFERRING_SERVICE))
            .remoteAddress(request.getRemoteAddr())
            .requestId(optUuid(response.getHeader(OTHeaders.REQUEST_ID)))
            .sessionId(request.getHeader(OTHeaders.SESSION_ID))
            .userAgent(request.getHeader(HttpHeaders.USER_AGENT))
            .userId(request.getHeader(OTHeaders.USER_ID))
            .headerOtOriginaluri(request.getHeader(OTHeaders.ORIGINAL_URI))

            .headerOtDomain(request.getHeader(OTHeaders.DOMAIN))
            .headerHost(request.getHeader(HttpHeaders.HOST))
            .headerAccept(request.getHeader(HttpHeaders.ACCEPT))

            .headerXForwardedFor(request.getHeader(HttpHeaders.X_FORWARDED_FOR))
            .headerXForwardedPort(request.getHeader(HttpHeaders.X_FORWARDED_PORT))
            .headerXForwardedProto(request.getHeader(HttpHeaders.X_FORWARDED_PROTO))

            .build();
    }

    @Nonnull
    protected String constructMessage(HttpV1 payload) {
        return constructMessage0(payload);
    }

    @Nonnull
    static String constructMessage0(HttpV1 payload) {
        if (payload == null) {
            throw new IllegalArgumentException("null payload");
        }
        final long responseSize = payload.getResponseSize();
        final String responseSizeText = responseSize <= 0 ? "" : responseSize + " bytes in ";
        return String.format("%s %s : %s, %s%s", payload.getMethod(), payload.getUrl(), payload.getStatus(), responseSizeText, prettyTime(payload.getDuration()));
    }

    protected static String prettyTime(long micros) {
        return String.format("%.1f ms", micros / 1000.0);
    }

    private UUID optUuid(String uuid) {
        try {
            return uuid == null ? null : UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            LOG.warn("Unable to parse purported request id '{}': {}", uuid, e.toString());
            return null;
        }
    }

    private String fullUrl(Request request) {
        final String result;
        if (StringUtils.isNotEmpty(request.getQueryString())) {
            result = request.getRequestURI() + '?' + request.getQueryString();
        } else {
            result = request.getRequestURI();
        }
        return result;
    }

    protected Clock getClock() {
        return clock;
    }
}
