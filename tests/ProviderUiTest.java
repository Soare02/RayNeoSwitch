package dev.rayneo.switcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
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

/** Uses the normal buttons and dialogs, then restores the original provider. */
final class ProviderUiTest {
    private final Instrumentation instrumentation;
    private final Context context;
    private final ConfigStore store;
    private Activity activity;

    ProviderUiTest(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
        store = new ConfigStore(context);
    }

    void run() throws Exception {
        List<ConfigStore.Provider> initial = store.readProviders();
        String originalId = store.activeProviderId();
        ConfigStore.Config original = store.read();
        Set<String> initialIds = new HashSet<>();
        for (ConfigStore.Provider provider : initial) {
            initialIds.add(provider.id);
        }
        String testName = "切换验证-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            launch();
            clickField("addProvider");
            instrumentation.runOnMainSync(() -> {
                edit("providerName").setText(testName);
                edit("baseUrl").setText(original.baseUrl);
                edit("model").setText(original.model);
                edit("apiKey").setText(original.apiKey);
            });
            clickField("save");
            waitForUpdate();
            String testId = store.activeProviderId();
            check(!initialIds.contains(testId), "add button did not create a provider");
            check(store.readProviders().size() == initial.size() + 1, "add button replaced an existing provider");
            sameOriginal(original);
            capture("provider-editor-test.png");

            instrumentation.runOnMainSync(() -> activity.finish());
            launch();
            check(testId.equals(store.activeProviderId()), "selection lost after activity restart");
            check(testName.equals(edit("providerName").getText().toString()), "editor did not load saved provider");

            instrumentation.runOnMainSync(() -> edit("providerName").setText("未保存的修改"));
            choose(originalId);
            clickDialogButton("取消");
            check(testId.equals(store.activeProviderId()), "cancel changed active provider");
            check("未保存的修改".equals(edit("providerName").getText().toString()), "cancel discarded the draft");
            choose(originalId);
            clickDialogButton("放弃更改");
            waitForUpdate();
            check(originalId.equals(store.activeProviderId()), "discard did not complete the switch");
            sameOriginal(original);

            choose(testId);
            waitForUpdate();
            instrumentation.runOnMainSync(() -> edit("providerName").setText(testName + "-已编辑"));
            choose(originalId);
            clickDialogButton("保存并继续");
            waitForUpdate();
            check(originalId.equals(store.activeProviderId()), "save-and-continue did not switch");
            boolean renamed = false;
            for (ConfigStore.Provider provider : store.readProviders()) {
                if (provider.id.equals(testId)) {
                    renamed = provider.name.equals(testName + "-已编辑");
                }
            }
            check(renamed, "save-and-continue lost the edit");

            choose(testId);
            waitForUpdate();
            clickField("deleteProvider");
            clickDialogButton("取消");
            check(testId.equals(store.activeProviderId()), "cancelled deletion changed selection");
            clickField("deleteProvider");
            clickDialogButton("删除");
            waitForUpdate();
            check(store.readProviders().size() == initial.size(), "delete button retained temporary provider");
            check(initial.get(0).id.equals(store.activeProviderId()), "deletion did not select the first saved provider");
            if (!originalId.equals(store.activeProviderId())) {
                choose(originalId);
                waitForUpdate();
            }
            sameOriginal(original);
            capture("provider-final-ui.png");
        } finally {
            store.activateProvider(originalId);
            for (ConfigStore.Provider provider : store.readProviders()) {
                if (!initialIds.contains(provider.id) && provider.name.startsWith(testName)) {
                    store.deleteProvider(provider.id);
                }
            }
            ConfigStore.connectTarget(context);
            if (activity != null) {
                instrumentation.runOnMainSync(() -> activity.finish());
            }
        }
    }

    private void sameOriginal(ConfigStore.Config original) throws Exception {
        ConfigStore.Config actual = store.read();
        check(original.enabled == actual.enabled && original.baseUrl.equals(actual.baseUrl)
                && original.model.equals(actual.model) && original.apiKey.equals(actual.apiKey),
                "UI operation changed original endpoint, model, key or enabled state");
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
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private EditText edit(String name) {
        return (EditText) field(name);
    }

    private void clickField(String name) {
        instrumentation.runOnMainSync(() -> ((Button) field(name)).performClick());
        instrumentation.waitForIdleSync();
    }

    private void choose(String id) throws Exception {
        List<ConfigStore.Provider> providers = store.readProviders();
        int selected = -1;
        for (int index = 0; index < providers.size(); index++) {
            if (providers.get(index).id.equals(id)) {
                selected = index;
            }
        }
        check(selected >= 0, "provider missing from picker");
        clickField("providerPicker");
        int position = selected;
        instrumentation.runOnMainSync(() -> {
            ListView list = findWindowView(ListView.class, null);
            check(list != null, "provider dialog list missing");
            list.performItemClick(list.getChildAt(position), position, list.getAdapter().getItemId(position));
        });
        instrumentation.waitForIdleSync();
    }

    private void clickDialogButton(String label) {
        instrumentation.runOnMainSync(() -> {
            Button button = findWindowView(Button.class, label);
            check(button != null, "dialog button missing: " + label);
            button.performClick();
        });
        instrumentation.waitForIdleSync();
    }

    private void waitForUpdate() {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] updating = new boolean[1];
            instrumentation.runOnMainSync(() -> updating[0] = (Boolean) field("updating"));
            if (!updating[0]) {
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(25);
        }
        throw new AssertionError("configuration operation timed out");
    }

    static <T extends View> T findWindowView(Class<T> type, String text) {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            T found = findView(root, type, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static void layoutForCapture(View root) {
        int width = root.getWidth();
        int height = root.getHeight();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        settleDrawables(root);
    }

    private static void settleDrawables(View view) {
        view.jumpDrawablesToCurrentState();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                settleDrawables(group.getChildAt(index));
            }
        }
    }

    private static <T extends View> T findView(View view, Class<T> type, String text) {
        if (type.isInstance(view) && (text == null
                || view instanceof TextView && text.contentEquals(((TextView) view).getText()))) {
            return type.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = findView(group.getChildAt(index), type, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void capture(String name) {
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
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
