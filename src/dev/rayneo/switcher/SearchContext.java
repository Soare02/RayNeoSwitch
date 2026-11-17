package dev.rayneo.switcher;

import java.util.LinkedHashMap;
import java.util.Map;

final class SearchContext {
    private final String name;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private String reference = "";
    private String referenceLabel = "";
    private String note = "";

    private static final class Source {
        final String title;
        final String url;
        final String content;

        Source(String title, String url, String content) {
            this.title = SearchConfig.limit(title, 240);
            this.url = url;
            this.content = SearchConfig.limit(content, 3000);
        }
    }

    SearchContext(String name) {
        this.name = name;
    }

    void add(String title, String url, String content) {
        try {
            url = SearchConfig.pageUrl(url);
            if (sources.size() >= 50 && !sources.containsKey(url)) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        Source previous = sources.get(url);
        if (previous == null || content.length() > previous.content.length()) {
            sources.put(url, new Source(title, url, content));
        }
    }

    void reference(String label, String text) {
        referenceLabel = label;
        reference = SearchConfig.limit(text, 12000);
    }

    void note(String value) {
        note = value;
    }

    int size() {
        return sources.size();
    }

    boolean hasContent() {
        for (Source source : sources.values()) {
            if (!source.content.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    SearchContext copy() {
        SearchContext copy = new SearchContext(name);
        copy.sources.putAll(sources);
        copy.reference = reference;
        copy.referenceLabel = referenceLabel;
        copy.note = note;
        return copy;
    }

    String description() {
        return reference.isEmpty() ? name + " · " + sources.size() + " 条来源" : referenceLabel;
    }

    String render() {
        StringBuilder text = new StringBuilder(name).append('\n');
        if (!note.isEmpty()) {
            text.append(note).append('\n');
        }
        int index = 0;
        for (Source source : sources.values()) {
            if (text.length() >= 20000) {
                break;
            }
            text.append('\n').append('[').append(++index).append("] ").append(source.title)
                    .append('\n').append(source.url).append('\n')
                    .append(source.content.isEmpty() ? "本来源仅提供标题和链接。" : source.content).append('\n');
        }
        if (!reference.isEmpty()) {
            text.append('\n').append(referenceLabel).append('\n').append(reference);
        }
        if (sources.isEmpty() && reference.isEmpty()) {
            text.append("本轮检索未找到可用资料，请说明检索结果为空，谨慎回答实时信息。");
        }
        return SearchConfig.limit(text.toString(), 24000);
    }
}
