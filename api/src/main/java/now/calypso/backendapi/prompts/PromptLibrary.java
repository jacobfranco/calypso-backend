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
    private static final String SIGNAL_TAG_PREFIX = "signal:";
    private static final String SILHOUETTE_TAG_PREFIX = "silhouette:";
    private static final List<String> SILHOUETTE_DOMAIN_PRIORITY = List.of(
            "formative_imprints",
            "aesthetic_field",
            "spark_archetypes",
            "social_belonging",
            "home_atmosphere",
            "humor_play",
            "sustainability_needs",
            "anti_patterns");

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
        defs.add(def("private.formative.imprints", PromptBankKind.PRIVATE,
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "private", null, "agent", "signal:media_title", "signal:place", "signal:nostalgia",
                "silhouette:formative_imprints"));
        defs.add(def("private.inner.weather", PromptBankKind.PRIVATE,
                "What fictional world, album, movie, game, visual style, or place feels closest to your inner weather? What does it feel like?",
                "private", null, "agent", "signal:media_title", "signal:aesthetic", "signal:music",
                "silhouette:aesthetic_field", "silhouette:formative_imprints"));
        defs.add(def("private.fictional.characters", PromptBankKind.PRIVATE,
                "Name up to 3 fictional characters you've felt drawn to romantically (from any medium - film, books, games, etc). "
                        + "What about each of them pulls you in? Name up to 3 fictional characters you relate to most.",
                "private", null, "agent", "signal:media_title", "signal:attraction",
                "silhouette:spark_archetypes"));
        defs.add(def("private.gravitational.pull", PromptBankKind.PRIVATE,
                "What kind of person has a weird gravitational pull for you, even if they are not your usual type? What is the pull?",
                "private", null, "agent", "signal:seeking_traits", "signal:attraction",
                "silhouette:spark_archetypes"));
        defs.add(def("private.color.presence", PromptBankKind.PRIVATE,
                "If your presence had a color in a room, what would it be? What color feels magnetic to you in someone else?",
                "private", null, "agent", "signal:aesthetic", "signal:social_energy",
                "silhouette:aesthetic_field", "silhouette:spark_archetypes"));
        defs.add(def("private.hobbies", PromptBankKind.PRIVATE,
                "What are your hobbies? Which hobbies would you like to share with your partner?",
                "private", null, "agent", "signal:hobbies", "signal:shared_activity",
                "silhouette:social_belonging"));
        defs.add(def("private.popular.dislike", PromptBankKind.PRIVATE,
                "What's something popular that you really don't like?",
                "private", null, "agent", "signal:dislike", "signal:boundary",
                "silhouette:anti_patterns"));
        defs.add(def("private.most.myself", PromptBankKind.PRIVATE,
                "I feel most like myself when...",
                "private", null, "agent", "signal:self_expression", "silhouette:home_atmosphere"));
        defs.add(def("private.political.issues", PromptBankKind.PRIVATE,
                "What political issues are important to you? Why those specifically?",
                "private", null, "agent", "signal:values", "signal:politics"));
        defs.add(def("private.not.my.person", PromptBankKind.PRIVATE,
                "What are some interests or lifestyles that would make you think 'not my person'?",
                "private", null, "agent", "signal:dislike", "signal:boundary",
                "silhouette:anti_patterns"));
        defs.add(def("private.drawn.to", PromptBankKind.PRIVATE,
                "Describe the kind of person you tend to be drawn to.",
                "private", null, "agent", "signal:seeking_traits", "signal:attraction",
                "silhouette:spark_archetypes", "silhouette:sustainability_needs"));
        defs.add(def("private.repair.rhythm", PromptBankKind.PRIVATE,
                "When something feels off with someone, what kind of response makes you feel safe, respected, or willing to stay in the conversation?",
                "private", null, "agent", "signal:communication", "signal:repair",
                "silhouette:sustainability_needs"));
        defs.add(def("private.music.feels.like", PromptBankKind.PRIVATE,
                "What music feels the most like you? Why?",
                "private", null, "agent", "signal:music", "signal:aesthetic",
                "silhouette:aesthetic_field"));
        defs.add(def("private.media.revisit", PromptBankKind.PRIVATE,
                "What are some pieces of media you can revisit endlessly?",
                "private", null, "agent", "signal:media_title", "signal:nostalgia",
                "silhouette:formative_imprints"));
        defs.add(def("private.makes.you.laugh", PromptBankKind.PRIVATE,
                "What kind of things reliably make you laugh?",
                "private", null, "agent", "signal:humor", "signal:social_energy",
                "silhouette:humor_play"));
        defs.add(def("private.humor.language", PromptBankKind.PRIVATE,
                "What kind of humor, teasing, bit, or running joke makes you feel instantly understood by someone?",
                "private", null, "agent", "signal:humor", "signal:social_energy",
                "silhouette:humor_play", "silhouette:spark_archetypes"));
        defs.add(def("private.rabbit.hole", PromptBankKind.PRIVATE,
                "What topic or niche have you spent way too much time exploring?",
                "private", null, "agent", "signal:curiosity", "signal:niche_interest"));
        defs.add(def("private.places.home", PromptBankKind.PRIVATE,
                "What kind of places feel like home to you?",
                "private", null, "agent", "signal:place", "signal:environment",
                "silhouette:home_atmosphere"));
        defs.add(def("private.home.texture", PromptBankKind.PRIVATE,
                "Describe a room, city, late-night setting, or kind of place that makes you feel most at home. What details matter?",
                "private", null, "agent", "signal:place", "signal:environment",
                "silhouette:home_atmosphere", "silhouette:aesthetic_field"));
        defs.add(def("private.great.night", PromptBankKind.PRIVATE,
                "On a great night with friends, what does it look like?",
                "private", null, "agent", "signal:social_energy", "signal:activity",
                "silhouette:social_belonging"));
        defs.add(def("private.visual.aesthetic", PromptBankKind.PRIVATE,
                "If your life had a visual aesthetic, what would it feel like?",
                "private", null, "agent", "signal:aesthetic", "silhouette:aesthetic_field"));
        defs.add(def("private.stuck.with", PromptBankKind.PRIVATE,
                "What's something you've stuck with for a long time?",
                "private", null, "agent", "signal:commitment", "signal:hobbies",
                "silhouette:sustainability_needs"));
        defs.add(def("private.communities.scene", PromptBankKind.PRIVATE,
                "What communities or scenes have you felt the most at home in?",
                "private", null, "agent", "signal:community", "signal:scene",
                "silhouette:social_belonging"));
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

    private static List<String> tagsWithPrefix(PromptDefinition def, String prefix) {
        if (def == null || prefix == null || prefix.isBlank() || !def.isSetTags() || def.getTags() == null) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String raw : def.getTags()) {
            if (raw == null) {
                continue;
            }
            String tag = raw.trim().toLowerCase(Locale.ROOT);
            if (tag.startsWith(prefix) && tag.length() > prefix.length()) {
                out.add(tag.substring(prefix.length()));
            }
        }
        return Collections.unmodifiableList(out);
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

    public static List<String> silhouetteDomainPriority() {
        return new ArrayList<>(SILHOUETTE_DOMAIN_PRIORITY);
    }

    public static List<String> silhouetteDomains(PromptDefinition def) {
        return tagsWithPrefix(def, SILHOUETTE_TAG_PREFIX);
    }

    public static List<String> signalDomains(PromptDefinition def) {
        return tagsWithPrefix(def, SIGNAL_TAG_PREFIX);
    }

    public static List<String> silhouetteDomainsForPromptId(String id) {
        return silhouetteDomains(BY_ID.get(id));
    }

    public static List<String> signalDomainsForPromptId(String id) {
        return signalDomains(BY_ID.get(id));
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

    public static String privateTextById(String id) {
        PromptDefinition def = BY_ID.get(id);
        if (def == null || def.getBank() != PromptBankKind.PRIVATE)
            return null;
        return def.getText();
    }

    public static boolean isPrivatePromptId(String id) {
        PromptDefinition def = BY_ID.get(id);
        return def != null && def.getBank() == PromptBankKind.PRIVATE;
    }
}
