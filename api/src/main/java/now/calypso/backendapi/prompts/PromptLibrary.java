package now.calypso.backendapi.prompts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import now.calypso.backend.data.PromptBankKind;
import now.calypso.backend.data.PromptDefinition;

/**
 * System-provided prompts. These are centralized here so the backend and API
 * can reference the same canonical list.
 */
public final class PromptLibrary {

    private static final List<PromptDefinition> DEFINITIONS;
    private static final List<PromptDefinition> PUBLIC_PROMPTS;
    private static final List<PromptDefinition> PRIVATE_PROMPTS;
    private static final Map<String, PromptDefinition> BY_ID;

    static {
        List<PromptDefinition> defs = new ArrayList<>();
        defs.add(def("prompt.talk.hours", PromptBankKind.PUBLIC,
                "I could talk for hours about...", "personality", null, "conversation"));
        defs.add(def("prompt.life.goal", PromptBankKind.PUBLIC,
                "A life goal of mine...", "values", null, "goals"));
        defs.add(def("prompt.same.weird", PromptBankKind.PUBLIC,
                "We're the same type of weird if...", "personality", null, "quirks"));
        defs.add(def("prompt.gas.station", PromptBankKind.PUBLIC,
                "Gas station stop. What snacks are you grabbing?", "food", null, "fun"));
        defs.add(def("prompt.hill.die.on", PromptBankKind.PUBLIC,
                "A hill I will die on is...", "values", null, "opinions"));
        defs.add(def("prompt.controversial.opinion", PromptBankKind.PUBLIC,
                "My most controversial opinion is...", "values", null, "opinions"));
        defs.add(def("prompt.ideal.sunday", PromptBankKind.PUBLIC,
                "My ideal Sunday looks like...", "lifestyle", null, "weekend"));
        defs.add(def("prompt.no.compromise", PromptBankKind.PUBLIC,
                "A part of my day I don't compromise on...", "lifestyle", null, "habits"));
        defs.add(def("prompt.respect.people", PromptBankKind.PUBLIC,
                "I respect people who...", "values", null, "character"));
        defs.add(def("prompt.small.thing", PromptBankKind.PUBLIC,
                "A small thing that says a lot about someone...", "values", null, "observations"));
        defs.add(def("prompt.disappeared.year", PromptBankKind.PUBLIC,
                "If I disappeared for a year, I'd probably be...", "story", null, "adventure"));
        defs.add(def("prompt.rabbit.hole", PromptBankKind.PUBLIC,
                "A rabbit hole I've gone down recently...", "personality", null, "curiosity"));
        defs.add(def("private.fictional.characters", PromptBankKind.PRIVATE,
                "Name up to 3 fictional characters you've felt drawn to romantically (from any medium - film, books, games, etc). "
                        + "What about each of them pulls you in? Name up to 3 fictional characters you relate to most.",
                "private", null, "agent"));
        defs.add(def("private.color.presence", PromptBankKind.PRIVATE,
                "If your presence had a color in a room, what would it be? What color feels magnetic to you in someone else?",
                "private", null, "agent"));
        defs.add(def("private.hobbies", PromptBankKind.PRIVATE,
                "What are your hobbies? Which hobbies would you like to share with your partner?",
                "private", null, "agent"));
        defs.add(def("private.popular.dislike", PromptBankKind.PRIVATE,
                "What's something popular that you really don't like?",
                "private", null, "agent"));
        defs.add(def("private.most.myself", PromptBankKind.PRIVATE,
                "I feel most like myself when...",
                "private", null, "agent"));
        defs.add(def("private.political.issues", PromptBankKind.PRIVATE,
                "What political issues are important to you? Why those specifically?",
                "private", null, "agent"));
        defs.add(def("private.not.my.person", PromptBankKind.PRIVATE,
                "What are some interests or lifestyles that would make you think 'not my person'?",
                "private", null, "agent"));
        defs.add(def("private.drawn.to", PromptBankKind.PRIVATE,
                "Describe the kind of person you tend to be drawn to.",
                "private", null, "agent"));
        defs.add(def("private.music.feels.like", PromptBankKind.PRIVATE,
                "What music feels the most like you? Why?",
                "private", null, "agent"));
        defs.add(def("private.media.revisit", PromptBankKind.PRIVATE,
                "What are some pieces of media you can revisit endlessly?",
                "private", null, "agent"));
        defs.add(def("private.makes.you.laugh", PromptBankKind.PRIVATE,
                "What kind of things reliably make you laugh?",
                "private", null, "agent"));
        defs.add(def("private.rabbit.hole", PromptBankKind.PRIVATE,
                "What topic or niche have you spent way too much time exploring?",
                "private", null, "agent"));
        defs.add(def("private.places.home", PromptBankKind.PRIVATE,
                "What kind of places feel like home to you?",
                "private", null, "agent"));
        defs.add(def("private.great.night", PromptBankKind.PRIVATE,
                "On a great night with friends, what does it look like?",
                "private", null, "agent"));
        defs.add(def("private.visual.aesthetic", PromptBankKind.PRIVATE,
                "If your life had a visual aesthetic, what would it feel like?",
                "private", null, "agent"));
        defs.add(def("private.stuck.with", PromptBankKind.PRIVATE,
                "What's something you've stuck with for a long time?",
                "private", null, "agent"));
        defs.add(def("private.communities.scene", PromptBankKind.PRIVATE,
                "What communities or scene have you felt the most at home in?",
                "private", null, "agent"));
        defs.add(def("private.fascinating.people", PromptBankKind.PRIVATE,
                "Who are some people (historical or living) that you find fascinating? Why?",
                "private", null, "agent"));
        DEFINITIONS = Collections.unmodifiableList(defs);

        List<PromptDefinition> publicPrompts = new ArrayList<>();
        List<PromptDefinition> privatePrompts = new ArrayList<>();
        for (PromptDefinition def : DEFINITIONS) {
            if (def == null)
                continue;
            if (def.getBank() == PromptBankKind.PUBLIC) {
                publicPrompts.add(def);
            } else if (def.getBank() == PromptBankKind.PRIVATE) {
                privatePrompts.add(def);
            }
        }
        PUBLIC_PROMPTS = Collections.unmodifiableList(publicPrompts);
        PRIVATE_PROMPTS = Collections.unmodifiableList(privatePrompts);

        Map<String, PromptDefinition> byId = new HashMap<>();
        for (PromptDefinition def : DEFINITIONS) {
            if (def != null && def.getPromptId() != null) {
                byId.put(def.getPromptId(), def);
            }
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private PromptLibrary() {
    }

    private static PromptDefinition def(String id, PromptBankKind bank, String text, String topic,
            Integer version, String... tags) {
        List<String> tagList = (tags == null || tags.length == 0) ? null : Arrays.asList(tags);
        String normalizedTopic = topic == null ? null : topic.toLowerCase(Locale.ROOT);
        PromptDefinition def = new PromptDefinition();
        def.setPromptId(id);
        def.setBank(bank);
        def.setText(text);
        if (normalizedTopic != null)
            def.setTopic(normalizedTopic);
        if (tagList != null)
            def.setTags(tagList);
        if (version != null)
            def.setVersion(version);
        return def;
    }

    private static PromptDefinition copy(PromptDefinition def) {
        return def == null ? null : new PromptDefinition(def);
    }

    public static List<PromptDefinition> publicBank() {
        List<PromptDefinition> out = new ArrayList<>(PUBLIC_PROMPTS.size());
        for (PromptDefinition def : PUBLIC_PROMPTS) {
            out.add(copy(def));
        }
        return out;
    }

    public static List<PromptDefinition> privateBank() {
        List<PromptDefinition> out = new ArrayList<>(PRIVATE_PROMPTS.size());
        for (PromptDefinition def : PRIVATE_PROMPTS) {
            out.add(copy(def));
        }
        return out;
    }

    public static PromptDefinition getById(String id) {
        return copy(BY_ID.get(id));
    }

    public static String publicTextById(String id) {
        PromptDefinition def = BY_ID.get(id);
        if (def == null || def.getBank() != PromptBankKind.PUBLIC)
            return null;
        return def.getText();
    }

    public static boolean isPublicPromptId(String id) {
        PromptDefinition def = BY_ID.get(id);
        return def != null && def.getBank() == PromptBankKind.PUBLIC;
    }
}
