package dev.rayneo.switcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class SearchClient {
    private final JsonHttpClient http = new JsonHttpClient();
    private final List<JsonHttpClient> activeFetches = new ArrayList<>();
    private volatile boolean cancelled;

    private static final class Page {
        String title;
        final String url;
        String content;
        final boolean hasFullContent;

        Page(String title, String url, String content, boolean hasFullContent) {
            this.title = title;
            this.url = url;
            this.content = content;
            this.hasFullContent = hasFullContent;
        }
    }

    SearchContext search(SearchConfig config, String question) throws Exception {
        validate(config);
        JSONObject payload = new JSONObject().put("query", config.query(question))
                .put("ori_question", SearchConfig.limit(question.trim(), 128))
                .put("count", config.count).put("need_content", config.needContent);
        JSONObject response = http.post(config.endpoint, config.apiKey, payload,
                15000, 2 * 1024 * 1024, "搜索接口");
        JSONArray results = response.optJSONArray("results");
        if (results == null) {
            throw new IllegalStateException("搜索接口缺少 results 数组，请确认兼容 DeepSeek Search 协议");
        }
        Map<String, Page> uniquePages = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(results.length(), config.count); index++) {
            JSONObject item = results.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String url;
            try {
                url = SearchConfig.pageUrl(item.optString("url", ""));
            } catch (Exception invalidUrl) {
                continue;
            }
            String content = config.needContent ? item.optString("content", "").trim() : "";
            boolean hasFullContent = !content.isEmpty();
            if (content.isEmpty()) {
                content = item.optString("summary", "");
            }
            if (content.isEmpty()) {
                content = item.optString("snippet", "");
            }
            Page previous = uniquePages.get(url);
            if (previous == null || (hasFullContent && !previous.hasFullContent)
                    || (hasFullContent == previous.hasFullContent && content.length() > previous.content.length())) {
                uniquePages.put(url, new Page(item.optString("title", ""), url, content, hasFullContent));
            }
        }
        List<Page> pages = new ArrayList<>(uniquePages.values());
        SearchContext context = new SearchContext(config.name);
        enrich(config, pages, context);
        for (Page page : pages) {
            context.add(page.title, page.url, page.content);
        }
        checkCancelled();
        return context;
    }

    SearchContext fetch(SearchConfig config, String url) throws Exception {
        validate(config);
        String pageUrl = SearchConfig.pageUrl(url);
        JSONObject page = fetchPage(config, pageUrl);
        SearchContext context = new SearchContext(config.name + " · 网页抓取");
        context.add(page.getString("title"), pageUrl, page.getString("content"));
        checkCancelled();
        return context;
    }

    private void validate(SearchConfig config) throws Exception {
        checkCancelled();
        config.validate();
        if (!SearchConfig.CUSTOM.equals(config.mode)) {
            throw new IllegalArgumentException("请选择自定义搜索供应商");
        }
    }

    private void enrich(SearchConfig config, List<Page> pages, SearchContext context) throws Exception {
        List<Page> candidates = new ArrayList<>();
        for (Page page : pages) {
            if (!page.hasFullContent && candidates.size() < config.fetchCount) {
                candidates.add(page);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(3, candidates.size()));
        try {
            List<Future<Boolean>> requests = new ArrayList<>();
            for (Page page : candidates) {
                checkCancelled();
                requests.add(workers.submit(() -> {
                    try {
                        JSONObject fetched = fetchPage(config, page.url);
                        String title = fetched.getString("title");
                        if (!title.trim().isEmpty()) {
                            page.title = title;
                        }
                        page.content = fetched.getString("content");
                        return true;
                    } catch (InterruptedException error) {
                        throw error;
                    } catch (Exception error) {
                        return false;
                    }
                }));
            }
            int fetched = 0;
            for (Future<Boolean> request : requests) {
                if (request.get()) {
                    fetched++;
                }
            }
            checkCancelled();
            int failed = requests.size() - fetched;
            context.note("已补抓 " + fetched + " 篇网页正文。"
                    + (failed == 0 ? "" : failed + " 篇抓取失败，已使用搜索返回的资料。"));
        } catch (Exception error) {
            cancel();
            throw error;
        } finally {
            workers.shutdownNow();
        }
    }

    private JSONObject fetchPage(SearchConfig config, String url) throws Exception {
        String endpoint = config.fetchEndpoint();
        JsonHttpClient fetch = new JsonHttpClient();
        synchronized (activeFetches) {
            checkCancelled();
            activeFetches.add(fetch);
        }
        try {
            JSONObject response = fetch.post(endpoint, config.apiKey, new JSONObject().put("url", url),
                    15000, 2 * 1024 * 1024, "抓取接口");
            if (!(response.opt("title") instanceof String) || !(response.opt("content") instanceof String)) {
                throw new IllegalStateException("抓取接口缺少 title 或 content 字符串，请确认兼容 DeepSeek Fetch 协议");
            }
            if (response.getString("content").trim().isEmpty()) {
                throw new IllegalStateException("抓取接口未返回可用的网页正文");
            }
            return response;
        } finally {
            synchronized (activeFetches) {
                activeFetches.remove(fetch);
            }
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("请求已取消");
        }
    }

    void cancel() {
        cancelled = true;
        http.cancel();
        synchronized (activeFetches) {
            for (JsonHttpClient fetch : activeFetches) {
                fetch.cancel();
            }
        }
    }
}
