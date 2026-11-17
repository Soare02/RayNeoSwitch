package dev.rayneo.switcher;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class SearchRegressionTests {
    private final Context target;

    SearchRegressionTests(Instrumentation instrumentation) {
        target = instrumentation.getTargetContext();
    }

    int run() throws Exception {
        profiles();
        bundleAndRuntime();
        validationAndScope();
        searchAndModelHttp();
        emptyAndInvalidResponses();
        cancellation();
        officialAnswerFallback();
        officialSources();
        toolsAddressesAndMigration();
        fetchHttp();
        searchFetchAndModelHttp();
        fetchFailuresAndCancellation();
        officialNativeCompletion();
        officialNativeError();
        return 14;
    }

    private SearchConfig config(String name, String key) {
        return SearchConfig.tools(name, SearchConfig.DEFAULT_URL, key, 5, false, "site:example.com", 0);
    }

    private void profiles() throws Exception {
        Fixture fixture = new Fixture(target);
        SearchStore store = new SearchStore(fixture);
        check(store.providers().isEmpty() && SearchConfig.OFF.equals(store.read().mode), "default search must be off");
        SearchStore.Provider first = store.save(null, config("First", "first-search-secret"));
        SearchStore.Provider second = store.save(null, config("Second", "second-search-secret"));
        check(store.providers().size() == 2, "saving search replaced a provider");
        store.activate(first.id);
        check("first-search-secret".equals(store.read().apiKey), "first search key mismatch");
        store.activate(second.id);
        check("second-search-secret".equals(store.read().apiKey), "second search key mismatch");
        store.save(second.id, config("Renamed", "updated-search-secret"));
        check(store.providers().size() == 2 && "Renamed".equals(new SearchStore(fixture).read().name), "rename failed");
        check(!fixture.preferences("search_config").getAll().toString().contains("updated-search-secret"), "search key persisted as plaintext");
        store.setBuiltin(SearchConfig.OFFICIAL);
        store.delete(second.id);
        check(SearchConfig.OFFICIAL.equals(store.read().mode), "inactive deletion changed official selection");
        store.activate(first.id);
        store.delete(first.id);
        check(SearchConfig.OFF.equals(store.read().mode) && store.providers().isEmpty(), "last custom deletion failed");
        SearchStore.Provider last = store.save(null, config("Last", "last-secret"));
        store.setBuiltin(SearchConfig.OFFICIAL);
        store.delete(last.id);
        check(SearchConfig.OFFICIAL.equals(store.read().mode), "remembered last provider deletion disabled official search");
    }

    private void bundleAndRuntime() throws Exception {
        Fixture fixture = new Fixture(target);
        ConfigStore models = new ConfigStore(fixture);
        models.save(new ConfigStore.Config(true, "https://model.example/v1", "test-model", "model-secret"));
        SearchStore searches = new SearchStore(fixture);
        searches.save(null, config("Search", "search-secret"));
        Bundle bundle = ConfigStore.currentBundle(fixture);
        ConfigStore.Config received = ConfigStore.Config.fromBundle(bundle);
        check("model-secret".equals(received.apiKey) && "search-secret".equals(received.search.apiKey), "combined config lost separate credentials");
        ConfigStore runtime = new ConfigStore(fixture, "runtime_fixture");
        runtime.saveRuntime(received);
        ConfigStore.Config cached = new ConfigStore(fixture, "runtime_fixture").read();
        check("search-secret".equals(cached.search.apiKey) && cached.search.scope.equals(received.search.scope), "runtime search cache lost configuration");
        check(!fixture.preferences("runtime_fixture").getAll().toString().contains("search-secret"), "runtime search key plaintext");
        models.save(new ConfigStore.Config(true, "https://other.example", "other-model", "other-key"));
        check("Search".equals(searches.read().name), "model save replaced search config");
        models.save(new ConfigStore.Config(false, "https://other.example", "other-model", "other-key"));
        check(SearchConfig.OFF.equals(ConfigStore.Config.fromBundle(ConfigStore.currentBundle(fixture)).search.mode), "disabled model transmitted active search");
        bundle.remove("search");
        check(SearchConfig.OFF.equals(ConfigStore.Config.fromBundle(bundle).search.mode), "legacy bundle enabled search");
    }

    private void validationAndScope() throws Exception {
        Fixture fixture = new Fixture(target);
        SearchStore store = new SearchStore(fixture);
        SearchStore.Provider first = store.save(null, config("Valid", "secret"));
        Map<String, ?> before = fixture.preferences("search_config").getAll();
        expectFailure(() -> store.save(first.id, new SearchConfig(SearchConfig.CUSTOM, "", SearchConfig.DEFAULT_URL, "", 5, false, "")));
        expectFailure(() -> store.save(first.id, new SearchConfig(SearchConfig.CUSTOM, "X", "bad", "", 5, false, "")));
        expectFailure(() -> store.save(first.id, new SearchConfig(SearchConfig.CUSTOM, "X", SearchConfig.DEFAULT_URL, "", 51, false, "")));
        expectFailure(() -> store.activate("missing"));
        check(before.equals(fixture.preferences("search_config").getAll()), "invalid save mutated search state");
        StringBuilder question = new StringBuilder();
        for (int index = 0; index < 160; index++) {
            question.appendCodePoint(0x1f600);
        }
        SearchConfig config = config("Range", "secret");
        String query = config.query(question.toString());
        check(query.codePointCount(0, query.length()) == 128 && query.endsWith(config.scope), "scope/unicode query limit failed");
        check(SearchConfig.limit(question.toString(), 128).codePointCount(0, 256) == 128, "original question truncation failed");
        expectFailure(() -> config.query(" "));
    }

    private void searchAndModelHttp() throws Exception {
        try (HttpFixture server = new HttpFixture()) {
            SearchConfig config = new SearchConfig(SearchConfig.CUSTOM, "Local search", server.url("/search"),
                    "fake-search-key", 3, true, "site:example.com lang:zh");
            SearchContext context = new SearchClient().search(config, "测试问题");
            check(context.size() == 1 && context.render().contains("完整网页正文"), "need_content result not passed through");
            check(server.searchRequest.getString("query").equals("测试问题 site:example.com lang:zh"), "search query mismatch");
            check(server.searchRequest.getString("ori_question").equals("测试问题"), "original question missing");
            check(server.searchRequest.getInt("count") == 3 && server.searchRequest.getBoolean("need_content"), "search request options missing");
            check("Bearer fake-search-key".equals(server.searchAuthorization), "search bearer missing");
            ConfigStore.Config model = new ConfigStore.Config(true, server.url("/v1"), "fake-model", "fake-model-key");
            String answer = new ModelClient().ask(model, "测试问题", context);
            check("检索回答 [1]".equals(answer), "model response failed");
            JSONArray messages = server.modelRequest.getJSONArray("messages");
            String prompt = messages.getJSONObject(1).getString("content");
            check(prompt.contains("测试问题") && prompt.contains("完整网页正文") && prompt.contains("https://example.com/source"), "model did not receive search evidence");
            check("Bearer fake-model-key".equals(server.modelAuthorization), "search and model credentials mixed");
            new ModelClient().ask(model, "普通问题");
            check("普通问题".equals(server.modelRequest.getJSONArray("messages").getJSONObject(1).getString("content")), "plain question behavior changed");
        }
    }

    private void emptyAndInvalidResponses() throws Exception {
        try (HttpFixture server = new HttpFixture()) {
            SearchConfig config = new SearchConfig(SearchConfig.CUSTOM, "Empty", server.url("/empty"), "", 5, false, "");
            SearchContext empty = new SearchClient().search(config, "question");
            check(empty.size() == 0 && empty.render().contains("未找到"), "empty results misrepresented");
            SearchConfig bad = new SearchConfig(SearchConfig.CUSTOM, "Bad", server.url("/bad"), "", 5, false, "");
            expectFailure(() -> new SearchClient().search(bad, "question"));
            SearchConfig denied = new SearchConfig(SearchConfig.CUSTOM, "Denied", server.url("/denied"), "", 5, false, "");
            expectFailure(() -> new SearchClient().search(denied, "question"));
            SearchConfig summary = new SearchConfig(SearchConfig.CUSTOM, "Summary", server.url("/search"), "", 5, false, "");
            check(new SearchClient().search(summary, "question").render().contains("网页长摘要"), "summary fallback failed");
        }
    }

    private void cancellation() throws Exception {
        SearchClient client = new SearchClient();
        client.cancel();
        expectFailure(() -> client.search(config("Cancelled", ""), "question"));
        ModelClient model = new ModelClient();
        model.cancel();
        expectFailure(() -> model.ask(new ConfigStore.Config(true, "https://example.com", "model", ""), "question"));
    }

    private void officialAnswerFallback() throws Exception {
        OfficialSearchCollector collector = new OfficialSearchCollector();
        check(!collector.ready(), "empty official turn reported complete");
        collector.tool("search_web");
        collector.metadata("{\"webResultCount\":5,\"searchSummary\":\"已检索 5 个来源并整理答案\"}");
        expectFailure(() -> collector.finish(false));
        collector.answer("官方", false);
        collector.answer("官方回答", false);
        collector.answer("内容", false);
        collector.answer("官方回答内容", true);
        check(collector.ready(), "completed official turn not ready");
        SearchContext result = collector.finish(false);
        check(result.description().equals("官方联网回答参考"), "official fallback label missing");
        check(result.render().contains("官方回答内容") && !result.render().contains("已检索 5"), "statistics used as evidence");
        OfficialSearchCollector repeated = new OfficialSearchCollector();
        repeated.answer("哈", false);
        repeated.answer("哈", false);
        check(repeated.finish(true).render().contains("哈哈"), "repeated streamed text lost");
        check(repeated.finish(true).render().contains("等待超时"), "partial timeout not labelled");
        expectFailure(() -> new OfficialSearchCollector().finish(true));
        OfficialSearchCollector current = new OfficialSearchCollector("当前问题");
        current.answer("当前部分回答", false);
        current.metadata("{\"query\":\"上一次问题\",\"eventType\":\"RAYCLAW_CHAT_EVENT_TYPE_FINISHED\",\"webResultCount\":5}");
        check(!current.ready(), "stale metadata completed a newer turn");
        current.metadata("{\"query\":\"当前问题\",\"eventType\":\"RAYCLAW_CHAT_EVENT_TYPE_FINISHED\"}");
        check(current.ready(), "current metadata did not complete its turn");
    }

    private void officialSources() throws Exception {
        OfficialSearchCollector collector = new OfficialSearchCollector();
        collector.metadata("{\"backReferenceList\":[{\"title\":\"标题\",\"url\":\"https://example.com/source\",\"summary\":\"来源摘要\"}]}");
        collector.metadata("{\"rawData\":\"{\\\"backendReferenceList\\\":[{\\\"title\\\":\\\"标题\\\",\\\"url\\\":\\\"https://example.com/source\\\",\\\"content\\\":\\\"更完整的来源正文内容\\\"}]}\"}");
        collector.answer("官方回答不应覆盖网页正文", true);
        SearchContext result = collector.finish(false);
        check(result.size() == 1 && result.render().contains("更完整的来源正文内容"), "official references missing or duplicated");
        check(!result.render().contains("官方回答不应覆盖"), "official answer replaced full source material");
        SearchContext unsafe = new SearchContext("test");
        unsafe.add("bad", "javascript:alert(1)", "bad");
        check(unsafe.size() == 0, "non-web reference accepted");
    }

    private void officialNativeCompletion() throws Exception {
        OfficialSearchCollector collector = new OfficialSearchCollector("问题");
        collector.tool("search_web");
        collector.answer("官方返回的参考答案", false);
        collector.metadata("{\"query\":\"问题\",\"webResultCount\":3,\"status\":\"reading\"}");
        check(!collector.ready(), "official progress ended the wait early");
        collector.complete();
        check(collector.ready(), "native completion did not finish streamed answers");
        check(!collector.finish(false).render().contains("等待超时"), "completed native response was marked as timed out");
        OfficialSearchCollector empty = new OfficialSearchCollector();
        empty.complete();
        expectFailure(() -> empty.finish(false));
    }

    private void officialNativeError() throws Exception {
        OfficialSearchCollector partial = new OfficialSearchCollector();
        partial.answer("原生等待结束前的资料", false);
        partial.error(1008);
        check(partial.ready() && partial.finish(false).render().contains("官方服务等待超时"), "native timeout did not label partial evidence");
        OfficialSearchCollector failed = new OfficialSearchCollector();
        failed.error(1008);
        try {
            failed.finish(false);
            throw new AssertionError("native timeout without evidence succeeded");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("官方服务等待超时"), "native timeout cause was lost");
        }
        partial.error(1006);
        check(partial.finish(false).render().contains("1006"), "native error code was lost");
    }

    private void toolsAddressesAndMigration() throws Exception {
        for (String suffix : new String[]{"", "/", "/search", "/fetch/"}) {
            SearchConfig config = SearchConfig.tools("Tools", SearchConfig.DEFAULT_URL + suffix,
                    "fake-tools-key", 5, false, "", 3);
            config.validate();
            check(config.address().equals(SearchConfig.DEFAULT_URL), "tools base normalization failed");
            check(config.endpoint.equals(SearchConfig.DEFAULT_URL + "/search"), "search route was not appended");
            check(config.fetchEndpoint().equals(SearchConfig.DEFAULT_URL + "/fetch"), "fetch route was not appended");
        }
        expectFailure(() -> SearchConfig.tools("X", "https://user:password@example.com/tools", "", 5, false, "", 3).validate());
        expectFailure(() -> SearchConfig.tools("X", "https://example.com/tools?q=1", "", 5, false, "", 3).validate());
        expectFailure(() -> SearchConfig.tools("X", SearchConfig.DEFAULT_URL, "", 5, false, "", -1).validate());
        expectFailure(() -> SearchConfig.tools("X", SearchConfig.DEFAULT_URL, "", 5, false, "", 51).validate());

        Fixture fixture = new Fixture(target);
        SearchStore store = new SearchStore(fixture);
        SearchConfig tools = SearchConfig.tools("Tools", SearchConfig.DEFAULT_URL, "fake-tools-key", 7, true, "lang:zh", 2);
        SearchStore.Provider saved = store.save(null, tools);
        check(new SearchStore(fixture).providers().get(0).config().fetchCount == 2, "provider lost fetch count");
        SearchConfig bundled = SearchConfig.fromBundle(tools.toBundle());
        check(bundled.fetchCount == 2 && bundled.fetchEndpoint().equals(tools.fetchEndpoint()), "bundle lost fetch settings");
        ConfigStore runtime = new ConfigStore(fixture, "tools_runtime");
        runtime.saveRuntime(new ConfigStore.Config(true, "https://model.example", "model", "model-key", tools));
        check(runtime.read().search.fetchCount == 2 && runtime.read().search.toolsBaseUrl.equals(tools.toolsBaseUrl), "runtime lost fetch settings");

        JSONArray profiles = new JSONArray(fixture.preferences("search_config").getString("providers", "[]"));
        JSONObject legacy = profiles.getJSONObject(0);
        legacy.remove("tools_base_url");
        legacy.remove("fetch_count");
        fixture.preferences("search_config").edit().putString("providers", profiles.toString())
                .remove("search_tools_base_url").remove("search_fetch_count").commit();
        check(new SearchStore(fixture).providers().get(0).config().fetchCount == 3, "legacy provider was not upgraded");
        check(store.read().fetchCount == 3 && store.read().toolsBaseUrl.equals(SearchConfig.DEFAULT_URL), "legacy runtime was not upgraded");
        store.activate(saved.id);
        check("fake-tools-key".equals(store.read().apiKey), "migration changed search credentials");

        Bundle oldBundle = tools.toBundle();
        oldBundle.remove("tools_base_url");
        oldBundle.remove("fetch_count");
        check(SearchConfig.fromBundle(oldBundle).fetchCount == 3, "legacy bundle was not upgraded");
        oldBundle.putString("endpoint", "https://example.com/custom-search-handler");
        SearchConfig standalone = SearchConfig.fromBundle(oldBundle);
        check(standalone.endpoint.equals("https://example.com/custom-search-handler") && standalone.fetchCount == 0,
                "standalone legacy endpoint changed");
        expectFailure(standalone::fetchEndpoint);
    }

    private void fetchHttp() throws Exception {
        try (HttpFixture server = new HttpFixture()) {
            SearchConfig config = SearchConfig.tools("Fetch", server.url("/v1/tools/"), "fake-tools-key", 5, false, "", 0);
            SearchContext context = new SearchClient().fetch(config, " https://example.com/article?a=1#section ");
            check(context.size() == 1 && context.render().contains("# 抓取正文"), "fetched Markdown was not returned");
            check(context.render().contains("抓取标题"), "fetched page title missing");
            check(server.fetchRequest.getString("url").equals("https://example.com/article?a=1#section"), "fetch URL mismatch");
            check("Bearer fake-tools-key".equals(server.fetchAuthorization) && server.fetchRequest.length() == 1,
                    "fetch auth or request schema mismatch");
            check(server.fetchPaths.get(0).equals("/v1/tools/fetch"), "fetch used the wrong route");
            for (String url : new String[]{"javascript:alert(1)", "https://user:password@example.com/", "/relative", ""}) {
                expectFailure(() -> new SearchClient().fetch(config, url));
            }
            StringBuilder tooLong = new StringBuilder("https://example.com/");
            while (tooLong.length() <= 8192) {
                tooLong.append('a');
            }
            expectFailure(() -> new SearchClient().fetch(config, tooLong.toString()));
            check(server.fetchRequests.size() == 1, "invalid fetch URL was sent");
            SearchClient cancelled = new SearchClient();
            cancelled.cancel();
            expectFailure(() -> cancelled.fetch(config, "https://example.com/"));
            for (String base : new String[]{"/denied-fetch", "/bad-fetch", "/empty-fetch"}) {
                SearchConfig invalid = SearchConfig.tools("Invalid", server.url(base), "", 5, false, "", 0);
                expectFailure(() -> new SearchClient().fetch(invalid, "https://example.com/"));
            }
        }
    }

    private void searchFetchAndModelHttp() throws Exception {
        try (HttpFixture server = new HttpFixture()) {
            SearchConfig config = SearchConfig.tools("Tools", server.url("/v1/tools"), "fake-tools-key", 5, false, "lang:zh", 3);
            SearchContext context = new SearchClient().search(config, "读取网页");
            check(server.fetchRequests.size() == 1 && context.render().contains("独立抓取的 Markdown 内容"), "search did not fetch page content");
            check(server.searchAuthorization.equals(server.fetchAuthorization), "search and fetch did not share credentials");
            ConfigStore.Config model = new ConfigStore.Config(true, server.url("/v1"), "fake-model", "fake-model-key");
            new ModelClient().ask(model, "读取网页", context);
            String prompt = server.modelRequest.getJSONArray("messages").getJSONObject(1).getString("content");
            check(prompt.contains("独立抓取的 Markdown 内容") && prompt.contains("https://example.com/source"), "model did not receive fetched evidence");
            check("Bearer fake-model-key".equals(server.modelAuthorization), "tools key reached model endpoint");
            SearchConfig suppliedContent = SearchConfig.tools("Full", server.url("/v1/tools"), "", 5, true, "", 3);
            check(new SearchClient().search(suppliedContent, "question").render().contains("完整网页正文"), "search content was discarded");
            check(server.fetchRequests.size() == 1, "already supplied content was fetched again");
            SearchConfig duplicates = SearchConfig.tools("Duplicates", server.url("/duplicates"), "", 5, true, "", 3);
            SearchContext duplicateContent = new SearchClient().search(duplicates, "question");
            check(duplicateContent.size() == 1 && duplicateContent.render().contains("后续重复结果的完整正文"), "duplicate source lost better content");
            check(server.fetchRequests.size() == 1, "duplicate supplied content was fetched again");
            SearchConfig many = SearchConfig.tools("Many", server.url("/many"), "", 6, false, "", 2);
            SearchContext ranked = new SearchClient().search(many, "question");
            check(ranked.size() == 4 && server.fetchRequests.size() == 3, "fetch budget or source deduplication failed");
            String rendered = ranked.render();
            check(rendered.indexOf("https://example.com/source-1") < rendered.indexOf("https://example.com/source-2")
                    && rendered.indexOf("https://example.com/source-2") < rendered.indexOf("https://example.com/source-3"), "source ranking changed");
            check(rendered.contains("第3条搜索摘要") && rendered.contains("第4条搜索摘要"), "unfetched summaries were lost");
        }
    }

    private void fetchFailuresAndCancellation() throws Exception {
        try (HttpFixture server = new HttpFixture()) {
            for (String base : new String[]{"/denied-fetch", "/bad-fetch", "/empty-fetch"}) {
                SearchConfig config = SearchConfig.tools("Fallback", server.url(base), "fake-tools-key", 5, false, "", 3);
                SearchContext result = new SearchClient().search(config, "question");
                check(result.render().contains("网页长摘要") && result.render().contains("1 篇抓取失败"), "fetch failure did not preserve summaries");
            }
            check(server.fetchRequests.size() == 3, "failed fetch was retried unexpectedly");
        }
        try (HttpFixture server = new HttpFixture()) {
            SearchClient client = new SearchClient();
            SearchConfig config = SearchConfig.tools("Cancel", server.url("/slow-many"), "", 6, false, "", 3);
            FutureTask<SearchContext> request = new FutureTask<>(() -> client.search(config, "question"));
            Thread thread = new Thread(request, "cancel-fetch-regression");
            thread.start();
            try {
                check(server.concurrentFetches.await(3, TimeUnit.SECONDS), "fetch requests did not run concurrently");
                client.cancel();
                try {
                    request.get(3, TimeUnit.SECONDS);
                    throw new AssertionError("cancelled fetch returned a successful context");
                } catch (ExecutionException expected) {
                    check(expected.getCause() instanceof Exception, "unexpected cancellation failure");
                }
            } finally {
                client.cancel();
                server.releaseFetch.countDown();
                thread.join(3000);
            }
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void expectFailure(Action action) throws Exception {
        try {
            action.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("Expected failure");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture extends ContextWrapper {
        private final Map<String, ProviderStoreInstrumentation.MemoryPreferences> files = new HashMap<>();

        Fixture(Context target) {
            super(target);
        }

        SharedPreferences preferences(String name) {
            return getSharedPreferences(name, 0);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return files.computeIfAbsent(name, key -> new ProviderStoreInstrumentation.MemoryPreferences());
        }
    }

    static final class HttpFixture implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final ExecutorService connections = Executors.newFixedThreadPool(4);
        volatile JSONObject searchRequest;
        volatile JSONObject modelRequest;
        volatile JSONObject modelResponse;
        volatile String modelListMethod;
        volatile String modelListPath;
        volatile String modelListAuthorization;
        volatile int modelListBodyLength;
        volatile int modelListCalls;
        volatile JSONObject fetchRequest;
        volatile String searchAuthorization;
        volatile String modelAuthorization;
        volatile String fetchAuthorization;
        final List<JSONObject> fetchRequests = Collections.synchronizedList(new ArrayList<>());
        final List<String> fetchPaths = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch concurrentFetches = new CountDownLatch(3);
        final CountDownLatch releaseFetch = new CountDownLatch(1);

        HttpFixture() throws Exception {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "search-test-http");
            thread.setDaemon(true);
            thread.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    connections.submit(() -> respond(socket));
                } catch (Exception error) {
                    if (!server.isClosed()) {
                        throw new AssertionError(error);
                    }
                }
            }
        }

        private void respond(Socket accepted) {
            try (Socket socket = accepted) {
                socket.setSoTimeout(3000);
                InputStream input = socket.getInputStream();
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                String text;
                do {
                    int value = input.read();
                    if (value == -1) {
                        throw new IllegalStateException("incomplete headers");
                    }
                    header.write(value);
                    text = header.toString("UTF-8");
                } while (!text.endsWith("\r\n\r\n"));
                int length = 0;
                String authorization = "";
                for (String line : text.split("\r\n")) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(15).trim());
                    }
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:")) {
                        authorization = line.substring(14).trim();
                    }
                }
                byte[] bytes = new byte[length];
                int offset = 0;
                while (offset < length) {
                    int count = input.read(bytes, offset, length - offset);
                    if (count < 0) {
                        throw new IllegalStateException("incomplete body");
                    }
                    offset += count;
                }
                JSONObject request = length == 0 ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                String method = text.split(" ")[0];
                String path = text.split(" ")[1];
                int status = 200;
                String response;
                if (path.endsWith("/models")) {
                    modelListMethod = method;
                    modelListPath = path;
                    modelListAuthorization = authorization;
                    modelListBodyLength = length;
                    modelListCalls++;
                    if (path.equals("/denied-models/models")) {
                        status = 401;
                        response = "{}";
                    } else if (path.equals("/empty-models/models")) {
                        response = "{\"data\":[]}";
                    } else if (path.equals("/bad-models/models")) {
                        response = "{}";
                    } else {
                        response = "{\"data\":[{\"id\":\"catalog-alpha\"},{\"id\":\"catalog-beta\"},"
                                + "{\"id\":\"catalog-alpha\"},{\"id\":\"Reasoning-Model\"},{\"id\":\"reasoning-model\"},"
                                + "{\"id\":12},{\"id\":null},{\"id\":\" \"},null]}";
                    }
                } else if (path.endsWith("chat/completions")) {
                    modelRequest = request;
                    modelAuthorization = authorization;
                    JSONObject configuredResponse = modelResponse;
                    response = configuredResponse == null ? "{\"choices\":[{\"message\":{\"content\":\"检索回答 [1]\"}}]}"
                            : configuredResponse.toString();
                } else if (path.endsWith("/fetch")) {
                    fetchRequest = request;
                    fetchAuthorization = authorization;
                    fetchRequests.add(request);
                    fetchPaths.add(path);
                    if (path.startsWith("/slow-many/")) {
                        concurrentFetches.countDown();
                        releaseFetch.await(5, TimeUnit.SECONDS);
                    }
                    if (path.equals("/denied-fetch/fetch")) {
                        status = 401;
                        response = "{}";
                    } else if (path.equals("/bad-fetch/fetch")) {
                        response = "{\"title\":\"missing content\"}";
                    } else if (path.equals("/empty-fetch/fetch")) {
                        response = "{\"title\":\"empty\",\"content\":\" \"}";
                    } else {
                        response = "{\"title\":\"抓取标题\",\"content\":\"# 抓取正文\\n\\n独立抓取的 Markdown 内容\"}";
                    }
                } else {
                    searchRequest = request;
                    searchAuthorization = authorization;
                    if (path.equals("/duplicates/search")) {
                        response = "{\"results\":[{\"url\":\"https://example.com/source\",\"summary\":\"摘要\"},"
                                + "{\"url\":\"https://example.com/source\",\"content\":\"后续重复结果的完整正文\"}]}";
                    } else if (path.equals("/many/search") || path.equals("/slow-many/search")) {
                        JSONArray results = new JSONArray();
                        for (int index = 1; index <= 4; index++) {
                            JSONObject page = new JSONObject().put("title", "来源" + index)
                                    .put("url", "https://example.com/source-" + index)
                                    .put("summary", "第" + index + "条搜索摘要");
                            results.put(page);
                            if (index == 1) {
                                results.put(page).put(new JSONObject().put("url", "javascript:alert(1)"));
                            }
                        }
                        response = new JSONObject().put("results", results).toString();
                    } else if (path.equals("/empty")) {
                        response = "{\"results\":[]}";
                    } else if (path.equals("/bad")) {
                        response = "{}";
                    } else if (path.equals("/denied")) {
                        status = 401;
                        response = "{}";
                    } else {
                        response = "{\"results\":[{\"title\":\"测试来源\",\"url\":\"https://example.com/source\","
                                + "\"summary\":\"网页长摘要\",\"snippet\":\"短摘要\",\"content\":\"完整网页正文\"}]}";
                    }
                }
                byte[] output = response.getBytes(StandardCharsets.UTF_8);
                String headers = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " + output.length + "\r\n\r\n";
                OutputStream stream = socket.getOutputStream();
                stream.write(headers.getBytes(StandardCharsets.US_ASCII));
                stream.write(output);
                stream.flush();
            } catch (java.net.SocketException ignored) {
                // Cancellation can close a socket while the fixture sends its response.
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        @Override
        public void close() throws Exception {
            releaseFetch.countDown();
            server.close();
            connections.shutdownNow();
            thread.join(3000);
            connections.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
