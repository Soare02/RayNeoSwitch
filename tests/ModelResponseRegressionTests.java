package dev.rayneo.switcher;

import org.json.JSONArray;
import org.json.JSONObject;

final class ModelResponseRegressionTests {
    int run() throws Exception {
        longResponseHttp();
        interruptedResponses();
        legacyMetadata();
        invalidAndEmptyContent();
        return 4;
    }

    private void longResponseHttp() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            content.append("完整回答段落 ").append(index).append("，包含中文与😀。\n");
        }
        content.append("【完整回答末尾】");
        String answer = content.toString();
        try (SearchRegressionTests.HttpFixture server = new SearchRegressionTests.HttpFixture()) {
            server.modelResponse = response(answer, "stop").put("usage", new JSONObject()
                    .put("completion_tokens", 3456)
                    .put("completion_tokens_details", new JSONObject().put("reasoning_tokens", 125)));
            ConfigStore.Config config = new ConfigStore.Config(true, server.url("/v1"), "model", "fake-model-key");
            ModelClient.Reply reply = new ModelClient().complete(config, "长回答测试", null);
            check(reply.answer.equals(answer) && reply.displayText().endsWith("【完整回答末尾】"), "long model reply was truncated");
            check(!server.modelRequest.has("max_tokens") && !server.modelRequest.has("max_completion_tokens"),
                    "client still overrides the provider output limit");
            check(reply.completionTokens == 3456 && reply.reasoningTokens == 125, "token metadata was lost");
            check(reply.interruption().isEmpty() && reply.summary().contains("finish_reason=stop"), "normal stop was marked incomplete");
        }
    }

    private void interruptedResponses() throws Exception {
        for (String reason : new String[]{"length", "content_filter", "aborted", "insufficient_system_resource", "tool_calls"}) {
            ModelClient.Reply reply = new ModelClient.Reply(response("部分回答", reason));
            check(!reply.interruption().isEmpty(), "interrupted generation was not detected");
            check(reply.displayText().startsWith("部分回答\n\n【") && reply.displayText().endsWith("】"),
                    "partial answer or interruption notice missing");
            check(reply.summary().contains("finish_reason=" + reason), "finish reason metadata was lost");
        }
        try (SearchRegressionTests.HttpFixture server = new SearchRegressionTests.HttpFixture()) {
            server.modelResponse = response("未完成内容", "length");
            ConfigStore.Config config = new ConfigStore.Config(true, server.url("/v1"), "model", "");
            String answer = new ModelClient().ask(config, "测试");
            check(answer.contains("未完整生成") && answer.startsWith("未完成内容"), "legacy ask caller hid a length stop");
        }
    }

    private void legacyMetadata() throws Exception {
        JSONObject response = response("旧接口回答", "stop");
        JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
        choice.remove("finish_reason");
        ModelClient.Reply legacy = new ModelClient.Reply(response);
        check(legacy.displayText().equals("旧接口回答") && legacy.completionTokens == -1, "legacy reply behavior changed");
        choice.put("finish_reason", JSONObject.NULL);
        check(new ModelClient.Reply(response).finishReason.isEmpty(), "null finish reason rendered as text");
    }

    private void invalidAndEmptyContent() throws Exception {
        ModelClient.Reply empty = new ModelClient.Reply(response(JSONObject.NULL, "length"));
        try {
            empty.displayText();
            throw new AssertionError("empty length-limited output was accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("长度上限"), "empty limited response lost the cause");
        }
        try {
            new ModelClient.Reply(response(new JSONArray(), "stop"));
            throw new AssertionError("non-text model response was accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("格式无效"), "invalid response error was lost");
        }
        try {
            new ModelClient.Reply(response(" \n", "stop")).displayText();
            throw new AssertionError("blank model answer was accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("空回答"), "blank response error changed");
        }
    }

    private static JSONObject response(Object content, String reason) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", reason)
                .put("message", new JSONObject().put("content", content))));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
