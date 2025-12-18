package now.calypso.backendapi.prompts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import now.calypso.backend.data.PromptQuestion;

/**
 * System-provided prompts. These are centralized here so the backend and API
 * can reference the same canonical list.
 */
public final class PromptLibrary {

    private static final List<PromptQuestion> PROMPTS;
    private static final Map<String, PromptQuestion> BY_ID;

    static {
        List<PromptQuestion> tmp = new ArrayList<>();
        tmp.add(prompt("prompt.travel.memory",
                "What's a travel memory that still makes you smile?", "travel", "travel", "story"));
        tmp.add(prompt("prompt.weekend.vibes",
                "How do you usually spend a perfect Saturday?", "lifestyle", "weekend", "lifestyle"));
        tmp.add(prompt("prompt.values.spark",
                "What quality instantly gets your attention in a partner?", "values", "values"));
        tmp.add(prompt("prompt.food.combo",
                "What's an unusual food combo you swear by?", "food", "fun"));
        tmp.add(prompt("prompt.challenge",
                "What's something new you tried recently that surprised you?", "growth", "adventure"));
        tmp.add(prompt("prompt.music.throwback",
                "Which song instantly transports you back in time?", "music", "nostalgia"));
        PROMPTS = Collections.unmodifiableList(tmp);

        Map<String, PromptQuestion> byId = new HashMap<>();
        for (PromptQuestion q : PROMPTS) {
            byId.put(q.getPromptId(), q);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private PromptLibrary() {
    }

    private static PromptQuestion prompt(String id, String question, String topic, String... tags) {
        PromptQuestion q = new PromptQuestion();
        q.setPromptId(id);
        q.setQuestion(question);
        if (topic != null)
            q.setTopic(topic.toLowerCase(Locale.ROOT));
        if (tags != null && tags.length > 0)
            q.setTags(Arrays.asList(tags));
        return q;
    }

    private static PromptQuestion copy(PromptQuestion q) {
        return q == null ? null : new PromptQuestion(q);
    }

    public static List<PromptQuestion> all() {
        List<PromptQuestion> out = new ArrayList<>(PROMPTS.size());
        for (PromptQuestion q : PROMPTS) {
            out.add(copy(q));
        }
        return out;
    }

    public static PromptQuestion getById(String id) {
        return copy(BY_ID.get(id));
    }
}
