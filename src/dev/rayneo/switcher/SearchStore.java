package dev.rayneo.switcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SearchStore {
    private final SharedPreferences preferences;

    SearchStore(Context context) {
        preferences = context.getSharedPreferences("search_config", Context.MODE_PRIVATE);
    }

    static final class Provider {
        final String id;
        final String name;
        private final JSONObject data;

        Provider(JSONObject data) throws Exception {
            this.data = data;
            id = data.getString("id");
            name = data.getString("name");
        }

        SearchConfig config() throws Exception {
            return config(ConfigStore.decrypt(data.getString("encrypted_key")));
        }

        SearchConfig config(String apiKey) throws Exception {
            String endpoint = data.getString("endpoint");
            return new SearchConfig(SearchConfig.CUSTOM, name, endpoint, apiKey, data.getInt("count"),
                    data.getBoolean("need_content"), data.getString("scope"),
                    data.optString("tools_base_url", null),
                    data.optInt("fetch_count", SearchConfig.defaultFetchCount(endpoint)));
        }
    }

    List<Provider> providers() throws Exception {
        synchronized (ConfigStore.CONFIG_LOCK) {
            JSONArray values = new JSONArray(preferences.getString("providers", "[]"));
            List<Provider> providers = new ArrayList<>();
            for (int index = 0; index < values.length(); index++) {
                providers.add(new Provider(values.getJSONObject(index)));
            }
            return providers;
        }
    }

    String activeId() {
        synchronized (ConfigStore.CONFIG_LOCK) {
            return preferences.getString("active_id", "");
        }
    }

    String label() {
        synchronized (ConfigStore.CONFIG_LOCK) {
            String mode = preferences.getString("search_mode", SearchConfig.OFF);
            return SearchConfig.CUSTOM.equals(mode) ? preferences.getString("search_name", "自定义搜索")
                    : SearchConfig.builtin(mode).name;
        }
    }

    String mode() {
        synchronized (ConfigStore.CONFIG_LOCK) {
            return preferences.getString("search_mode", SearchConfig.OFF);
        }
    }

    SearchConfig read() throws Exception {
        synchronized (ConfigStore.CONFIG_LOCK) {
            return readRuntime(preferences);
        }
    }

    void setBuiltin(String mode) throws Exception {
        SearchConfig config = SearchConfig.builtin(mode);
        config.validate();
        if (SearchConfig.CUSTOM.equals(mode)) {
            throw new IllegalArgumentException("请选择已保存的搜索供应商");
        }
        synchronized (ConfigStore.CONFIG_LOCK) {
            ConfigStore.commit(preferences.edit().putString("search_mode", mode));
        }
    }

    Provider save(String id, SearchConfig config) throws Exception {
        config.validate();
        if (!SearchConfig.CUSTOM.equals(config.mode)) {
            throw new IllegalArgumentException("请填写自定义搜索配置");
        }
        synchronized (ConfigStore.CONFIG_LOCK) {
            List<Provider> providers = providers();
            Provider previous = find(providers, id);
            if (id != null && previous == null) {
                throw new IllegalStateException("搜索供应商已被删除，请重新选择");
            }
            String encrypted = ConfigStore.encrypt(config.apiKey);
            Provider saved = new Provider(new JSONObject()
                    .put("id", id == null ? UUID.randomUUID().toString() : id)
                    .put("name", config.name).put("endpoint", config.endpoint)
                    .put("tools_base_url", config.toolsBaseUrl).put("fetch_count", config.fetchCount)
                    .put("encrypted_key", encrypted).put("count", config.count)
                    .put("need_content", config.needContent).put("scope", config.scope));
            if (previous == null) {
                providers.add(saved);
            } else {
                providers.set(providers.indexOf(previous), saved);
            }
            ConfigStore.commit(writeRuntime(preferences.edit(), config, encrypted)
                    .putString("providers", encode(providers)).putString("active_id", saved.id));
            return saved;
        }
    }

    void activate(String id) throws Exception {
        synchronized (ConfigStore.CONFIG_LOCK) {
            Provider provider = find(providers(), id);
            if (provider == null) {
                throw new IllegalArgumentException("未找到搜索供应商");
            }
            SearchConfig config = provider.config();
            config.validate();
            ConfigStore.commit(writeRuntime(preferences.edit(), config,
                    provider.data.getString("encrypted_key")).putString("active_id", id));
        }
    }

    void delete(String id) throws Exception {
        synchronized (ConfigStore.CONFIG_LOCK) {
            List<Provider> providers = providers();
            Provider provider = find(providers, id);
            if (provider == null) {
                throw new IllegalArgumentException("未找到搜索供应商");
            }
            providers.remove(provider);
            SharedPreferences.Editor editor = preferences.edit();
            if (id.equals(activeId())) {
                if (providers.isEmpty()) {
                    writeRuntime(editor, SearchConfig.builtin(SearchConfig.OFF), "").putString("active_id", "");
                } else {
                    Provider next = providers.get(0);
                    SearchConfig config = next.config();
                    config.validate();
                    writeRuntime(editor, config, next.data.getString("encrypted_key")).putString("active_id", next.id);
                }
                String mode = preferences.getString("search_mode", SearchConfig.OFF);
                if (!SearchConfig.CUSTOM.equals(mode)) {
                    editor.putString("search_mode", mode);
                }
            }
            ConfigStore.commit(editor.putString("providers", encode(providers)));
        }
    }

    private static Provider find(List<Provider> providers, String id) {
        for (Provider provider : providers) {
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        return null;
    }

    private static String encode(List<Provider> providers) {
        JSONArray values = new JSONArray();
        for (Provider provider : providers) {
            values.put(provider.data);
        }
        return values.toString();
    }

    static SearchConfig readRuntime(SharedPreferences preferences) throws Exception {
        String mode = preferences.getString("search_mode", SearchConfig.OFF);
        if (!SearchConfig.CUSTOM.equals(mode)) {
            SearchConfig config = SearchConfig.builtin(mode);
            config.validate();
            return config;
        }
        String endpoint = preferences.getString("search_endpoint", "");
        return new SearchConfig(mode, preferences.getString("search_name", ""), endpoint,
                ConfigStore.decrypt(preferences.getString("search_encrypted_key", "")),
                preferences.getInt("search_count", 5), preferences.getBoolean("search_need_content", false),
                preferences.getString("search_scope", ""), preferences.getString("search_tools_base_url", null),
                preferences.getInt("search_fetch_count", SearchConfig.defaultFetchCount(endpoint)));
    }

    static SharedPreferences.Editor writeRuntime(SharedPreferences.Editor editor,
            SearchConfig config, String encryptedKey) {
        return editor.putString("search_mode", config.mode).putString("search_name", config.name)
                .putString("search_endpoint", config.endpoint).putString("search_encrypted_key", encryptedKey)
                .putString("search_tools_base_url", config.toolsBaseUrl).putInt("search_fetch_count", config.fetchCount)
                .putInt("search_count", config.count).putBoolean("search_need_content", config.needContent)
                .putString("search_scope", config.scope);
    }
}
