package dev.rayneo.switcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(23, 32, 51);
    private static final int MUTED = Color.rgb(87, 97, 115);
    private static final int BLUE = Color.rgb(33, 94, 222);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private ConfigStore store;
    private List<ConfigStore.Provider> providers = new ArrayList<>();
    private String editingProviderId;
    private String initialName;
    private ConfigStore.Config initialConfig;
    private List<String> initialModels = new ArrayList<>();
    private List<String> draftModels = new ArrayList<>();
    private EditText providerName;
    private Button providerPicker;
    private Button addProvider;
    private Button deleteProvider;
    private TextView activeProvider;
    private EditText baseUrl;
    private EditText model;
    private EditText apiKey;
    private Button modelPicker;
    private Button fetchModels;
    private AlertDialog modelDialog;
    private Switch enabled;
    private TextView status;
    private TextView statusTime;
    private Button save;
    private Button test;
    private Button searchSettings;
    private boolean updating;
    private boolean testing;
    private boolean fetching;
    private volatile ModelClient testClient;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ConfigStore(this);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(243, 245, 248));
        root.setPadding(dp(22), dp(20), dp(22), dp(24));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(22), top + dp(20), dp(22), bottom + dp(24));
            return insets;
        });
        setContentView(scroll);

        TextView title = text("RayNeo Switch", 29, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("眼镜自定义模型", 15, MUTED);
        root.addView(subtitle, block(8, 24));

        searchSettings = button("联网搜索", false);
        root.addView(searchSettings, block(0, 16));
        searchSettings.setOnClickListener(view -> startActivity(new Intent(this, SearchSettingsActivity.class)));

        LinearLayout card = card();
        root.addView(card, block(0, 16));
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView toggleTitle = text("使用自定义模型", 17, INK);
        toggleTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toggleRow.addView(toggleTitle, new LinearLayout.LayoutParams(0, -2, 1));
        enabled = new Switch(this);
        enabled.setContentDescription("使用自定义模型");
        toggleRow.addView(enabled);
        card.addView(toggleRow);
        card.addView(text("保存或切换后，下一次眼镜提问生效", 13, MUTED), block(4, 20));

        card.addView(text("已保存的供应商", 13, MUTED));
        providerPicker = button("选择供应商", false);
        providerPicker.setSingleLine(true);
        providerPicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(providerPicker, new LinearLayout.LayoutParams(-1, dp(50)));
        providerPicker.setOnClickListener(view -> chooseProvider());
        activeProvider = text("", 13, MUTED);
        card.addView(activeProvider, block(4, 8));
        LinearLayout providerButtons = new LinearLayout(this);
        addProvider = button("新增供应商", false);
        deleteProvider = button("删除供应商", false);
        LinearLayout.LayoutParams addParameters = new LinearLayout.LayoutParams(0, dp(48), 1);
        addParameters.setMarginEnd(dp(8));
        providerButtons.addView(addProvider, addParameters);
        providerButtons.addView(deleteProvider, new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(providerButtons, block(0, 18));
        addProvider.setOnClickListener(view -> confirmChanges(this::newProvider));
        deleteProvider.setOnClickListener(view -> confirmDelete());

        providerName = input(card, "供应商名称", InputType.TYPE_CLASS_TEXT);
        providerName.setHint("例如 我的中转站");

        baseUrl = input(card, "API 地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        model = input(card, "模型名称", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        apiKey = input(card, "API Key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);

        modelPicker = button("选择模型", false);
        modelPicker.setSingleLine(true);
        modelPicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(modelPicker, new LinearLayout.LayoutParams(-1, dp(50)));
        modelPicker.setOnClickListener(view -> chooseModel());
        fetchModels = button("获取模型列表", false);
        card.addView(fetchModels, block(8, 8));
        fetchModels.setOnClickListener(view -> fetchModels());
        card.addView(text("填写模型 ID 后保存可加入此供应商的模型列表。获取列表后可搜索并多选添加，再点击保存并使用。",
                12, MUTED), block(0, 14));

        LinearLayout buttons = new LinearLayout(this);
        save = button("保存并使用", true);
        test = button("测试连接", false);
        LinearLayout.LayoutParams firstButton = new LinearLayout.LayoutParams(0, dp(50), 1);
        firstButton.setMarginEnd(dp(8));
        buttons.addView(save, firstButton);
        buttons.addView(test, new LinearLayout.LayoutParams(0, dp(50), 1));
        card.addView(buttons, block(4, 0));
        save.setOnClickListener(view -> saveConfig(null));
        test.setOnClickListener(view -> testConnection());

        LinearLayout statusCard = card();
        root.addView(statusCard, block(0, 16));
        TextView statusTitle = text("模块状态", 15, INK);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(statusTitle);
        status = text("等待雷鸟 App 加载模块", 15, INK);
        statusCard.addView(status, block(10, 4));
        statusTime = text("", 12, MUTED);
        statusCard.addView(statusTime);
        statusCard.addView(text("首次使用需在 Vector 中启用此模块，作用域勾选雷鸟 AI，然后重新打开雷鸟 App。",
                13, MUTED), block(14, 0));

        Button open = button("打开雷鸟 AI", false);
        root.addView(open, new LinearLayout.LayoutParams(-1, dp(50)));
        open.setOnClickListener(view -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(ConfigStore.TARGET_PACKAGE);
            if (intent == null) {
                toast("未找到雷鸟 AI App");
            } else {
                try {
                    ConfigStore.connectTarget(this);
                    startActivity(intent);
                } catch (RuntimeException error) {
                    toast("连接雷鸟 App 失败，请稍后重试");
                }
            }
        });
        root.addView(text("语音识别使用雷鸟服务。原厂 AI 请求仍会运行，问答结果由自定义模型接管。",
                12, MUTED), block(18, 0));
        try {
            loadProviders();
        } catch (Exception error) {
            toast("供应商读取失败，请重新打开 App");
        }
        updateControls();
    }

    private ConfigStore.Config form() {
        return new ConfigStore.Config(enabled.isChecked(), baseUrl.getText().toString(),
                model.getText().toString(), apiKey.getText().toString());
    }

    private void loadProviders() throws Exception {
        providers = store.readProviders();
        String activeId = store.activeProviderId();
        ConfigStore.Provider selected = null;
        for (ConfigStore.Provider provider : providers) {
            if (provider.id.equals(activeId)) {
                selected = provider;
                break;
            }
        }
        activeProvider.setText(selected == null ? "尚未保存供应商" : "当前选择 · " + selected.name);
        if (selected == null) {
            newProvider();
            return;
        }
        ConfigStore.Config config;
        try {
            config = selected.config(store.status().getBoolean("enabled"));
        } catch (Exception error) {
            config = new ConfigStore.Config(store.status().getBoolean("enabled"),
                    selected.baseUrl, selected.model, "");
            toast("此供应商的密钥读取失败，请重新填写并保存");
        }
        showProvider(selected.id, selected.name, config, selected.models);
    }

    private void showProvider(String id, String name, ConfigStore.Config config, List<String> models) {
        editingProviderId = id;
        initialName = name;
        initialConfig = config;
        initialModels = new ArrayList<>(models);
        draftModels = new ArrayList<>(models);
        providerName.setText(name);
        enabled.setChecked(config.enabled);
        baseUrl.setText(config.baseUrl);
        model.setText(config.model);
        apiKey.setText(config.apiKey);
        updateModelPicker();
        providerPicker.setText(id == null ? "选择供应商（" + providers.size() + "）" : name + " ▾");
        updateControls();
    }

    private void newProvider() {
        showProvider(null, "", new ConfigStore.Config(store.status().getBoolean("enabled"),
                "https://", "", ""), new ArrayList<>());
    }

    private boolean hasChanges() {
        if (initialConfig == null) {
            return false;
        }
        ConfigStore.Config current = form();
        return !providerName.getText().toString().trim().equals(initialName)
                || current.enabled != initialConfig.enabled
                || !current.baseUrl.equals(initialConfig.baseUrl)
                || !current.model.equals(initialConfig.model)
                || !current.apiKey.equals(initialConfig.apiKey)
                || !draftModels.equals(initialModels);
    }

    private void updateModelPicker() {
        modelPicker.setText("选择模型（" + draftModels.size() + "） ▾");
    }

    private void chooseModel() {
        String providerId = editingProviderId;
        modelDialog = ModelPicker.single(this, new ArrayList<>(draftModels), form().model, selected -> {
            if (selected.equals(form().model)) {
                return;
            }
            if (providerId != null && !hasChanges() && initialModels.contains(selected)) {
                updateConfig(() -> store.activateModel(providerId, selected),
                        "已切换模型，下一次眼镜提问使用「" + selected + "」", null);
            } else {
                model.setText(selected);
                toast("模型已选择，请点击保存并使用");
            }
        });
    }

    private void fetchModels() {
        ConfigStore.Config config = form();
        fetching = true;
        updateControls();
        fetchModels.setText("获取中…");
        ModelClient client = new ModelClient();
        testClient = client;
        worker.submit(() -> {
            try {
                List<String> models = client.listModels(config);
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    if (models.isEmpty()) {
                        toast("接口未返回可选模型，可以手动填写模型 ID");
                        return;
                    }
                    modelDialog = ModelPicker.multiple(this, models, draftModels, selected -> {
                        for (String id : selected) {
                            if (!draftModels.contains(id)) {
                                draftModels.add(id);
                            }
                        }
                        if (model.getText().toString().trim().isEmpty() && !selected.isEmpty()) {
                            model.setText(selected.get(0));
                        }
                        updateModelPicker();
                        updateControls();
                        toast("已添加所选模型，请点击保存并使用");
                    });
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        toast("获取模型列表失败 · " + error.getMessage());
                    }
                });
            } finally {
                testClient = null;
                runOnUiThread(() -> {
                    fetching = false;
                    if (!isDestroyed()) {
                        fetchModels.setText("获取模型列表");
                        updateControls();
                    }
                });
            }
        });
    }

    private void confirmChanges(Runnable next) {
        if (!hasChanges()) {
            next.run();
            return;
        }
        new AlertDialog.Builder(this).setTitle("保存当前修改？")
                .setMessage("当前供应商有尚未保存的修改。")
                .setPositiveButton("保存并继续", (dialog, which) -> saveConfig(next))
                .setNeutralButton("放弃更改", (dialog, which) -> next.run())
                .setNegativeButton("取消", null).show();
    }

    private void chooseProvider() {
        String[] names = new String[providers.size()];
        int checked = -1;
        for (int index = 0; index < providers.size(); index++) {
            ConfigStore.Provider provider = providers.get(index);
            names[index] = provider.name + " · " + provider.model;
            if (provider.id.equals(editingProviderId)) {
                checked = index;
            }
        }
        new AlertDialog.Builder(this).setTitle("切换供应商")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    ConfigStore.Provider selected = providers.get(which);
                    dialog.dismiss();
                    if (!selected.id.equals(editingProviderId)) {
                        confirmChanges(() -> updateConfig(() -> store.activateProvider(selected.id),
                                "已切换到「" + selected.name + "」，下一次眼镜提问生效", null));
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void confirmDelete() {
        String id = editingProviderId;
        ConfigStore.Provider selected = null;
        ConfigStore.Provider next = null;
        for (ConfigStore.Provider provider : providers) {
            if (provider.id.equals(id)) {
                selected = provider;
            } else if (next == null) {
                next = provider;
            }
        }
        if (selected == null) {
            return;
        }
        String message = "删除「" + selected.name + "」及其保存的配置？";
        if (id.equals(store.activeProviderId())) {
            message += next == null ? "\n删除后会关闭自定义模型。"
                    : "\n删除后将使用「" + next.name + "」。";
        }
        new AlertDialog.Builder(this).setTitle("删除供应商").setMessage(message)
                .setPositiveButton("删除", (dialog, which) -> updateConfig(() -> store.deleteProvider(id),
                        "供应商已删除", null))
                .setNegativeButton("取消", null).show();
    }

    private void saveConfig(Runnable next) {
        ConfigStore.Config config = form();
        String id = editingProviderId;
        String name = providerName.getText().toString().trim();
        List<String> models = new ArrayList<>(draftModels);
        updateConfig(() -> store.saveProvider(id, name, config, models),
                "已保存并使用「" + name + "」，下一次眼镜提问生效", next);
    }

    private interface ConfigAction {
        void run() throws Exception;
    }

    private void updateConfig(ConfigAction action, String success, Runnable next) {
        updating = true;
        updateControls();
        worker.submit(() -> {
            try {
                action.run();
                String message;
                try {
                    ConfigStore.connectTarget(this);
                    message = success;
                } catch (RuntimeException error) {
                    message = "配置已保存，同步失败，请重新打开雷鸟 AI 后重试";
                }
                String result = message;
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    updating = false;
                    try {
                        loadProviders();
                        toast(result);
                        if (next != null) {
                            next.run();
                        }
                    } catch (Exception error) {
                        toast("配置已保存，界面刷新失败，请重新打开 App");
                    }
                    updateControls();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        updating = false;
                        updateControls();
                        toast(error.getMessage());
                    }
                });
            }
        });
    }

    private void updateControls() {
        boolean editable = !updating && !testing && !fetching && initialConfig != null;
        providerPicker.setEnabled(editable && !providers.isEmpty());
        addProvider.setEnabled(editable);
        deleteProvider.setEnabled(editable && editingProviderId != null);
        providerName.setEnabled(editable);
        enabled.setEnabled(editable);
        baseUrl.setEnabled(editable);
        model.setEnabled(editable);
        apiKey.setEnabled(editable);
        modelPicker.setEnabled(editable && !draftModels.isEmpty());
        fetchModels.setEnabled(editable);
        searchSettings.setEnabled(editable);
        save.setEnabled(editable);
        test.setEnabled(editable && !testing);
    }

    private void testConnection() {
        ConfigStore.Config config = form();
        String name = providerName.getText().toString().trim();
        testing = true;
        updateControls();
        test.setText("测试中…");
        ModelClient client = new ModelClient();
        testClient = client;
        worker.submit(() -> {
            try {
                client.ask(config, "请只回复连接成功。");
                runOnUiThread(() -> toast((name.isEmpty() ? config.model : name) + " 连接成功"));
            } catch (Exception error) {
                runOnUiThread(() -> toast((name.isEmpty() ? config.model : name) + " · " + error.getMessage()));
            } finally {
                testClient = null;
                runOnUiThread(() -> {
                    testing = false;
                    updateControls();
                    test.setText("测试连接");
                });
            }
        });
    }

    private void refreshStatus() {
        if (store == null || status == null) {
            return;
        }
        Bundle details = store.status();
        status.setText(details.getString("message"));
        long time = details.getLong("time");
        String timestamp = time == 0 ? "" : new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(time));
        String modelName = details.getString("model", "");
        statusTime.setText((modelName.isEmpty() ? "" : modelName + "  ·  ") + timestamp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        searchSettings.setText("联网搜索 · " + new SearchStore(this).label());
        try {
            ConfigStore.connectTarget(this);
        } catch (RuntimeException error) {
            toast("连接雷鸟 App 失败，请确认已安装雷鸟 AI");
        }
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (modelDialog != null) {
            modelDialog.dismiss();
        }
        ModelClient client = testClient;
        if (client != null) {
            client.cancel();
        }
        worker.shutdown();
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));
        layout.setBackground(background);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        return view;
    }

    private EditText input(LinearLayout parent, String label, int type) {
        parent.addView(text(label, 13, MUTED));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setInputType(type);
        input.setSelectAllOnFocus(false);
        parent.addView(input, block(2, 18));
        return input;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : BLUE);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? BLUE : Color.rgb(232, 238, 249)));
        return button;
    }

    private LinearLayout.LayoutParams block(int top, int bottom) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.topMargin = dp(top);
        parameters.bottomMargin = dp(bottom);
        return parameters;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
