package dev.rayneo.switcher;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ModelCatalogRegressionTests {
    private final Context context;

    ModelCatalogRegressionTests(Instrumentation instrumentation) {
        context = instrumentation.getTargetContext();
    }

    int run() throws Exception {
        legacyModels();
        savedModelsAndSwitching();
        concurrentModels();
        modelListHttp();
        return 4;
    }

    private ConfigStore.Config config(String model, String key) {
        return new ConfigStore.Config(true, "https://example.com/v1", model, key);
    }

    private void legacyModels() throws Exception {
        Fixture fixture = new Fixture(context);
        ConfigStore store = new ConfigStore(fixture);
        store.save(config("original-model", "fake-secret"));
        ConfigStore.Provider migrated = store.readProviders().get(0);
        check(migrated.models.equals(Collections.singletonList("original-model")), "legacy model was not migrated");
        JSONArray profiles = new JSONArray(fixture.preferences("model_config").getString("provider_profiles", "[]"));
        JSONObject profile = profiles.getJSONObject(0);
        String encrypted = profile.getString("encrypted_key");
        profile.remove("models");
        fixture.preferences("model_config").edit().putString("provider_profiles", profiles.toString()).commit();
        ConfigStore.Provider reopened = new ConfigStore(fixture).readProviders().get(0);
        check(reopened.models.equals(Collections.singletonList("original-model")), "0.1.8 provider lost its model");
        check(reopened.toJson().getString("encrypted_key").equals(encrypted), "model migration changed encrypted credentials");
        store.activateModel(reopened.id, "original-model");
        check(store.read().model.equals("original-model"), "migrated model could not be activated");
    }

    private void savedModelsAndSwitching() throws Exception {
        Fixture fixture = new Fixture(context);
        ConfigStore store = new ConfigStore(fixture);
        String id = store.readProviders().get(0).id;
        ConfigStore.Provider first = store.saveProvider(id, "First", config("model-a", "first-secret"),
                Arrays.asList("model-a", " model-b ", "model-b", "", null));
        check(first.models.equals(Arrays.asList("deepseek-flash", "model-a", "model-b")), "saved models were replaced or duplicated");
        String encrypted = first.toJson().getString("encrypted_key");
        ConfigStore.Provider second = store.saveProvider(null, "Second", config("other-model", "second-secret"));
        store.activateModel(first.id, "model-b");
        check(store.read().model.equals("model-b") && store.read().apiKey.equals("first-secret"), "model switch mixed provider credentials");
        ConfigStore.Provider switched = store.readProviders().get(0);
        check(switched.toJson().getString("encrypted_key").equals(encrypted), "switching models re-encrypted provider key");
        store.activateProvider(second.id);
        check(store.read().model.equals("other-model"), "second provider model changed");
        store.activateProvider(first.id);
        check(store.read().model.equals("model-b"), "provider did not remember selected model");
        store.save(config("manual-model", "first-secret"));
        check(store.readProviders().get(0).models.containsAll(Arrays.asList("model-a", "model-b", "manual-model")),
                "legacy save discarded other models");
        Map<String, ?> before = fixture.preferences("model_config").getAll();
        try {
            store.activateModel(first.id, "missing-model");
            throw new AssertionError("missing model was activated");
        } catch (IllegalArgumentException expected) {
            check(before.equals(fixture.preferences("model_config").getAll()), "invalid model selection changed state");
        }
        store.save(new ConfigStore.Config(false, "https://example.com/v1", "manual-model", "first-secret"));
        store.activateModel(first.id, "model-a");
        check(!store.read().enabled, "switching models enabled the global toggle");
        check(!fixture.preferences("model_config").getAll().toString().contains("first-secret"), "provider key persisted as plaintext");
    }

    private void concurrentModels() throws Exception {
        Fixture fixture = new Fixture(context);
        ConfigStore store = new ConfigStore(fixture);
        ConfigStore.Provider provider = store.saveProvider(null, "Concurrent", config("initial", "fake-secret"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                String model = "concurrent-" + index;
                calls.add(() -> store.saveProvider(provider.id, "Concurrent", config(model, "fake-secret"),
                        Collections.singletonList(model)).model);
            }
            for (Future<String> result : workers.invokeAll(calls)) {
                result.get();
            }
            ConfigStore.Provider saved = null;
            for (ConfigStore.Provider candidate : store.readProviders()) {
                if (candidate.id.equals(provider.id)) {
                    saved = candidate;
                }
            }
            check(saved != null && saved.models.size() == 9 && saved.model.equals(store.read().model),
                    "concurrent additions lost models or selected state");
        } finally {
            workers.shutdownNow();
        }
    }

    private void modelListHttp() throws Exception {
        try (SearchRegressionTests.HttpFixture server = new SearchRegressionTests.HttpFixture()) {
            List<String> expected = Arrays.asList("catalog-alpha", "catalog-beta", "Reasoning-Model", "reasoning-model");
            for (String root : new String[]{"", "/v1/", "/v1/chat/completions/"}) {
                ConfigStore.Config config = new ConfigStore.Config(false, server.url(root), "", "fake-list-key");
                check(new ModelClient().listModels(config).equals(expected), "model IDs were lost or not deduplicated");
                check(server.modelListMethod.equals("GET") && server.modelListBodyLength == 0, "models endpoint did not use GET");
                check(server.modelListAuthorization.equals("Bearer fake-list-key"), "model listing used wrong authorization");
                check(server.modelListPath.equals(root.isEmpty() ? "/models" : "/v1/models"), "models route lost the API prefix");
            }
            check(new ModelClient().listModels(new ConfigStore.Config(false, server.url("/empty-models"), "", "")).isEmpty(),
                    "empty model list was rejected");
            check(server.modelListAuthorization.isEmpty(), "empty key added authorization");
            for (String root : new String[]{"/bad-models", "/denied-models"}) {
                try {
                    new ModelClient().listModels(new ConfigStore.Config(false, server.url(root), "", ""));
                    throw new AssertionError("invalid model list succeeded");
                } catch (IllegalStateException expectedError) {
                    check(expectedError.getMessage().contains(root.equals("/bad-models") ? "data" : "401"), "catalog failure lost its cause");
                }
            }
            int calls = server.modelListCalls;
            ModelClient cancelled = new ModelClient();
            cancelled.cancel();
            try {
                cancelled.listModels(new ConfigStore.Config(false, server.url("/v1"), "", ""));
                throw new AssertionError("cancelled listing succeeded");
            } catch (InterruptedException expectedError) {
                check(server.modelListCalls == calls, "cancelled listing reached the server");
            }
        }
    }

    private static final class Fixture extends ContextWrapper {
        private final Map<String, ProviderStoreInstrumentation.MemoryPreferences> files = new HashMap<>();

        Fixture(Context context) {
            super(context);
        }

        SharedPreferences preferences(String name) {
            return getSharedPreferences(name, 0);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return files.computeIfAbsent(name, key -> new ProviderStoreInstrumentation.MemoryPreferences());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
