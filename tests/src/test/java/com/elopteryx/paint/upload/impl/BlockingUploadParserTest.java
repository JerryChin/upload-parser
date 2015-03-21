package com.elopteryx.paint.upload.impl;

import static com.elopteryx.paint.upload.util.Servlets.newRequest;
import static com.elopteryx.paint.upload.util.Servlets.newResponse;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import com.elopteryx.paint.upload.OnError;
import com.elopteryx.paint.upload.OnPartBegin;
import com.elopteryx.paint.upload.OnPartEnd;
import com.elopteryx.paint.upload.PartOutput;
import com.elopteryx.paint.upload.UploadContext;
import com.elopteryx.paint.upload.UploadParser;
import com.elopteryx.paint.upload.UploadResponse;
import com.elopteryx.paint.upload.errors.MultipartException;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BlockingUploadParserTest implements OnPartBegin, OnPartEnd, OnError {
    
    private List<ByteArrayOutputStream> strings = new ArrayList<>();

    @Test
    public void this_should_end_with_multipart_exception() throws Exception {
        HttpServletRequest request = newRequest();
        HttpServletResponse response = newResponse();

        when(request.isAsyncSupported()).thenReturn(false);
        when(request.getHeader(PartStreamHeaders.CONTENT_TYPE)).thenReturn("multipart/form-data; boundary=----1234");

        UploadParser.newBlockingParser(request)
                .onPartBegin(this)
                .onPartEnd(this)
                .onError(this)
                .userObject(UploadResponse.from(response))
                .doBlockingParse();
    }

    @Test(expected = IllegalArgumentException.class)
    public void this_should_end_with_illegal_argument_exception() throws Exception {
        HttpServletRequest request = newRequest();

        when(request.isAsyncSupported()).thenReturn(false);
        when(request.getHeader(PartStreamHeaders.CONTENT_TYPE)).thenReturn("multipart/form-data;");

        UploadParser.newBlockingParser(request).doBlockingParse();
    }

    @Override
    @Nonnull
    public PartOutput onPartBegin(UploadContext context, ByteBuffer buffer) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        strings.add(baos);
        return PartOutput.from(baos);
    }

    @Override
    public void onPartEnd(UploadContext context) throws IOException {
        System.out.println(strings.get(strings.size() - 1).toString());
    }

    @Override
    public void onError(UploadContext context, Throwable throwable) {
        assertThat(throwable, instanceOf(MultipartException.class));
    }
}