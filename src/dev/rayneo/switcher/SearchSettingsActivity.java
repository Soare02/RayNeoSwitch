package dev.rayneo.switcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SearchSettingsActivity extends Activity {
    private static final int INK = Color.rgb(23, 32, 51);
    private static final int MUTED = Color.rgb(87, 97, 115);
    private static final int BLUE = Color.rgb(33, 94, 222);
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private SearchStore store;
    private List<SearchStore.Provider> providers = new ArrayList<>();
    private String editingId;
    private SearchConfig initial;
    private Button picker;
    private Button add;
    private Button delete;
    private Button save;
    private Button test;
    private Button testFetch;
    private LinearLayout editor;
    private TextView description;
    private TextView result;
    private EditText name;
    private EditText endpoint;
    private EditText apiKey;
    private EditText count;
    private EditText fetchCount;
    private EditText scope;
    private EditText testQuery;
    private CheckBox needContent;
    private boolean updating;
    private boolean testing;
    private volatile SearchClient testClient;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SearchStore(this);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(22), dp(20), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(243, 245, 248));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            }
            root.setPadding(dp(22), top + dp(20), dp(22), bottom + dp(24));
            return insets;
        });
        setContentView(scroll);
        Button back = button("返回模型设置");
        root.addView(back, block());
        back.setOnClickListener(view -> confirmChanges(this::finish));
        root.addView(text("联网搜索", 27, INK), block());
        root.addView(text("用于已开启的自定义模型问答，切换后下一次提问生效。", 14, MUTED), block());
        picker = button("选择搜索方式");
        picker.setSingleLine(true);
        picker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(picker, block());
        picker.setOnClickListener(view -> choose());
        description = text("", 14, MUTED);
        root.addView(description, block());
        LinearLayout actions = new LinearLayout(this);
        add = button("新增自定义搜索");
        delete = button("删除供应商");
        actions.addView(add, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(delete, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(actions, block());
        add.setOnClickListener(view -> confirmChanges(() -> show(null,
                SearchConfig.tools("", SearchConfig.DEFAULT_URL, "", 5, false, "", SearchConfig.DEFAULT_FETCH_COUNT))));
        delete.setOnClickListener(view -> confirmDelete());
        editor = column();
        root.addView(editor);
        name = input(editor, "搜索供应商名称", InputType.TYPE_CLASS_TEXT);
        endpoint = input(editor, "工具基础地址（搜索与抓取共用）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint(SearchConfig.DEFAULT_URL);
        apiKey = input(editor, "搜索与抓取 API Key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        count = input(editor, "返回结果条数（1–50）", InputType.TYPE_CLASS_NUMBER);
        scope = input(editor, "搜索范围（选填）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scope.setHint("site:example.com lang:zh after:2026");
        needContent = new CheckBox(this);
        needContent.setText("请求网页正文（need_content）");
        needContent.setTextColor(INK);
        editor.addView(needContent, block());
        fetchCount = input(editor, "补抓网页数量（0–50，0 为关闭）", InputType.TYPE_CLASS_NUMBER);
        editor.addView(text("搜索后按排名补抓缺少正文的网页，返回的 Markdown 会交给当前模型。已有正文时跳过补抓。", 12, MUTED), block());
        save = button("保存并使用");
        save.setTextColor(Color.WHITE);
        save.setBackgroundTintList(ColorStateList.valueOf(BLUE));
        editor.addView(save, block());
        save.setOnClickListener(view -> save(null));
        testQuery = input(editor, "测试问题或网页 URL", InputType.TYPE_CLASS_TEXT);
        testQuery.setText("DeepSeek 搜索 API 文档");
        LinearLayout tests = new LinearLayout(this);
        test = button("测试搜索");
        testFetch = button("测试抓取");
        tests.addView(test, new LinearLayout.LayoutParams(0, dp(50), 1));
        tests.addView(testFetch, new LinearLayout.LayoutParams(0, dp(50), 1));
        editor.addView(tests, block());
        test.setOnClickListener(view -> testSearch(false));
        testFetch.setOnClickListener(view -> testSearch(true));
        editor.addView(text("基础地址自动拼接 /search 和 /fetch。测试搜索会按配置补抓正文，各接口按服务商规则产生用量。测试抓取时请填入网页 URL。", 12, MUTED), block());
        result = text("", 14, INK);
        result.setTextIsSelectable(true);
        editor.addView(result, block());
        try {
            load();
        } catch (Exception error) {
            toast("搜索配置读取失败，请重新打开此页面");
        }
        controls();
    }

    private void load() throws Exception {
        providers = store.providers();
        if (!SearchConfig.CUSTOM.equals(store.mode())) {
            show(null, SearchConfig.builtin(store.mode()));
            return;
        }
        for (SearchStore.Provider provider : providers) {
            if (provider.id.equals(store.activeId())) {
                SearchConfig config;
                try {
                    config = provider.config();
                } catch (Exception error) {
                    config = provider.config("");
                    toast("搜索密钥读取失败，请重新填写并保存");
                }
                show(provider.id, config);
                return;
            }
        }
        throw new IllegalStateException("未找到当前搜索供应商");
    }

    private void show(String id, SearchConfig config) {
        editingId = id;
        initial = config;
        boolean custom = SearchConfig.CUSTOM.equals(config.mode);
        editor.setVisibility(custom ? View.VISIBLE : View.GONE);
        picker.setText(custom && id == null ? "新增自定义搜索 ▾" : config.name + " ▾");
        if (SearchConfig.OFFICIAL.equals(config.mode)) {
            description.setText("由雷鸟按问题触发搜索，等待和重试沿用雷鸟原生配置。收到官方完成或错误回调后，模块将已取得的来源资料或官方回答参考交给当前模型供应商生成回答。");
        } else if (custom) {
            description.setText("按 DeepSeek Search / Fetch 协议搜索和读取网页，将资料及来源链接交给当前模型供应商生成回答。当前已保存的选择为「" + store.label() + "」。"
                    + (config.toolsBaseUrl.isEmpty() ? "当前为旧版独立搜索地址，填写工具基础地址后可启用抓取。" : ""));
        } else {
            description.setText("语音问题直接交给当前模型供应商回答。");
        }
        name.setText(config.name);
        endpoint.setText(config.address());
        apiKey.setText(config.apiKey);
        count.setText(String.valueOf(config.count));
        scope.setText(config.scope);
        needContent.setChecked(config.needContent);
        fetchCount.setText(String.valueOf(config.fetchCount));
        result.setText("");
        controls();
    }

    private SearchConfig form() {
        int results;
        int pages;
        try {
            results = Integer.parseInt(count.getText().toString().trim());
        } catch (NumberFormatException error) {
            results = -1;
        }
        try {
            pages = Integer.parseInt(fetchCount.getText().toString().trim());
        } catch (NumberFormatException error) {
            pages = -1;
        }
        String address = endpoint.getText().toString().trim();
        if (initial != null && SearchConfig.CUSTOM.equals(initial.mode)
                && initial.toolsBaseUrl.isEmpty() && initial.endpoint.equals(address)) {
            return new SearchConfig(SearchConfig.CUSTOM, name.getText().toString(), address,
                    apiKey.getText().toString(), results, needContent.isChecked(), scope.getText().toString(), "", pages);
        }
        return SearchConfig.tools(name.getText().toString(), address, apiKey.getText().toString(),
                results, needContent.isChecked(), scope.getText().toString(), pages);
    }

    private boolean hasChanges() {
        if (initial == null || !SearchConfig.CUSTOM.equals(initial.mode)) {
            return false;
        }
        SearchConfig draft = form();
        return !draft.name.equals(initial.name) || !draft.endpoint.equals(initial.endpoint)
                || !draft.apiKey.equals(initial.apiKey) || draft.count != initial.count
                || draft.needContent != initial.needContent || !draft.scope.equals(initial.scope)
                || draft.fetchCount != initial.fetchCount || !draft.toolsBaseUrl.equals(initial.toolsBaseUrl);
    }

    private void confirmChanges(Runnable next) {
        if (!hasChanges()) {
            next.run();
            return;
        }
        new AlertDialog.Builder(this).setTitle("保存搜索配置修改？")
                .setPositiveButton("保存并继续", (dialog, which) -> save(next))
                .setNeutralButton("放弃更改", (dialog, which) -> next.run())
                .setNegativeButton("取消", null).show();
    }

    private void choose() {
        String[] choices = new String[providers.size() + 2];
        choices[0] = "关闭联网搜索";
        choices[1] = "官方搜索";
        int selected = SearchConfig.OFFICIAL.equals(initial.mode) ? 1 : 0;
        for (int index = 0; index < providers.size(); index++) {
            SearchStore.Provider provider = providers.get(index);
            choices[index + 2] = "自定义 · " + provider.name;
            if (SearchConfig.CUSTOM.equals(initial.mode) && provider.id.equals(editingId)) {
                selected = index + 2;
            }
        }
        new AlertDialog.Builder(this).setTitle("选择搜索方式")
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < 2) {
                        String mode = which == 0 ? SearchConfig.OFF : SearchConfig.OFFICIAL;
                        confirmChanges(() -> change(() -> store.setBuiltin(mode), null));
                    } else {
                        String id = providers.get(which - 2).id;
                        if (!id.equals(editingId)) {
                            confirmChanges(() -> change(() -> store.activate(id), null));
                        }
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void confirmDelete() {
        String id = editingId;
        if (id == null) {
            return;
        }
        String message = providers.size() == 1 ? "删除后关闭联网搜索。" : "删除后使用第一个已保存的搜索供应商。";
        new AlertDialog.Builder(this).setTitle("删除此搜索供应商？").setMessage(message)
                .setPositiveButton("删除", (dialog, which) -> change(() -> store.delete(id), null))
                .setNegativeButton("取消", null).show();
    }

    private void save(Runnable next) {
        SearchConfig config = form();
        String id = editingId;
        change(() -> store.save(id, config), next);
    }

    private interface Change {
        void run() throws Exception;
    }

    private void change(Change action, Runnable next) {
        updating = true;
        controls();
        worker.submit(() -> {
            try {
                action.run();
                boolean synced;
                try {
                    ConfigStore.connectTarget(this);
                    synced = true;
                } catch (RuntimeException error) {
                    synced = false;
                }
                String message = synced ? "搜索设置已保存，下一次提问生效" : "设置已保存，请打开雷鸟 AI 后重试同步";
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    updating = false;
                    try {
                        load();
                        toast(message);
                        if (next != null) {
                            next.run();
                        }
                    } catch (Exception error) {
                        toast("设置已保存，请重新打开此页面");
                    }
                    controls();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        updating = false;
                        controls();
                        toast(error.getMessage());
                    }
                });
            }
        });
    }

    private void testSearch(boolean fetch) {
        SearchConfig config = form();
        String question = testQuery.getText().toString();
        testing = true;
        controls();
        (fetch ? testFetch : test).setText("测试中…");
        SearchClient client = new SearchClient();
        testClient = client;
        worker.submit(() -> {
            try {
                SearchContext context = fetch ? client.fetch(config, question) : client.search(config, question);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        result.setText("测试「" + config.name + "」返回 " + context.size() + " 条来源\n\n"
                                + SearchConfig.limit(context.render(), 1500));
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        result.setText((fetch ? "抓取失败 · " : "搜索失败 · ") + error.getMessage());
                    }
                });
            } finally {
                testClient = null;
                runOnUiThread(() -> {
                    testing = false;
                    test.setText("测试搜索");
                    testFetch.setText("测试抓取");
                    controls();
                });
            }
        });
    }

    private void controls() {
        boolean editable = !updating && !testing && initial != null;
        picker.setEnabled(editable);
        add.setEnabled(editable);
        delete.setEnabled(editable && editingId != null);
        save.setEnabled(editable);
        test.setEnabled(editable && !testing);
        testFetch.setEnabled(editable);
        for (EditText field : new EditText[]{name, endpoint, apiKey, count, fetchCount, scope, testQuery}) {
            field.setEnabled(editable);
        }
        needContent.setEnabled(editable);
    }

    @Override
    protected void onDestroy() {
        SearchClient client = testClient;
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

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(BLUE);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232, 238, 249)));
        button.setMinHeight(dp(48));
        return button;
    }

    private EditText input(LinearLayout parent, String label, int type) {
        parent.addView(text(label, 14, MUTED));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setTextColor(INK);
        input.setTextSize(16);
        parent.addView(input, block());
        return input;
    }

    private LinearLayout.LayoutParams block() {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.bottomMargin = dp(16);
        return parameters;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}
