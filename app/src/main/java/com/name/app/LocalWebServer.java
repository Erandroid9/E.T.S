package com.example.ussdwebview;

import android.content.Context;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.IOException;
import java.io.InputStream;

public class LocalWebServer extends NanoHTTPD {

    private Context context;

    public LocalWebServer(int port, Context context) {
        super(port);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        if (uri.equals("/") || uri.equals("/index.html")) {
            try {
                InputStream html = context.getAssets().open("index.html");
                return Response.newFixedLengthResponse(Status.OK, "text/html", html);
            } catch (IOException e) {
                e.printStackTrace();
                return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error loading index.html");
            }
        }

        return Response.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Not Found");
    }
}
