package dev.rayneo.switcher;

import android.os.Bundle;

import java.net.URI;

final class SearchConfig {
    static final String OFF = "off";
    static final String OFFICIAL = "official";
    static final String CUSTOM = "custom";
    static final String DEFAULT_URL = "https://api.deepseek.com/v1/tools";
    static final int DEFAULT_FETCH_COUNT = 3;
    final String mode;
    final String name;
    final String endpoint;
    final String toolsBaseUrl;
    final String apiKey;
    final int count;
    final boolean needContent;
    final String scope;
    final int fetchCount;

    SearchConfig(String mode, String name, String endpoint, String apiKey,
            int count, boolean needContent, String scope) {
        this(mode, name, endpoint, apiKey, count, needContent, scope, null, 0);
    }

    SearchConfig(String mode, String name, String endpoint, String apiKey,
            int count, boolean needContent, String scope, String toolsBaseUrl, int fetchCount) {
        this.mode = mode;
        this.name = name.trim();
        this.endpoint = endpoint.trim();
        this.toolsBaseUrl = toolsBaseUrl == null ? legacyBaseUrl(this.endpoint) : toolsBaseUrl.trim();
        this.apiKey = apiKey.trim();
        this.count = count;
        this.needContent = needContent;
        this.scope = scope.trim();
        this.fetchCount = fetchCount;
    }

    static SearchConfig tools(String name, String address, String apiKey, int count,
            boolean needContent, String scope, int fetchCount) {
        String baseUrl = address.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/search") || baseUrl.endsWith("/fetch")) {
            baseUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/'));
        }
        return new SearchConfig(CUSTOM, name, baseUrl + "/search", apiKey,
                count, needContent, scope, baseUrl, fetchCount);
    }

    private static String legacyBaseUrl(String endpoint) {
        return endpoint.endsWith("/search") ? endpoint.substring(0, endpoint.length() - 7) : "";
    }

    static int defaultFetchCount(String endpoint) {
        return legacyBaseUrl(endpoint).isEmpty() ? 0 : DEFAULT_FETCH_COUNT;
    }

    String address() {
        return toolsBaseUrl.isEmpty() ? endpoint : toolsBaseUrl;
    }

    String fetchEndpoint() {
        if (toolsBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("请填写工具基础地址以使用网页抓取");
        }
        return toolsBaseUrl + "/fetch";
    }

    static SearchConfig builtin(String mode) {
        return new SearchConfig(mode, OFFICIAL.equals(mode) ? "官方搜索" : "关闭联网搜索",
                "", "", 5, false, "");
    }

    void validate() throws Exception {
        if (!(OFF.equals(mode) || OFFICIAL.equals(mode) || CUSTOM.equals(mode))) {
            throw new IllegalArgumentException("搜索方式无效");
        }
        if (!CUSTOM.equals(mode)) {
            return;
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("请填写搜索供应商名称");
        }
        URI uri = new URI(endpoint);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("请填写有效的工具基础地址");
        }
        if (!toolsBaseUrl.isEmpty() && !endpoint.equals(toolsBaseUrl + "/search")) {
            throw new IllegalArgumentException("工具基础地址与搜索地址不一致");
        }
        if (count < 1 || count > 50) {
            throw new IllegalArgumentException("搜索结果条数应为 1 到 50");
        }
        if (fetchCount < 0 || fetchCount > 50) {
            throw new IllegalArgumentException("补抓网页数量应为 0 到 50，0 表示关闭");
        }
        if (fetchCount > 0) {
            fetchEndpoint();
        }
        if (scope.codePointCount(0, scope.length()) > 126) {
            throw new IllegalArgumentException("搜索范围最多填写 126 个字符");
        }
    }

    static String pageUrl(String value) throws Exception {
        String url = value.trim();
        URI uri = new URI(url);
        if (url.codePointCount(0, url.length()) > 8192
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("请填写有效的 HTTP/HTTPS 网页地址，最多 8192 个字符");
        }
        return url;
    }

    String query(String question) {
        String original = question.trim();
        if (original.isEmpty()) {
            throw new IllegalArgumentException("请填写搜索问题");
        }
        int reserved = scope.isEmpty() ? 0 : scope.codePointCount(0, scope.length()) + 1;
        String query = limit(original, 128 - reserved);
        return scope.isEmpty() ? query : query + " " + scope;
    }

    static String limit(String text, int characters) {
        if (text.codePointCount(0, text.length()) <= characters) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, Math.max(0, characters)));
    }

    Bundle toBundle() {
        Bundle value = new Bundle();
        value.putString("mode", mode);
        value.putString("name", name);
        value.putString("endpoint", endpoint);
        value.putString("tools_base_url", toolsBaseUrl);
        value.putString("api_key", apiKey);
        value.putInt("count", count);
        value.putBoolean("need_content", needContent);
        value.putString("scope", scope);
        value.putInt("fetch_count", fetchCount);
        return value;
    }

    static SearchConfig fromBundle(Bundle value) {
        if (value == null) {
            return builtin(OFF);
        }
        String endpoint = value.getString("endpoint", "");
        return new SearchConfig(value.getString("mode", OFF), value.getString("name", ""),
                endpoint, value.getString("api_key", ""),
                value.getInt("count", 5), value.getBoolean("need_content", false),
                value.getString("scope", ""), value.getString("tools_base_url"),
                value.getInt("fetch_count", defaultFetchCount(endpoint)));
    }
}
