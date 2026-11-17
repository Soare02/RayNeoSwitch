package dev.rayneo.switcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs against the real Android Keystore with isolated, in-memory preferences. */
public final class ProviderStoreInstrumentation extends Instrumentation {
    private int passed;
    private boolean testUi;
    private boolean diagnoseOutput;
    private boolean diagnoseModels;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        testUi = arguments != null && "true".equals(arguments.getString("ui"));
        diagnoseOutput = arguments != null && "true".equals(arguments.getString("diagnose_output"));
        diagnoseModels = arguments != null && "true".equals(arguments.getString("diagnose_models"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            if (diagnoseModels) {
                result.putString("stream", ModelCatalogDiagnostic.run(this).toString(2) + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (diagnoseOutput) {
                result.putString("stream", ModelOutputDiagnostic.run(this).toString(2) + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            migration();
            switchingAndEditing();
            invalidChanges();
            unreadableKey();
            deletion();
            legacyCallers();
            concurrentSaves();
            passed += new SearchRegressionTests(this).run();
            passed += new ModelResponseRegressionTests().run();
            passed += new ModelCatalogRegressionTests(this).run();
            if (testUi) {
                new ProviderUiTest(this).run();
                passed++;
                new SearchUiTest(this).run();
                passed++;
                new ModelCatalogUiTest(this).run();
                passed++;
            }
            result.putString("stream", "Passed " + passed + " model/search regression tests\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAILED after " + passed + " tests\n" + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static ConfigStore.Config config(boolean enabled, String suffix) {
        return new ConfigStore.Config(enabled, "https://" + suffix + ".example/v1",
                "model-" + suffix, "test-secret-" + suffix);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void sameConfig(ConfigStore.Config expected, ConfigStore.Config actual) {
        check(expected.enabled == actual.enabled, "enabled mismatch");
        check(expected.baseUrl.equals(actual.baseUrl), "URL mismatch");
        check(expected.model.equals(actual.model), "model mismatch");
        check(expected.apiKey.equals(actual.apiKey), "key mismatch");
    }

    private void migration() throws Exception {
        Fixture fixture = new Fixture();
        ConfigStore store = new ConfigStore(fixture);
        ConfigStore.Config original = config(true, "original");
        store.save(original);
        String encrypted = fixture.preferences.getString("encrypted_key", "");
        check(!fixture.preferences.contains("provider_profiles"), "legacy format changed early");
        List<ConfigStore.Provider> providers = store.readProviders();
        check(providers.size() == 1, "migration must create one provider");
        check(providers.get(0).id.equals(store.activeProviderId()), "migration selection mismatch");
        sameConfig(original, providers.get(0).config(true));
        sameConfig(original, store.read());
        check(encrypted.equals(providers.get(0).toJson().getString("encrypted_key")),
                "migration must retain the original encrypted key");
        check(store.readProviders().get(0).id.equals(providers.get(0).id), "migration repeated");
        check(!fixture.preferences.getAll().toString().contains(original.apiKey), "plaintext key persisted");
        passed++;
    }

    private void switchingAndEditing() throws Exception {
        Fixture fixture = new Fixture();
        ConfigStore store = new ConfigStore(fixture);
        ConfigStore.Provider first = store.readProviders().get(0);
        store.saveProvider(first.id, "First", config(true, "first"));
        ConfigStore.Provider second = store.saveProvider(null, "Second", config(true, "second"));
        check(store.readProviders().size() == 2, "new provider replaced the first");
        sameConfig(config(true, "second"), store.read());
        sameConfig(config(true, "first"), store.activateProvider(first.id));
        sameConfig(config(true, "second"), store.activateProvider(second.id));
        store.saveProvider(second.id, "Renamed", config(false, "updated"));
        check(store.readProviders().size() == 2, "edit duplicated a provider");
        ConfigStore reopened = new ConfigStore(fixture);
        check(second.id.equals(reopened.activeProviderId()), "active provider lost on reopening");
        check("Renamed".equals(reopened.readProviders().get(1).name), "rename lost");
        sameConfig(config(false, "updated"), reopened.read());
        sameConfig(config(false, "first"), reopened.activateProvider(first.id));
        passed++;
    }

    private void invalidChanges() throws Exception {
        Fixture fixture = new Fixture();
        ConfigStore store = new ConfigStore(fixture);
        String id = store.readProviders().get(0).id;
        store.saveProvider(id, "Valid", config(true, "valid"));
        Map<String, ?> before = fixture.preferences.getAll();
        expectFailure(() -> store.saveProvider(null, " ", config(true, "invalid")));
        expectFailure(() -> store.saveProvider(id, "Broken", new ConfigStore.Config(true, "bad", "m", "k")));
        expectFailure(() -> store.saveProvider(id, "Broken", new ConfigStore.Config(true, "https://a.example", "", "k")));
        expectFailure(() -> store.activateProvider("missing"));
        expectFailure(() -> store.saveProvider("missing", "Missing", config(true, "missing")));
        check(before.equals(fixture.preferences.getAll()), "invalid operation changed saved configuration");
        passed++;
    }

    private void unreadableKey() throws Exception {
        Fixture fixture = new Fixture();
        ConfigStore store = new ConfigStore(fixture);
        String firstId = store.readProviders().get(0).id;
        store.saveProvider(firstId, "First", config(true, "first"));
        ConfigStore.Provider second = store.saveProvider(null, "Second", config(true, "second"));
        store.activateProvider(firstId);
        JSONArray saved = new JSONArray(fixture.preferences.getString("provider_profiles", "[]"));
        saved.getJSONObject(1).put("encrypted_key", "unreadable");
        fixture.preferences.edit().putString("provider_profiles", saved.toString()).commit();
        Map<String, ?> before = fixture.preferences.getAll();
        check(store.readProviders().size() == 2, "one damaged key blocked the provider list");
        expectFailure(() -> store.activateProvider(second.id));
        check(before.equals(fixture.preferences.getAll()), "failed decryption changed active configuration");
        sameConfig(config(true, "first"), store.read());
        passed++;
    }

    private void deletion() throws Exception {
        Fixture fixture = new Fixture();
        ConfigStore store = new ConfigStore(fixture);
        String firstId = store.readProviders().get(0).id;
        store.saveProvider(firstId, "First", config(true, "first"));
        ConfigStore.Provider second = store.saveProvider(null, "Second", config(true, "second"));
        ConfigStore.Provider third = store.saveProvider(null, "Third", config(true, "third"));
        store.deleteProvider(second.id);
        sameConfig(config(true, "third"), store.read());
        store.deleteProvider(third.id);
        check(firstId.equals(store.activeProviderId()), "active deletion did not select the remaining provider");
        sameConfig(config(true, "first"), store.read());
        store.deleteProvider(firstId);
        check(store.readProviders().isEmpty(), "last provider was recreated");
        check(new ConfigStore(fixture).readProviders().isEmpty(), "empty list lost on reopening");
        check(!store.read().enabled && store.read().apiKey.isEmpty(), "last deletion retained enabled credentials");
        check(store.activeProviderId().isEmpty(), "last deletion retained active selection");
        expectFailure(() -> store.deleteProvider(firstId));
        store.saveProvider(null, "New", config(true, "new"));
        check(store.readProviders().size() == 1, "adding to empty list created a default provider");
        passed++;
    }

    private void legacyCallers() throws Exception {
        Fixture fixture = new Fixture();
        ConfigStore store = new ConfigStore(fixture);
        String id = store.readProviders().get(0).id;
        store.save(config(true, "legacy"));
        check(id.equals(store.activeProviderId()), "legacy save changed provider identity");
        sameConfig(config(true, "legacy"), store.readProviders().get(0).config(true));
        store.deleteProvider(id);
        store.save(config(false, "restored"));
        check(store.readProviders().size() == 1, "legacy save failed on empty provider list");
        Fixture runtimeFixture = new Fixture();
        ConfigStore runtime = new ConfigStore(runtimeFixture, "rayneo_switch_runtime_config");
        runtime.save(config(true, "runtime"));
        sameConfig(config(true, "runtime"), runtime.read());
        check(!runtimeFixture.preferences.contains("provider_profiles"), "runtime created a provider list");
        passed++;
    }

    private void concurrentSaves() throws Exception {
        Fixture fixture = new Fixture();
        ConfigStore store = new ConfigStore(fixture);
        store.readProviders();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                String suffix = "parallel" + index;
                tasks.add(() -> new ConfigStore(fixture).saveProvider(null, suffix, config(true, suffix)).id);
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> future : executor.invokeAll(tasks)) {
                ids.add(future.get());
            }
            check(ids.size() == 8 && store.readProviders().size() == 9, "concurrent saves lost a provider");
            for (ConfigStore.Provider provider : store.readProviders()) {
                if (provider.id.equals(store.activeProviderId())) {
                    sameConfig(provider.config(true), store.read());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        passed++;
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void expectFailure(ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("Expected operation to fail");
    }

    private final class Fixture extends ContextWrapper {
        final MemoryPreferences preferences = new MemoryPreferences();

        Fixture() {
            super(getTargetContext());
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String fallback) {
            return (String) values.getOrDefault(key, fallback);
        }

        @Override
        public int getInt(String key, int fallback) {
            return (Integer) values.getOrDefault(key, fallback);
        }

        @Override
        public long getLong(String key, long fallback) {
            return (Long) values.getOrDefault(key, fallback);
        }

        @Override
        public float getFloat(String key, float fallback) {
            return (Float) values.getOrDefault(key, fallback);
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            return (Boolean) values.getOrDefault(key, fallback);
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> fallback) {
            return (Set<String>) values.getOrDefault(key, fallback);
        }

        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                private boolean clear;

                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, Set<String> value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putInt(String key, int value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(String key, float value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    pending.put(key, null);
                    return this;
                }

                @Override
                public Editor clear() {
                    clear = true;
                    return this;
                }

                @Override
                public void apply() {
                    commit();
                }

                @Override
                public boolean commit() {
                    if (clear) {
                        values.clear();
                    }
                    for (Map.Entry<String, Object> entry : pending.entrySet()) {
                        if (entry.getValue() == null) {
                            values.remove(entry.getKey());
                        } else {
                            values.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return true;
                }
            };
        }
    }
}
