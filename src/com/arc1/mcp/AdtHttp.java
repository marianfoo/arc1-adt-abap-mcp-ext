package com.arc1.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.communication.message.AdtRequestFactory;
import com.sap.adt.communication.message.ByteArrayMessageBody;
import com.sap.adt.communication.message.HeadersFactory;
import com.sap.adt.communication.message.IHeaders;
import com.sap.adt.communication.message.IMessageBody;
import com.sap.adt.communication.message.IRequest;
import com.sap.adt.communication.message.IRequestFactory;
import com.sap.adt.communication.message.IResponse;
import com.sap.adt.communication.session.AdtSystemSessionFactory;
import com.sap.adt.communication.session.IStatelessSystemSession;

/**
 * Thin facade over Eclipse's public ADT REST infrastructure. Reuses Eclipse's
 * destination/cookie/session machinery so callers never deal with auth.
 *
 * Use {@link #get(String, String, String)} for GET-only flows (v0.2). POST
 * support comes in v0.3 when where-used and related endpoints are wired up.
 */
final class AdtHttp {

    static final int MAX_BODY_BYTES = 256 * 1024;

    private AdtHttp() {
    }

    static Response get(String destinationId, String pathAndQuery, String accept) throws IOException {
        return call(destinationId, IRequest.Method.GET, pathAndQuery, accept, null, null);
    }

    static Response post(String destinationId, String pathAndQuery, String accept,
                         String contentType, byte[] body) throws IOException {
        return call(destinationId, IRequest.Method.POST, pathAndQuery, accept, contentType, body);
    }

    private static Response call(String destinationId, IRequest.Method method,
                                 String pathAndQuery, String accept,
                                 String contentType, byte[] body) throws IOException {
        IStatelessSystemSession session = AdtSystemSessionFactory
            .createSystemSessionFactory()
            .createStatelessSession(destinationId);

        IHeaders headers = HeadersFactory.newHeaders();
        if (accept != null && !accept.isEmpty()) {
            headers.setField(HeadersFactory.newField("Accept", accept));
        }

        IMessageBody msgBody = null;
        if (body != null) {
            String ct = contentType == null || contentType.isEmpty()
                ? "application/octet-stream"
                : contentType;
            msgBody = new ByteArrayMessageBody(ct, body);
        }

        IRequestFactory reqFactory = AdtRequestFactory.createRequestFactory();
        IRequest request = reqFactory.createInstance(
            method, URI.create(pathAndQuery), headers, msgBody);

        IResponse response = session.sendRequest(new NullProgressMonitor(), request);
        return Response.from(response);
    }

    static final class Response {
        final int status;
        final String contentType;
        final Map<String, String> headers;
        final byte[] body;
        final boolean truncated;

        private Response(int status, String contentType, Map<String, String> headers,
                         byte[] body, boolean truncated) {
            this.status = status;
            this.contentType = contentType;
            this.headers = headers;
            this.body = body;
            this.truncated = truncated;
        }

        static Response from(IResponse adtResp) throws IOException {
            int status = adtResp.getStatus();
            IHeaders adtHeaders = adtResp.getHeaders();
            String contentType = adtHeaders == null ? null : adtHeaders.getValue("Content-Type");
            Map<String, String> headerMap = new LinkedHashMap<>();
            if (adtHeaders != null && adtHeaders.getFieldMap() != null) {
                headerMap.putAll(adtHeaders.getFieldMap());
            }

            byte[] bytes = new byte[0];
            boolean truncated = false;
            if (adtResp.getBody() != null) {
                try (InputStream in = adtResp.getBody().getContent()) {
                    if (in != null) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int read;
                        while ((read = in.read(chunk)) != -1) {
                            if (buf.size() + read > MAX_BODY_BYTES) {
                                int allowed = MAX_BODY_BYTES - buf.size();
                                if (allowed > 0) {
                                    buf.write(chunk, 0, allowed);
                                }
                                truncated = true;
                                break;
                            }
                            buf.write(chunk, 0, read);
                        }
                        bytes = buf.toByteArray();
                    }
                }
            }
            return new Response(status, contentType, headerMap, bytes, truncated);
        }

        String bodyAsString() {
            Charset cs = charsetFromContentType(contentType);
            return new String(body, cs);
        }

        private static Charset charsetFromContentType(String ct) {
            if (ct == null) {
                return StandardCharsets.UTF_8;
            }
            int i = ct.toLowerCase().indexOf("charset=");
            if (i < 0) {
                return StandardCharsets.UTF_8;
            }
            String cs = ct.substring(i + 8).trim();
            int semi = cs.indexOf(';');
            if (semi >= 0) {
                cs = cs.substring(0, semi).trim();
            }
            // strip optional quotes
            if (cs.startsWith("\"") && cs.endsWith("\"") && cs.length() > 1) {
                cs = cs.substring(1, cs.length() - 1);
            }
            try {
                return Charset.forName(cs);
            } catch (Throwable t) {
                return StandardCharsets.UTF_8;
            }
        }
    }
}
