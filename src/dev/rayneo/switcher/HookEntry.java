package dev.rayneo.switcher;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final Uri CONFIG_URI = Uri.parse("content://" + ConfigStore.AUTHORITY);
    private Handler main;
    private ThreadPoolExecutor executor;
    private boolean setupAttempted;
    private volatile boolean ready;
    private boolean accessPersisted;
    private volatile String lastMessage = "模块已加载，可接管眼镜模型回答";
    private volatile String lastModel = "";
    private ConfigStore localConfig;
    private volatile ConfigStore.Config activeConfig;
    private volatile Turn current;
    private Context context;
    private Class<?> nlpClass;
    private Method nlpMethod;
    private Method completeMethod;

    private static final class Turn {
        final Object callback;
        final String query;
        final String sid;
        final String dialogId;
        final ConfigStore.Config config;
        final ModelClient client = new ModelClient();
        final SearchClient searchClient = new SearchClient();
        final OfficialSearchCollector official;
        final AtomicBoolean submitted = new AtomicBoolean();
        volatile boolean cancelled;
        volatile boolean delivered;
        volatile Future<?> future;
        volatile int round;

        Turn(Object callback, String query, String sid, String dialogId, ConfigStore.Config config) {
            this.callback = callback;
            this.query = query;
            this.sid = sid;
            this.dialogId = dialogId;
            this.config = config;
            official = new OfficialSearchCollector(query);
        }

        void cancel() {
            cancelled = true;
            client.cancel();
            searchClient.cancel();
            Future<?> request = future;
            if (request != null) {
                request.cancel(true);
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loaded) throws Throwable {
        if (!ConfigStore.TARGET_PACKAGE.equals(loaded.packageName)
                || !ConfigStore.TARGET_PACKAGE.equals(loaded.processName)) {
            return;
        }
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam parameter) {
                if (setupAttempted) {
                    return;
                }
                setupAttempted = true;
                context = (Application) parameter.thisObject;
                try {
                    long version = context.getPackageManager()
                            .getPackageInfo(ConfigStore.TARGET_PACKAGE, 0).getLongVersionCode();
                    if (version != 511) {
                        report("当前适配雷鸟 AI 1.0.25（511）", "");
                        return;
                    }
                    main = new Handler(Looper.getMainLooper());
                    executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
                    localConfig = new ConfigStore(context, "rayneo_switch_runtime_config");
                    installHooks(loaded.classLoader);
                    BroadcastReceiver statusRequest = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context receiverContext, Intent intent) {
                            Bundle config = intent.getBundleExtra("config");
                            if (config != null) {
                                try {
                                    saveConfig(ConfigStore.Config.fromBundle(config));
                                } catch (Exception error) {
                                    report("配置同步失败，请重新保存后重试", "");
                                    return;
                                }
                            }
                            report(lastMessage, lastModel);
                        }
                    };
                    IntentFilter statusFilter = new IntentFilter(ConfigStore.STATUS_REQUEST);
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(statusRequest, statusFilter,
                                ConfigStore.CONFIG_PERMISSION, main, Context.RECEIVER_EXPORTED);
                    } else {
                        context.registerReceiver(statusRequest, statusFilter, ConfigStore.CONFIG_PERMISSION, main);
                    }
                    ready = true;
                    main.post(() -> {
                        try {
                            if (localConfig.hasConfig()) {
                                activeConfig = localConfig.read();
                            }
                        } catch (Exception error) {
                            report("本地配置读取失败，请重新保存配置", "");
                            return;
                        }
                        report("模块已加载，可接管眼镜模型回答", "");
                    });
                } catch (Throwable error) {
                    XposedBridge.log("RayNeoSwitch setup failed: " + error.getClass().getName());
                    report("模块加载失败，请检查版本和作用域", "");
                }
            }
        });
    }

    private void installHooks(ClassLoader loader) throws Exception {
        Class<?> callbackClass = Class.forName("lm0.d$c", false, loader);
        nlpClass = Class.forName("com.rayneo.airuntime.controller.NlpResult", false, loader);
        nlpMethod = callbackClass.getDeclaredMethod("onNlpResult", nlpClass);
        completeMethod = callbackClass.getDeclaredMethod("onResponseComplete");

        XposedBridge.hookMethod(callbackClass.getDeclaredMethod("onAsrResult",
                String.class, boolean.class, String.class), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam parameter) {
                if (!ready || !((Boolean) parameter.args[1])
                        || ((String) parameter.args[0]).trim().isEmpty()) {
                    return;
                }
                startTurn(parameter.thisObject, (String) parameter.args[0], (String) parameter.args[2]);
            }
        });

        XposedBridge.hookMethod(nlpMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam parameter) throws Throwable {
                Turn turn = current;
                if (turn == null || turn.callback != parameter.thisObject) {
                    return;
                }
                Object result = parameter.args[0];
                String resultSid = (String) nlpClass.getMethod("getSessionId").invoke(result);
                if (!resultSid.isEmpty() && !turn.sid.isEmpty() && !turn.sid.equals(resultSid)) {
                    parameter.setResult(null);
                    return;
                }
                String intent = (String) nlpClass.getMethod("getIntent").invoke(result);
                boolean offline = (Boolean) nlpClass.getMethod("getOffline").invoke(result);
                Object command = nlpClass.getMethod("getCommand").invoke(result);
                boolean deviceCommand = command != null
                        && !((String) command.getClass().getMethod("getName").invoke(command)).trim().isEmpty();
                Class<?> handlers = Class.forName("mm0.g", true, loader);
                Field handlerField = handlers.getDeclaredField("b");
                handlerField.setAccessible(true);
                Map<?, ?> commands = (Map<?, ?>) handlerField.get(null);
                if (offline || deviceCommand || commands.containsKey(intent.trim()) || "exit".equalsIgnoreCase(intent)
                        || "quit".equalsIgnoreCase(intent)) {
                    turn.cancel();
                    current = null;
                    return;
                }
                if (SearchConfig.OFFICIAL.equals(turn.config.search.mode)) {
                    turn.official.answer((String) nlpClass.getMethod("getAnswer").invoke(result),
                            (Boolean) nlpClass.getMethod("getFinished").invoke(result));
                    turn.official.metadata((String) nlpClass.getMethod("getRawData").invoke(result));
                    if (turn.official.ready()) {
                        main.post(() -> submit(turn));
                    }
                }
                turn.round = Math.max(0, (Integer) nlpClass.getMethod("getRound").invoke(result));
                parameter.setResult(null);
            }
        });

        XposedBridge.hookMethod(completeMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam parameter) {
                Turn turn = current;
                if (turn != null && turn.callback == parameter.thisObject) {
                    if (SearchConfig.OFFICIAL.equals(turn.config.search.mode)) {
                        turn.official.complete();
                        main.post(() -> submit(turn));
                    }
                    parameter.setResult(null);
                }
            }
        });

        XposedBridge.hookMethod(callbackClass.getDeclaredMethod("onError", int.class, String.class),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam parameter) {
                        Turn turn = current;
                        if (turn != null && turn.callback == parameter.thisObject && !turn.delivered) {
                            if (SearchConfig.OFFICIAL.equals(turn.config.search.mode)) {
                                turn.official.error((Integer) parameter.args[0]);
                                main.post(() -> submit(turn));
                            }
                            parameter.setResult(null);
                        }
                    }
                });

        XposedBridge.hookMethod(callbackClass.getDeclaredMethod("onDeepThinkStatus", String.class, String.class),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam parameter) {
                        Turn turn = current;
                        if (turn != null && turn.callback == parameter.thisObject
                                && SearchConfig.OFFICIAL.equals(turn.config.search.mode)) {
                            turn.official.metadata((String) parameter.args[1]);
                            if (turn.official.ready()) {
                                main.post(() -> submit(turn));
                            }
                        }
                    }
                });

        Class<?> toolClass = Class.forName("com.rayneo.airuntime.controller.RayClawToolCallInfo", false, loader);
        XposedBridge.hookMethod(callbackClass.getDeclaredMethod("onRayClawToolCallInfo", toolClass),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam parameter) throws Throwable {
                        Turn turn = current;
                        if (turn == null || turn.callback != parameter.thisObject
                                || !SearchConfig.OFFICIAL.equals(turn.config.search.mode) || turn.submitted.get()) {
                            return;
                        }
                        Object info = parameter.args[0];
                        String name = (String) toolClass.getMethod("getToolName").invoke(info);
                        turn.official.tool(name);
                        if ("search_web".equals(name)) {
                            report("官方搜索 · " + toolClass.getMethod("getDisplayName").invoke(info), turn.config.model);
                        }
                    }
                });

    }

    private void startTurn(Object callback, String question, String sid) {
        try {
            ConfigStore.Config config = activeConfig;
            if (config == null) {
                Bundle data = context.getContentResolver().call(CONFIG_URI, "get_config", null, null);
                if (data == null || data.containsKey("error")) {
                    throw new IllegalStateException("Configuration unavailable");
                }
                config = ConfigStore.Config.fromBundle(data);
                saveConfig(config);
            }
            Turn previous = current;
            if (!config.enabled) {
                if (previous != null) {
                    previous.cancel();
                }
                current = null;
                report("自定义模型已关闭，使用官方问答", "");
                return;
            }
            if (previous != null && !previous.cancelled && !previous.delivered && previous.callback == callback
                    && previous.sid.equals(sid) && previous.query.equals(question)) {
                return;
            }
            config.validate();
            Object manager = field(callback, "a");
            String dialogId = String.valueOf(field(manager, "y"));
            Turn turn = new Turn(callback, question, sid, dialogId, config);
            current = turn;
            if (previous != null) {
                previous.cancel();
            }
            executor.purge();
            if (SearchConfig.OFFICIAL.equals(config.search.mode)) {
                report("等待官方搜索，等待与重试沿用雷鸟原生配置", config.model);
            } else {
                submit(turn);
            }
        } catch (Exception error) {
            Turn previous = current;
            current = null;
            if (previous != null) {
                previous.cancel();
            }
            report("配置读取失败，请打开 RayNeo Switch 检查", "");
        }
    }

    private void saveConfig(ConfigStore.Config config) throws Exception {
        localConfig.saveRuntime(config);
        activeConfig = config;
    }

    private void submit(Turn turn) {
        if (turn.cancelled || current != turn || !turn.submitted.compareAndSet(false, true)) {
            return;
        }
        turn.future = executor.submit(() -> request(turn));
        if (turn.cancelled) {
            turn.future.cancel(true);
        }
    }

    private void request(Turn turn) {
        if (turn.cancelled || current != turn) {
            return;
        }
        String answer;
        String deliveryStatus;
        try {
            SearchContext context = null;
            if (SearchConfig.CUSTOM.equals(turn.config.search.mode)) {
                report((turn.config.search.fetchCount > 0 ? "正在搜索与读取网页 · " : "正在搜索 · ")
                        + turn.config.search.name, turn.config.model);
                context = turn.searchClient.search(turn.config.search, turn.query);
            } else if (SearchConfig.OFFICIAL.equals(turn.config.search.mode)) {
                context = turn.official.finish(false);
            }
            if (turn.cancelled || current != turn) {
                return;
            }
            report(context == null ? "正在请求自定义模型" : "已取得" + context.description() + "，正在生成回答",
                    turn.config.model);
            ModelClient.Reply reply = turn.client.complete(turn.config, turn.query, context);
            XposedBridge.log("RayNeoSwitch model response " + reply.summary());
            answer = reply.displayText();
            deliveryStatus = (reply.interruption().isEmpty() ? "自定义回答已发送到眼镜" : "模型生成中断，已发送当前回答及提示")
                    + " · " + reply.summary();
        } catch (Exception error) {
            if (turn.cancelled) {
                return;
            }
            answer = "问答失败，请在 RayNeo Switch 检查搜索和模型配置。";
            if (error instanceof IllegalStateException) {
                answer = error.getMessage();
            }
            deliveryStatus = "问答失败，错误提示已发送";
        }
        if (turn.cancelled || current != turn) {
            return;
        }
        String result = answer;
        String status = deliveryStatus;
        main.post(() -> deliver(turn, result, status));
    }

    private void deliver(Turn turn, String answer, String status) {
        if (turn.cancelled || current != turn || turn.delivered) {
            return;
        }
        try {
            Object result = nlpClass.getDeclaredConstructor().newInstance();
            set(result, "setSub", String.class, "llm");
            set(result, "setDomain", String.class, "chat");
            set(result, "setIntent", String.class, "chat");
            set(result, "setQuery", String.class, turn.query);
            set(result, "setAnswer", String.class, answer);
            set(result, "setSpoken", String.class, answer);
            set(result, "setSessionId", String.class, turn.sid);
            set(result, "setDialogId", String.class, turn.dialogId);
            set(result, "setRound", int.class, turn.round);
            set(result, "setFinished", boolean.class, true);
            set(result, "setHasNextRound", boolean.class, false);
            XposedBridge.invokeOriginalMethod(nlpMethod, turn.callback, new Object[]{result});
            XposedBridge.invokeOriginalMethod(completeMethod, turn.callback, new Object[0]);
            turn.delivered = true;
            report(status, turn.config.model);
        } catch (Throwable error) {
            XposedBridge.log("RayNeoSwitch delivery failed: " + error.getClass().getName());
            report("回答发送失败，请重新连接眼镜后测试", turn.config.model);
        }
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private void set(Object instance, String method, Class<?> type, Object value) throws Exception {
        nlpClass.getMethod(method, type).invoke(instance, value);
    }

    private void report(String message, String model) {
        lastMessage = message;
        lastModel = model;
        if (context == null) {
            return;
        }
        try {
            Bundle details = new Bundle();
            details.putString("message", message);
            details.putString("model", model);
            ConfigStore.Config config = activeConfig;
            SearchConfig search = config == null ? SearchConfig.builtin(SearchConfig.OFF) : config.search;
            details.putString("search_mode", search.mode);
            details.putString("search_name", search.name);
            context.getContentResolver().call(CONFIG_URI, "report_status", null, details);
            if (!accessPersisted) {
                context.getContentResolver().takePersistableUriPermission(CONFIG_URI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                accessPersisted = true;
            }
        } catch (RuntimeException error) {
            XposedBridge.log("RayNeoSwitch status unavailable: " + error.toString());
        }
    }
}
