package dev.rayneo.switcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ConfigStore {
    static final String TARGET_PACKAGE = "com.rayneo.venus.pub";
    static final String AUTHORITY = "dev.rayneo.switcher.config";
    static final String CONFIG_PERMISSION = "dev.rayneo.switcher.READ_CONFIG";
    static final String STATUS_REQUEST = "dev.rayneo.switcher.REQUEST_STATUS";
    private static final String KEY_ALIAS = "rayneo.switcher.api_key.v1";
    private static final String PROVIDERS = "provider_profiles";
    private static final String ACTIVE_PROVIDER = "active_provider_id";
    static final Object CONFIG_LOCK = new Object();
    private final SharedPreferences preferences;

    ConfigStore(Context context) {
        this(context, "model_config");
    }

    ConfigStore(Context context, String name) {
        preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    static void connectTarget(Context context) {
        synchronized (CONFIG_LOCK) {
            Bundle config;
            try {
                config = currentBundle(context);
            } catch (Exception error) {
                throw new IllegalStateException("读取模型配置失败，请重新保存", error);
            }
            context.grantUriPermission(TARGET_PACKAGE, android.net.Uri.parse("content://" + AUTHORITY),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            context.sendBroadcast(new android.content.Intent(STATUS_REQUEST).setPackage(TARGET_PACKAGE)
                    .putExtra("config", config));
        }
    }

    static Bundle currentBundle(Context context) throws Exception {
        synchronized (CONFIG_LOCK) {
            Config config = new ConfigStore(context).read();
            Bundle value = config.toBundle();
            SearchConfig search = config.enabled ? new SearchStore(context).read() : SearchConfig.builtin(SearchConfig.OFF);
            value.putBundle("search", search.toBundle());
            return value;
        }
    }

    boolean hasConfig() {
        return preferences.contains("enabled");
    }

    static final class Config {
        final boolean enabled;
        final String baseUrl;
        final String model;
        final String apiKey;
        final SearchConfig search;

        Config(boolean enabled, String baseUrl, String model, String apiKey) {
            this(enabled, baseUrl, model, apiKey, SearchConfig.builtin(SearchConfig.OFF));
        }

        Config(boolean enabled, String baseUrl, String model, String apiKey, SearchConfig search) {
            this.enabled = enabled;
            this.baseUrl = baseUrl.trim();
            this.model = model.trim();
            this.apiKey = apiKey.trim();
            this.search = search;
        }

        void validate() throws Exception {
            validateEndpoint();
            if (model.isEmpty()) {
                throw new IllegalArgumentException("请填写模型名称");
            }
        }

        void validateEndpoint() throws Exception {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("请填写完整的 API 服务地址");
            }
        }

        private String apiRoot() {
            String normalized = baseUrl.replaceAll("/+$", "");
            return normalized.endsWith("/chat/completions")
                    ? normalized.substring(0, normalized.length() - "/chat/completions".length()) : normalized;
        }

        String completionUrl() {
            return apiRoot() + "/chat/completions";
        }

        String modelsUrl() {
            return apiRoot() + "/models";
        }

        Bundle toBundle() {
            Bundle result = new Bundle();
            result.putBoolean("enabled", enabled);
            result.putString("base_url", baseUrl);
            result.putString("model", model);
            result.putString("api_key", apiKey);
            result.putBundle("search", search.toBundle());
            return result;
        }

        static Config fromBundle(Bundle bundle) {
            return new Config(bundle.getBoolean("enabled", false),
                    bundle.getString("base_url", "https://api.deepseek.com"),
                    bundle.getString("model", "deepseek-flash"),
                    bundle.getString("api_key", ""), SearchConfig.fromBundle(bundle.getBundle("search")));
        }
    }

    Config read() throws Exception {
        synchronized (CONFIG_LOCK) {
            return new Config(preferences.getBoolean("enabled", false),
                    preferences.getString("base_url", "https://api.deepseek.com"),
                    preferences.getString("model", "deepseek-flash"),
                    decrypt(preferences.getString("encrypted_key", "")), SearchStore.readRuntime(preferences));
        }
    }

    void saveRuntime(Config config) throws Exception {
        synchronized (CONFIG_LOCK) {
            config.validate();
            config.search.validate();
            SharedPreferences.Editor editor = writeConfig(preferences.edit(), config, encrypt(config.apiKey));
            commit(SearchStore.writeRuntime(editor, config.search, encrypt(config.search.apiKey)));
        }
    }

    void save(Config config) throws Exception {
        synchronized (CONFIG_LOCK) {
            // Runtime copies keep the single-config format. Legacy callers in the
            // settings app also update the selected provider after migration.
            if (preferences.contains(PROVIDERS)) {
                Provider active = findProvider(readProviders(), activeProviderId());
                saveProvider(active == null ? null : active.id,
                        active == null ? "当前供应商" : active.name, config);
                return;
            }
            config.validate();
            commit(writeConfig(preferences.edit(), config, encrypt(config.apiKey)));
        }
    }

    static final class Provider {
        final String id;
        final String name;
        final String baseUrl;
        final String model;
        final List<String> models;
        private final String encryptedKey;

        Provider(String id, String name, String baseUrl, String model, String encryptedKey) {
            this(id, name, baseUrl, model, encryptedKey, Collections.singletonList(model));
        }

        Provider(String id, String name, String baseUrl, String model, String encryptedKey, List<String> models) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.model = model.trim();
            this.encryptedKey = encryptedKey;
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String value : models) {
                if (value != null && !value.trim().isEmpty()) {
                    unique.add(value.trim());
                }
            }
            if (!this.model.isEmpty()) {
                unique.add(this.model);
            }
            this.models = Collections.unmodifiableList(new ArrayList<>(unique));
        }

        Config config(boolean enabled) throws Exception {
            return new Config(enabled, baseUrl, model, decrypt(encryptedKey));
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("name", name)
                    .put("base_url", baseUrl).put("model", model)
                    .put("models", new JSONArray(models))
                    .put("encrypted_key", encryptedKey);
        }
    }

    List<Provider> readProviders() throws Exception {
        synchronized (CONFIG_LOCK) {
            List<Provider> providers = new ArrayList<>();
            if (!preferences.contains(PROVIDERS)) {
                Provider migrated = new Provider(UUID.randomUUID().toString(),
                        hasConfig() ? "当前供应商" : "DeepSeek",
                        preferences.getString("base_url", "https://api.deepseek.com"),
                        preferences.getString("model", "deepseek-flash"),
                        preferences.getString("encrypted_key", ""));
                providers.add(migrated);
                commit(preferences.edit().putString(PROVIDERS, encodeProviders(providers))
                        .putString(ACTIVE_PROVIDER, migrated.id));
                return providers;
            }
            JSONArray saved = new JSONArray(preferences.getString(PROVIDERS, "[]"));
            for (int index = 0; index < saved.length(); index++) {
                JSONObject entry = saved.getJSONObject(index);
                List<String> models = new ArrayList<>();
                JSONArray savedModels = entry.optJSONArray("models");
                if (savedModels != null) {
                    for (int modelIndex = 0; modelIndex < savedModels.length(); modelIndex++) {
                        Object value = savedModels.opt(modelIndex);
                        if (value instanceof String) {
                            models.add((String) value);
                        }
                    }
                }
                providers.add(new Provider(entry.getString("id"), entry.getString("name"),
                        entry.getString("base_url"), entry.getString("model"),
                        entry.getString("encrypted_key"), models));
            }
            return providers;
        }
    }

    String activeProviderId() {
        synchronized (CONFIG_LOCK) {
            return preferences.getString(ACTIVE_PROVIDER, "");
        }
    }

    Provider saveProvider(String id, String name, Config config) throws Exception {
        return saveProvider(id, name, config, null);
    }

    Provider saveProvider(String id, String name, Config config, List<String> models) throws Exception {
        synchronized (CONFIG_LOCK) {
            String trimmedName = name.trim();
            if (trimmedName.isEmpty()) {
                throw new IllegalArgumentException("请填写供应商名称");
            }
            config.validate();
            List<Provider> providers = readProviders();
            Provider previous = findProvider(providers, id);
            if (id != null && previous == null) {
                throw new IllegalStateException("供应商已被删除，请重新选择");
            }
            List<String> savedModels = new ArrayList<>();
            if (previous != null) {
                savedModels.addAll(previous.models);
            }
            if (models != null) {
                savedModels.addAll(models);
            }
            Provider saved = new Provider(id == null ? UUID.randomUUID().toString() : id,
                    trimmedName, config.baseUrl, config.model, encrypt(config.apiKey), savedModels);
            if (previous == null) {
                providers.add(saved);
            } else {
                providers.set(providers.indexOf(previous), saved);
            }
            commit(writeConfig(preferences.edit(), config, saved.encryptedKey)
                    .putString(PROVIDERS, encodeProviders(providers))
                    .putString(ACTIVE_PROVIDER, saved.id));
            return saved;
        }
    }

    Config activateProvider(String id) throws Exception {
        synchronized (CONFIG_LOCK) {
            Provider provider = findProvider(readProviders(), id);
            if (provider == null) {
                throw new IllegalArgumentException("未找到供应商，请重新选择");
            }
            Config config = provider.config(preferences.getBoolean("enabled", false));
            config.validate();
            commit(writeConfig(preferences.edit(), config, provider.encryptedKey)
                    .putString(ACTIVE_PROVIDER, id));
            return config;
        }
    }

    Config activateModel(String providerId, String model) throws Exception {
        synchronized (CONFIG_LOCK) {
            List<Provider> providers = readProviders();
            Provider provider = findProvider(providers, providerId);
            if (provider == null || !provider.models.contains(model)) {
                throw new IllegalArgumentException("未找到已保存的模型，请重新选择");
            }
            Provider selected = new Provider(provider.id, provider.name, provider.baseUrl, model,
                    provider.encryptedKey, provider.models);
            Config config = selected.config(preferences.getBoolean("enabled", false));
            config.validate();
            providers.set(providers.indexOf(provider), selected);
            commit(writeConfig(preferences.edit(), config, selected.encryptedKey)
                    .putString(PROVIDERS, encodeProviders(providers)).putString(ACTIVE_PROVIDER, providerId));
            return config;
        }
    }

    void deleteProvider(String id) throws Exception {
        synchronized (CONFIG_LOCK) {
            List<Provider> providers = readProviders();
            Provider provider = findProvider(providers, id);
            if (provider == null) {
                throw new IllegalArgumentException("未找到供应商，请重新选择");
            }
            providers.remove(provider);
            SharedPreferences.Editor editor = preferences.edit();
            if (id.equals(activeProviderId())) {
                if (providers.isEmpty()) {
                    writeConfig(editor, new Config(false, "https://api.deepseek.com", "deepseek-flash", ""), "")
                            .putString(ACTIVE_PROVIDER, "");
                } else {
                    Provider next = providers.get(0);
                    Config config = next.config(preferences.getBoolean("enabled", false));
                    config.validate();
                    writeConfig(editor, config, next.encryptedKey).putString(ACTIVE_PROVIDER, next.id);
                }
            }
            commit(editor.putString(PROVIDERS, encodeProviders(providers)));
        }
    }

    private static Provider findProvider(List<Provider> providers, String id) {
        for (Provider provider : providers) {
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        return null;
    }

    private static String encodeProviders(List<Provider> providers) throws Exception {
        JSONArray result = new JSONArray();
        for (Provider provider : providers) {
            result.put(provider.toJson());
        }
        return result.toString();
    }

    private static SharedPreferences.Editor writeConfig(SharedPreferences.Editor editor,
            Config config, String encryptedKey) {
        return editor.putBoolean("enabled", config.enabled).putString("base_url", config.baseUrl)
                .putString("model", config.model).putString("encrypted_key", encryptedKey);
    }

    static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("配置保存失败");
        }
    }

    void status(String message, String model, int pid, String searchMode, String searchName) {
        synchronized (CONFIG_LOCK) {
            commit(preferences.edit().putString("status_message", message)
                    .putString("status_model", model)
                    .putString("status_search_mode", searchMode).putString("status_search_name", searchName)
                    .putLong("status_time", System.currentTimeMillis()).putInt("status_pid", pid));
        }
    }

    Bundle status() {
        synchronized (CONFIG_LOCK) {
            Bundle result = new Bundle();
            result.putString("message", preferences.getString("status_message", "等待雷鸟 App 加载模块"));
            result.putString("model", preferences.getString("status_model", ""));
            result.putLong("time", preferences.getLong("status_time", 0));
            result.putInt("pid", preferences.getInt("status_pid", 0));
            result.putString("search_mode", preferences.getString("status_search_mode", SearchConfig.OFF));
            result.putString("search_name", preferences.getString("status_search_name", "关闭联网搜索"));
            result.putBoolean("enabled", preferences.getBoolean("enabled", false));
            result.putBoolean("key_configured", !preferences.getString("encrypted_key", "").isEmpty());
            return result;
        }
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static String encrypt(String value) throws Exception {
        if (value.isEmpty()) {
            return "";
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    static String decrypt(String value) throws Exception {
        if (value.isEmpty()) {
            return "";
        }
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("密钥存储格式无效，请重新保存");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
