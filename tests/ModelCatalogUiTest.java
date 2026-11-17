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
import android.widget.EditText;
import android.widget.ListView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ModelCatalogUiTest {
    private final Instrumentation instrumentation;
    private final Context context;
    private final ConfigStore store;
    private Activity activity;

    ModelCatalogUiTest(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
        store = new ConfigStore(context);
    }

    void run() throws Exception {
        String originalId = store.activeProviderId();
        ConfigStore.Config original = store.read();
        Set<String> initialIds = new HashSet<>();
        for (ConfigStore.Provider provider : store.readProviders()) {
            initialIds.add(provider.id);
        }
        String name = "模型验证-" + UUID.randomUUID().toString().substring(0, 6);
        try (SearchRegressionTests.HttpFixture server = new SearchRegressionTests.HttpFixture()) {
            launch();
            click("addProvider");
            instrumentation.runOnMainSync(() -> {
                edit("providerName").setText(name);
                edit("baseUrl").setText(server.url("/v1/chat/completions"));
                edit("apiKey").setText("fake-catalog-key");
            });
            check(edit("model").getText().toString().isEmpty(), "new provider already selected a model");
            click("fetchModels");
            waitFlag("fetching");
            check(server.modelListPath.equals("/v1/models"), "catalog UI used wrong URL");
            filter("ALPHA");
            selectRow(0);
            filter("beta");
            selectRow(0);
            filter("catalog");
            instrumentation.runOnMainSync(() -> {
                ListView list = list();
                check(list.getCount() == 2 && list.isItemChecked(0) && list.isItemChecked(1),
                        "filtered model selections were lost");
            });
            captureDialog("model-catalog-ui.png");
            dialog("添加所选");
            check(store.activeProviderId().equals(originalId), "fetching saved model configuration implicitly");
            check(edit("model").getText().toString().equals("catalog-alpha"), "first selected model was not applied to draft");
            click("modelPicker");
            filter("beta");
            selectRow(0);
            check(store.activeProviderId().equals(originalId), "draft model switch changed active provider");
            click("save");
            waitFlag("updating");
            String providerId = store.activeProviderId();
            ConfigStore.Provider saved = provider(providerId);
            check(saved.models.equals(Arrays.asList("catalog-alpha", "catalog-beta")), "UI did not save multiple model IDs");
            check(saved.model.equals("catalog-beta") && saved.config(original.enabled).apiKey.equals("fake-catalog-key"),
                    "UI selected wrong model or credential");
            instrumentation.runOnMainSync(activity::finish);
            launch();
            check(edit("model").getText().toString().equals("catalog-beta"), "selected model did not survive reopening");
            click("modelPicker");
            filter("alpha");
            selectRow(0);
            waitFlag("updating");
            check(store.read().model.equals("catalog-alpha"), "saved model was not activated immediately");
            chooseProvider(originalId);
            waitFlag("updating");
            chooseProvider(providerId);
            waitFlag("updating");
            check(store.read().model.equals("catalog-alpha"), "provider switch lost selected model");
            instrumentation.runOnMainSync(() -> edit("providerName").setText(name + "-edited"));
            click("modelPicker");
            filter("beta");
            selectRow(0);
            check(store.read().model.equals("catalog-alpha") && edit("model").getText().toString().equals("catalog-beta"),
                    "model picker discarded unsaved changes");
            click("save");
            waitFlag("updating");
            check(store.read().model.equals("catalog-beta"), "draft model was not saved");
            instrumentation.runOnMainSync(() -> edit("model").setText("manual-model"));
            click("save");
            waitFlag("updating");
            check(provider(providerId).models.equals(Arrays.asList("catalog-alpha", "catalog-beta", "manual-model")),
                    "manual model save lost other models");
            check(store.read().enabled == original.enabled, "model operations changed the global toggle");
            click("modelPicker");
            captureDialog("saved-models-ui.png");
            dialog("取消");
        } finally {
            store.activateProvider(originalId);
            for (ConfigStore.Provider provider : store.readProviders()) {
                if (!initialIds.contains(provider.id) && provider.name.startsWith(name)) {
                    store.deleteProvider(provider.id);
                }
            }
            ConfigStore.connectTarget(context);
            if (activity != null) {
                instrumentation.runOnMainSync(activity::finish);
            }
        }
    }

    private ConfigStore.Provider provider(String id) throws Exception {
        for (ConfigStore.Provider provider : store.readProviders()) {
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        throw new AssertionError("provider missing");
    }

    private void launch() {
        activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
    }

    private Object field(String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
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

    private void filter(String value) {
        instrumentation.runOnMainSync(() -> {
            android.app.AlertDialog dialog = (android.app.AlertDialog) field("modelDialog");
            EditText filter = findFilter(dialog.getWindow().getDecorView());
            check(filter != null, "model filter missing");
            filter.setText(value);
        });
        instrumentation.waitForIdleSync();
    }

    private EditText findFilter(View view) {
        if (view instanceof EditText && "搜索模型 ID".contentEquals(view.getContentDescription())) {
            return (EditText) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText found = findFilter(group.getChildAt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private ListView list() {
        ListView list = ProviderUiTest.findWindowView(ListView.class, null);
        check(list != null, "model dialog list missing");
        return list;
    }

    private void selectRow(int position) {
        instrumentation.runOnMainSync(() -> {
            ListView list = list();
            list.performItemClick(list.getChildAt(position), position, list.getAdapter().getItemId(position));
        });
        instrumentation.waitForIdleSync();
    }

    private void chooseProvider(String id) throws Exception {
        List<ConfigStore.Provider> providers = store.readProviders();
        int position = -1;
        for (int index = 0; index < providers.size(); index++) {
            if (providers.get(index).id.equals(id)) {
                position = index;
            }
        }
        check(position >= 0, "provider missing from picker");
        click("providerPicker");
        selectRow(position);
    }

    private void dialog(String text) {
        instrumentation.runOnMainSync(() -> {
            Button button = ProviderUiTest.findWindowView(Button.class, text);
            check(button != null, "dialog button missing");
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
        throw new AssertionError("model UI operation timed out");
    }

    private void captureDialog(String name) {
        SystemClock.sleep(150);
        instrumentation.runOnMainSync(() -> {
            View root = ((android.app.AlertDialog) field("modelDialog")).getWindow().getDecorView();
            ProviderUiTest.layoutForCapture(root);
            ListView visibleList = list();
            for (int index = 0; index < visibleList.getChildCount(); index++) {
                View child = visibleList.getChildAt(index);
                if (child instanceof android.widget.Checkable) {
                    check(((android.widget.Checkable) child).isChecked()
                            == visibleList.isItemChecked(visibleList.getFirstVisiblePosition() + index),
                            "visible model check state does not match saved selection");
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), name))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                bitmap.recycle();
            }
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
