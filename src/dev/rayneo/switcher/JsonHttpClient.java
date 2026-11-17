package dev.rayneo.switcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class JsonHttpClient {
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;

    JSONObject post(String url, String apiKey, JSONObject payload, int timeout, int maxBytes,
            String service) throws Exception {
        return request("POST", url, apiKey, payload, timeout, maxBytes, service);
    }

    JSONObject get(String url, String apiKey, int timeout, int maxBytes, String service) throws Exception {
        return request("GET", url, apiKey, null, timeout, maxBytes, service);
    }

    private JSONObject request(String method, String url, String apiKey, JSONObject payload,
            int timeout, int maxBytes, String service) throws Exception {
        checkCancelled();
        HttpURLConnection current = (HttpURLConnection) new URL(url).openConnection();
        connection = current;
        try {
            current.setConnectTimeout(10000);
            current.setReadTimeout(timeout);
            current.setRequestMethod(method);
            current.setInstanceFollowRedirects(false);
            current.setRequestProperty("Accept", "application/json");
            if (!apiKey.isEmpty()) {
                current.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            if (payload != null) {
                current.setDoOutput(true);
                current.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                current.setFixedLengthStreamingMode(bytes.length);
                checkCancelled();
                try (OutputStream output = current.getOutputStream()) {
                    output.write(bytes);
                }
            }
            checkCancelled();
            int status = current.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(service + "返回 HTTP " + status);
            }
            try (InputStream input = current.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (output.size() + count > maxBytes) {
                        throw new IllegalStateException(service + "响应超过大小限制");
                    }
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            current.disconnect();
            connection = null;
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("请求已取消");
        }
    }

    void cancel() {
        cancelled = true;
        HttpURLConnection current = connection;
        if (current != null) {
            current.disconnect();
        }
    }
}
