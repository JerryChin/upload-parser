/*
 * Copyright (C) 2015 Adam Forgacs
 *
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

package com.elopteryx.paint.upload.impl;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Objects.requireNonNull;

import com.elopteryx.paint.upload.errors.MultipartException;
import com.elopteryx.paint.upload.errors.RequestSizeException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import javax.annotation.Nonnull;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

/**
 * The asynchronous implementation of the parser. This parser can be used to perform a parse
 * only if the calling servlet supports async mode.
 * Implements the listener interface. Called by the servlet container whenever data is available.
 */
public class AsyncUploadParser extends AbstractUploadParser<AsyncUploadParser> implements ReadListener {

    private ServletInputStream servletInputStream;

    /**
     * The request object.
     */
    private final HttpServletRequest request;

    public AsyncUploadParser(@Nonnull HttpServletRequest request) {
        this.request = requireNonNull(request);
    }

    /**
     * Sets up the necessary objects to start the parsing. Depending upon
     * the environment the concrete implementations can be very different.
     * @throws IOException If an error occurs with the IO
     */
    protected void init() throws IOException {

        // Fail fast mode
        if (maxRequestSize > -1) {
            long requestSize = request.getContentLengthLong();
            if (requestSize > maxRequestSize) {
                throw new RequestSizeException("The size of the request (" + requestSize
                        + ") is greater than the allowed size (" + maxRequestSize + ")!", requestSize, maxRequestSize);
            }
        }

        checkBuffer = ByteBuffer.allocate(sizeThreshold);
        context = new UploadContextImpl(request, uploadResponse);

        String mimeType = request.getHeader(PartStreamHeaders.CONTENT_TYPE);
        String boundary;
        if (mimeType != null && mimeType.startsWith(MULTIPART_FORM_DATA)) {
            boundary = PartStreamHeaders.extractBoundaryFromHeader(mimeType);
            if (boundary == null) {
                throw new IllegalArgumentException("Could not find boundary in multipart request with ContentType: "
                        + mimeType
                        + ", multipart data will not be available");
            }
            String encodingHeader = request.getCharacterEncoding();
            Charset charset = encodingHeader != null ? Charset.forName(encodingHeader) : ISO_8859_1;
            parseState = MultipartParser.beginParse(this, boundary.getBytes(), charset);
        }

        servletInputStream = request.getInputStream();
    }

    /**
     * Performs the necessary operations to setup the async parsing. The parser will
     * register itself to the request stream and the method will quickly return.
     * @throws IOException If an error occurred with the request stream
     */
    public void setupAsyncParse() throws IOException {
        init();
        if (!request.isAsyncSupported()) {
            throw new IllegalStateException("The servlet does not support async mode! Enable it or use a blocking parser.");
        }
        if (!request.isAsyncStarted()) {
            request.startAsync();
        }
        servletInputStream.setReadListener(this);
    }

    /**
     * When an instance of the ReadListener is registered with a ServletInputStream, this method will be invoked
     * by the container the first time when it is possible to read data. Subsequently the container will invoke
     * this method if and only if ServletInputStream.isReady() method has been called and has returned false.
     * @throws IOException if an I/O related error has occurred during processing
     */
    @Override
    public void onDataAvailable() throws IOException {
        while (servletInputStream.isReady()) {
            parseCurrentItem();
        }
    }

    /**
     * Parses the servlet stream once. Will switch to a new item
     * if the current one is fully read.
     *
     * @return Whether it should be called again
     * @throws IOException if an I/O related error has occurred during processing
     */
    private boolean parseCurrentItem() throws IOException {
        int count = servletInputStream.read(buf);
        if (count == -1) {
            if (!parseState.isComplete()) {
                throw new MultipartException();
            }
        } else {
            checkRequestSize(count);
            parseState.parse(ByteBuffer.wrap(buf, 0, count));
        }
        return !parseState.isComplete();
    }

    /**
     * Invoked when all data for the current request has been read.
     * @throws IOException if an I/O related error has occurred during processing
     */
    @Override
    public void onAllDataRead() throws IOException {
        // After the servlet input stream is finished there are still unread bytes or
        // in case of fast uploads or small sizes the initial parse can read the whole
        // input stream, causing the {@link #onDataAvailable} not to be called even once.
        while (true) {
            if (!parseCurrentItem()) {
                break;
            }
        }
        try {
            if (requestCallback != null) {
                requestCallback.onRequestComplete(context);
            }
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Invoked when an error occurs processing the request.
     * @param throwable The unhandled error that happened
     */
    @Override
    public void onError(Throwable throwable) {
        try {
            if (errorCallback != null) {
                errorCallback.onError(context, throwable);
            }
        } catch (IOException | ServletException e) {
            throw new RuntimeException(e);
        }
    }
}
