package com.newsblur.network;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;

import android.content.ContentValues;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.grazerss.PL;
import com.newsblur.domain.ValueMultimap;
import com.newsblur.util.NetworkUtils;

public class APIClient {

    private Context context;
    private final String cookie;

    public APIClient(final Context context, final String cookie) {
        this.context = context;
        this.cookie = cookie;
    }

    public APIResponse get(final String urlString) {
        HttpURLConnection connection = null;
        if (!NetworkUtils.isOnline(context)) {
            APIResponse response = new APIResponse();
            response.isOffline = true;
            return response;
        }
        try {
            final URL url = new URL(urlString);
            Log.d(this.getClass().getName(), "API GET " + url);
            connection = (HttpURLConnection) url.openConnection();
            if (cookie != null) {
                connection.setRequestProperty("Cookie", cookie);
            }
            return extractResponse(url, connection);
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "Error opening GET connection to " + urlString, e.getCause());
            return new APIResponse();
        } finally {
            connection.disconnect();
        }
    }

    public APIResponse get(final String urlString, final ContentValues values) {
        List<String> parameters = new ArrayList<String>();
        for (Entry<String, Object> entry : values.valueSet()) {
            StringBuilder builder = new StringBuilder();
            builder.append((String) entry.getKey());
            builder.append("=");
            builder.append(URLEncoder.encode((String) entry.getValue()));
            parameters.add(builder.toString());
        }
        return this.get(urlString + "?" + TextUtils.join("&", parameters));
    }

    public APIResponse get(final String urlString, final ValueMultimap valueMap) {
        return this.get(urlString + "?" + valueMap.getParameterString());
    }

    private APIResponse extractResponse(final URL url, HttpURLConnection connection) throws IOException {
        StringBuilder builder = new StringBuilder();
        final Scanner scanner = new Scanner(connection.getInputStream());
        while (scanner.hasNextLine()) {
            builder.append(scanner.nextLine());
        }

        final APIResponse response = new APIResponse();
        response.responseString = builder.toString();
        response.responseCode = connection.getResponseCode();
        response.cookie = connection.getHeaderField("Set-Cookie");
        response.hasRedirected = !TextUtils.equals(url.getHost(), connection.getURL().getHost());

        return response;
    }

    public APIResponse post(final String urlString, final ContentValues values) {
        HttpURLConnection connection = null;
        if (!NetworkUtils.isOnline(context)) {
            APIResponse response = new APIResponse();
            response.isOffline = true;
            return response;
        }
        List<String> parameters = new ArrayList<String>();
        for (Entry<String, Object> entry : values.valueSet()) {
            final StringBuilder builder = new StringBuilder();

            builder.append((String) entry.getKey());
            builder.append("=");
            try {
                builder.append(URLEncoder.encode((String) entry.getValue(), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                Log.e(this.getClass().getName(), e.getLocalizedMessage());
                return new APIResponse();
            }
            parameters.add(builder.toString());
        }
        final String parameterString = TextUtils.join("&", parameters);
        try {
            final URL url = new URL(urlString);
            Log.d(this.getClass().getName(), "API POST " + url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setFixedLengthStreamingMode(parameterString.getBytes().length);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            if (cookie != null) {
                connection.setRequestProperty("Cookie", cookie);
            }

            try {
                final PrintWriter printWriter = new PrintWriter(connection.getOutputStream());
                printWriter.print(parameterString);
                printWriter.close();
            } catch (IOException e) {
                Log.e(this.getClass().getName(), "Error opening POST connection to " + urlString + ": " + e.getCause(),
                        e.getCause());
                e.printStackTrace();
                String message = "Error opening POST connection to " + urlString + ": " + e.getCause();
                PL.log(message, context);
                return new APIResponse();
            }

            return extractResponse(url, connection);
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "Error opening POST connection to " + urlString + ": " + e.getCause(),
                    e.getCause());
            e.printStackTrace();
            return new APIResponse();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public APIResponse post(final String urlString, final ValueMultimap valueMap) {
        return post(urlString, valueMap, true);
    }

    public APIResponse post(final String urlString, final ValueMultimap valueMap, boolean jsonIfy) {
        HttpURLConnection connection = null;
        if (!NetworkUtils.isOnline(context)) {
            APIResponse response = new APIResponse();
            response.isOffline = true;
            return response;
        }

        try {
            final URL url = new URL(urlString);
            Log.d(this.getClass().getName(), "API POST " + url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            String parameterString = jsonIfy ? valueMap.getJsonString() : valueMap.getParameterString();
            connection.setFixedLengthStreamingMode(parameterString.getBytes().length);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            if (cookie != null) {
                connection.setRequestProperty("Cookie", cookie);
            }

            final PrintWriter printWriter = new PrintWriter(connection.getOutputStream());
            printWriter.print(parameterString);
            printWriter.close();

            return extractResponse(url, connection);
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "Error opening POST connection to " + urlString + ": " + e.getCause(),
                    e.getCause());
            return new APIResponse();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
