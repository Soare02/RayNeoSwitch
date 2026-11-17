package dev.rayneo.switcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class SearchUiTest {
    private final Instrumentation instrumentation;
    private final Context context;
    private final SearchStore store;
    private Activity activity;
    private Activity main;

    SearchUiTest(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
        store = new SearchStore(context);
    }

    void run() throws Exception {
        String mode = store.mode();
        String activeId = store.activeId();
        Set<String> originalIds = new HashSet<>();
        for (SearchStore.Provider provider : store.providers()) {
            originalIds.add(provider.id);
        }
        ConfigStore.Config originalModel = new ConfigStore(context).read();
        String prefix = "搜索验证-" + UUID.randomUUID().toString().substring(0, 6);
        try (SearchRegressionTests.HttpFixture server = new SearchRegressionTests.HttpFixture()) {
            main = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(SearchSettingsActivity.class.getName(), null, false);
            instrumentation.runOnMainSync(() -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("searchSettings");
                    field.setAccessible(true);
                    ((Button) field.get(main)).performClick();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 4000);
            instrumentation.removeMonitor(monitor);
            check(activity != null, "main entry did not open search settings");
            instrumentation.waitForIdleSync();
            choose(1);
            waitFlag("updating");
            check(SearchConfig.OFFICIAL.equals(store.mode()), "official picker selection failed");
            awaitRuntime(SearchConfig.OFFICIAL, "官方搜索");
            capture("search-official-ui.png");

            add(prefix + "-one", server.url("/v1/tools"), "fake-search-one");
            String firstId = store.activeId();
            add(prefix + "-two", server.url("/v1/tools/search"), "fake-search-two");
            String secondId = store.activeId();
            awaitRuntime(SearchConfig.CUSTOM, prefix + "-two");
            check(!firstId.equals(secondId), "second search replaced first provider");
            instrumentation.runOnMainSync(() -> activity.finish());
            activity = instrumentation.startActivitySync(new Intent(context, SearchSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            check(secondId.equals(store.activeId()), "search selection did not survive reopening");
            check("fake-search-two".equals(edit("apiKey").getText().toString()), "reopened search key mismatch");
            check(server.url("/v1/tools").equals(edit("endpoint").getText().toString()), "UI did not normalize tools base");
            check("2".equals(edit("fetchCount").getText().toString()), "reopened fetch count mismatch");
            check(edit("apiKey").getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod,
                    "search API key must be masked");
            click("test");
            waitFlag("testing");
            check(((TextView) field("result")).getText().toString().contains("返回 1 条来源"), "search test button did not show results");
            check(server.searchRequest.getInt("count") == 3, "UI result count was not used");
            check(server.fetchRequests.size() == 1, "UI search did not fetch source content");
            check(((TextView) field("result")).getText().toString().contains("独立抓取的 Markdown 内容"), "UI search omitted fetched Markdown");
            capture("search-custom-ui.png");
            instrumentation.runOnMainSync(() -> edit("testQuery").setText("https://example.com/read-this"));
            click("testFetch");
            waitFlag("testing");
            check(server.fetchRequest.getString("url").equals("https://example.com/read-this"), "UI fetch URL mismatch");
            check(((TextView) field("result")).getText().toString().contains("# 抓取正文"), "UI fetch test omitted Markdown");
            instrumentation.runOnMainSync(() -> {
                android.view.ViewGroup content = activity.findViewById(android.R.id.content);
                ((android.widget.ScrollView) content.getChildAt(0)).fullScroll(View.FOCUS_DOWN);
            });
            capture("search-fetch-ui.png");

            instrumentation.runOnMainSync(() -> edit("name").setText(prefix + "-draft"));
            choose(1);
            dialog("取消");
            check(secondId.equals(store.activeId()), "cancel changed search provider");
            choose(1);
            dialog("放弃更改");
            waitFlag("updating");
            check(SearchConfig.OFFICIAL.equals(store.mode()), "discard did not switch to official");

            chooseId(firstId);
            waitFlag("updating");
            check("fake-search-one".equals(edit("apiKey").getText().toString()), "switch did not restore independent search key");
            instrumentation.runOnMainSync(() -> {
                edit("scope").setText("site:example.org");
                edit("fetchCount").setText("0");
            });
            choose(0);
            dialog("保存并继续");
            waitFlag("updating");
            check(SearchConfig.OFF.equals(store.mode()), "save-and-continue did not select off");
            awaitRuntime(SearchConfig.OFF, "关闭联网搜索");
            chooseId(firstId);
            waitFlag("updating");
            check("site:example.org".equals(store.read().scope), "scope edit was lost");
            check(store.read().fetchCount == 0, "fetch count edit was lost");
            click("delete");
            dialog("取消");
            check(firstId.equals(store.activeId()), "cancelled search deletion changed state");
            click("delete");
            dialog("删除");
            waitFlag("updating");
            for (SearchStore.Provider provider : store.providers()) {
                check(!firstId.equals(provider.id), "delete button retained search provider");
            }
            ConfigStore.Config actualModel = new ConfigStore(context).read();
            check(originalModel.enabled == actualModel.enabled && originalModel.baseUrl.equals(actualModel.baseUrl)
                    && originalModel.model.equals(actualModel.model) && originalModel.apiKey.equals(actualModel.apiKey),
                    "search UI changed model configuration");
        } finally {
            if (!activeId.isEmpty()) {
                store.activate(activeId);
            }
            for (SearchStore.Provider provider : store.providers()) {
                if (!originalIds.contains(provider.id) && provider.name.startsWith(prefix)) {
                    store.delete(provider.id);
                }
            }
            if (!SearchConfig.CUSTOM.equals(mode)) {
                store.setBuiltin(mode);
            }
            ConfigStore.connectTarget(context);
            awaitRuntime(store.mode(), store.label());
            instrumentation.runOnMainSync(() -> {
                if (activity != null) {
                    activity.finish();
                }
                if (main != null) {
                    main.finish();
                }
            });
        }
    }

    private void add(String name, String url, String key) {
        click("add");
        check("3".equals(edit("fetchCount").getText().toString()), "new provider fetch default is incorrect");
        instrumentation.runOnMainSync(() -> {
            edit("name").setText(name);
            edit("endpoint").setText(url);
            edit("apiKey").setText(key);
            edit("count").setText("3");
            edit("scope").setText("site:example.com");
            ((CheckBox) field("needContent")).setChecked(false);
            edit("fetchCount").setText("2");
        });
        click("save");
        waitFlag("updating");
    }

    private Object field(String name) {
        try {
            Field field = SearchSettingsActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private EditText edit(String name) {
        return (EditText) field(name);
    }

    private void click(String name) {
        instrumentation.runOnMainSync(() -> ((Button) field(name)).performClick());
        instrumentation.waitForIdleSync();
    }

    private void chooseId(String id) throws Exception {
        List<SearchStore.Provider> providers = store.providers();
        for (int index = 0; index < providers.size(); index++) {
            if (providers.get(index).id.equals(id)) {
                choose(index + 2);
                return;
            }
        }
        throw new AssertionError("missing search provider");
    }

    private void choose(int position) {
        click("picker");
        instrumentation.runOnMainSync(() -> {
            ListView list = ProviderUiTest.findWindowView(ListView.class, null);
            check(list != null, "search picker missing");
            list.performItemClick(list.getChildAt(position), position, list.getAdapter().getItemId(position));
        });
        instrumentation.waitForIdleSync();
    }

    private void dialog(String text) {
        instrumentation.runOnMainSync(() -> {
            Button button = ProviderUiTest.findWindowView(Button.class, text);
            check(button != null, "missing search dialog button");
            button.performClick();
        });
        instrumentation.waitForIdleSync();
    }

    private void waitFlag(String name) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] busy = new boolean[1];
            instrumentation.runOnMainSync(() -> busy[0] = (Boolean) field(name));
            if (!busy[0]) {
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(25);
        }
        throw new AssertionError("search UI operation timed out");
    }

    private void awaitRuntime(String mode, String name) {
        if (!new ConfigStore(context).status().getBoolean("enabled")) {
            return;
        }
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            android.os.Bundle status = new ConfigStore(context).status();
            if (mode.equals(status.getString("search_mode")) && name.equals(status.getString("search_name"))) {
                return;
            }
            SystemClock.sleep(25);
        }
        throw new AssertionError("search settings were not acknowledged by the live RayNeo module");
    }

    private void capture(String name) {
        SystemClock.sleep(150);
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            ProviderUiTest.layoutForCapture(root);
            Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), name))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, output);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                image.recycle();
            }
        });
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
