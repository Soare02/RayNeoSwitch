package dev.rayneo.switcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

final class OfficialSearchCollector {
    private final SearchContext sources = new SearchContext("官方搜索返回资料");
    private final StringBuilder answer = new StringBuilder();
    private final String question;
    private boolean searched;
    private boolean finished;
    private String interruption = "";

    OfficialSearchCollector() {
        this("");
    }

    OfficialSearchCollector(String question) {
        this.question = question.trim();
    }

    synchronized void tool(String name) {
        if ("search_web".equals(name)) {
            searched = true;
        }
    }

    synchronized void answer(String text, boolean finished) {
        this.finished |= finished;
        if (text == null || text.isEmpty() || answer.length() >= 12000) {
            return;
        }
        String previous = answer.toString();
        if (text.length() > previous.length() && text.startsWith(previous)) {
            answer.setLength(0);
        } else if (finished && text.equals(previous)) {
            return;
        }
        answer.append(SearchConfig.limit(text, 12000 - answer.length()));
    }

    synchronized void metadata(String value) {
        if (value == null || value.length() > 128000) {
            return;
        }
        try {
            String text = value.trim();
            if (text.startsWith("{")) {
                JSONObject metadata = new JSONObject(text);
                String query = metadata.optString("query", "").trim();
                if (!question.isEmpty() && !query.isEmpty() && !question.equals(query)) {
                    return;
                }
                String event = metadata.optString("eventType", "");
                finished |= event.endsWith("FINISHED") || event.endsWith("COMPLETED");
                collect(metadata, 0);
            } else if (text.startsWith("[")) {
                collect(new JSONArray(text), 0);
            }
        } catch (Exception ignored) {
            // Status text and incomplete streamed JSON can arrive through the same callback.
        }
    }

    synchronized boolean ready() {
        return finished && (sources.size() > 0 || answer.length() > 0);
    }

    synchronized void complete() {
        finished = true;
    }

    synchronized void error(int code) {
        finished = true;
        interruption = code == 1008 ? "官方服务等待超时"
                : "官方服务返回错误（" + code + "）";
    }

    private void collect(Object value, int depth) throws Exception {
        if (depth > 6) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray items = (JSONArray) value;
            for (int index = 0; index < Math.min(items.length(), 50); index++) {
                collect(items.opt(index), depth + 1);
            }
        } else if (value instanceof JSONObject) {
            JSONObject item = (JSONObject) value;
            if (item.optLong("webResultCount", 0) > 0) {
                searched = true;
            }
            String url = item.optString("url", "");
            if (!url.isEmpty()) {
                String content = item.optString("content", "");
                if (content.isEmpty()) {
                    content = item.optString("summary", item.optString("snippet", ""));
                }
                sources.add(item.optString("title", ""), url, content);
            }
            Iterator<String> keys = item.keys();
            int count = 0;
            while (keys.hasNext() && count++ < 64) {
                String key = keys.next();
                // searchSummary contains display statistics in the observed voice protocol.
                if (!"searchSummary".equals(key)) {
                    collect(item.opt(key), depth + 1);
                }
            }
        } else if (value instanceof String && depth < 5) {
            String text = ((String) value).trim();
            if (text.startsWith("{") && text.length() <= 64000) {
                try {
                    collect(new JSONObject(text), depth + 1);
                } catch (org.json.JSONException ignored) {
                    // A quoted webpage excerpt can also begin with an opening brace.
                }
            }
        }
    }

    synchronized SearchContext finish(boolean timedOut) {
        SearchContext result = sources.copy();
        if (!sources.hasContent() && answer.length() > 0) {
            result.reference(searched ? "官方联网回答参考" : "官方回答参考（本轮搜索触发情况未确认）",
                    answer.toString());
        }
        if (sources.size() == 0 && answer.length() == 0) {
            if (!interruption.isEmpty()) {
                throw new IllegalStateException(interruption + "，本轮未取得可用资料，请重试");
            }
            throw new IllegalStateException(timedOut ? "等待官方搜索资料超时，请重试"
                    : "官方本轮未返回可用资料或回答，请重试");
        }
        if (!interruption.isEmpty()) {
            result.note(interruption + "，以下为官方结束前已取得的部分资料。");
        } else if (timedOut) {
            result.note("官方等待超时，以下为截至超时已取得的部分资料。");
        }
        return result;
    }
}
