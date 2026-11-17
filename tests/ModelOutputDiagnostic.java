package dev.rayneo.switcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;

/** Opt-in live comparison using a synthetic prompt and the saved model credentials. */
final class ModelOutputDiagnostic {
    static JSONArray run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            return compare(instrumentation, context);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private static JSONArray compare(Instrumentation instrumentation, Context context) throws Exception {
        ConfigStore.Config config = new ConfigStore(context).read();
        config.validate();
        String question = "请逐行输出从第01行到第50行，每行严格使用这个模板，只替换两位数字编号："
                + "第01行：这是一次长文本完整性检查。"
                + "必须输出全部50行，最后另起一行输出【完整性检查结束】。不要省略，不要加说明。";
        JSONArray results = new JSONArray();
        for (boolean capped : new boolean[]{true, false}) {
            JSONObject payload = new JSONObject().put("model", config.model).put("stream", false)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", "你是眼镜中的助手，请使用简洁的中文回答。"))
                            .put(new JSONObject().put("role", "user").put("content", question)));
            if (capped) {
                payload.put("max_tokens", 512);
            }
            if ("api.deepseek.com".equalsIgnoreCase(new URI(config.baseUrl).getHost())) {
                payload.put("thinking", new JSONObject().put("type", "disabled"));
            }
            long started = SystemClock.elapsedRealtime();
            ModelClient.Reply reply;
            if (capped) {
                JSONObject response = new JsonHttpClient().post(config.completionUrl(), config.apiKey,
                        payload, 90000, 1024 * 1024, "模型诊断接口");
                reply = new ModelClient.Reply(response);
            } else {
                reply = new ModelClient().complete(config, question, null);
            }
            String answer = reply.answer;
            JSONObject result = new JSONObject().put("requested_limit", capped ? 512 : JSONObject.NULL)
                    .put("finish_reason", reply.finishReason)
                    .put("characters", answer.length())
                    .put("completion_tokens", reply.completionTokens)
                    .put("reasoning_tokens", reply.reasoningTokens)
                    .put("has_last_line", answer.contains("第50行"))
                    .put("has_end_marker", answer.contains("【完整性检查结束】"))
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            results.put(result);
            Bundle progress = new Bundle();
            progress.putString("stream", result.toString(2) + "\n");
            instrumentation.sendStatus(0, progress);
        }
        return results;
    }
}
