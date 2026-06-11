package now.calypso.backendapi.matchstandards;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import now.calypso.backend.data.MatchStandardAnswerSet;
import now.calypso.backend.data.MatchStandardOption;
import now.calypso.backend.data.MatchStandardQuestion;
import now.calypso.backend.data.MatchStandardQuestionAnswerType;

public final class MatchStandardQuestionLibrary {
    public static final String TAG_STARTER = "starter";

    private static final int VERSION = 1;
    private static final List<MatchStandardQuestion> QUESTIONS = buildQuestions();
    private static final Map<String, MatchStandardQuestion> BY_ID = indexById(QUESTIONS);

    private MatchStandardQuestionLibrary() {
    }

    public static List<MatchStandardQuestion> all() {
        return QUESTIONS.stream().map(MatchStandardQuestion::new).toList();
    }

    public static MatchStandardQuestion getById(String questionId) {
        if (questionId == null) {
            return null;
        }
        MatchStandardQuestion question = BY_ID.get(questionId.trim());
        return question == null ? null : new MatchStandardQuestion(question);
    }

    public static MatchStandardQuestion nextQuestion(MatchStandardAnswerSet answers, String rawCategory, boolean starterOnly) {
        Set<String> answered = new LinkedHashSet<>();
        if (answers != null && answers.isSetAnswers() && answers.getAnswers() != null) {
            answers.getAnswers().forEach(answer -> {
                if (answer != null && answer.isSetQuestionId() && answer.getQuestionId() != null) {
                    answered.add(answer.getQuestionId());
                }
            });
        }
        String category = normalize(rawCategory);
        for (MatchStandardQuestion question : QUESTIONS) {
            if (!matches(question, category, starterOnly)) {
                continue;
            }
            if (!answered.contains(question.getQuestionId())) {
                return new MatchStandardQuestion(question);
            }
        }
        return null;
    }

    private static boolean matches(MatchStandardQuestion question, String category, boolean starterOnly) {
        if (question == null) {
            return false;
        }
        if (starterOnly && !hasTag(question, TAG_STARTER)) {
            return false;
        }
        return category == null || category.equals(normalize(question.getCategory()));
    }

    private static boolean hasTag(MatchStandardQuestion question, String tag) {
        if (question == null || !question.isSetTags() || question.getTags() == null) {
            return false;
        }
        return question.getTags().contains(tag);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, MatchStandardQuestion> indexById(List<MatchStandardQuestion> questions) {
        LinkedHashMap<String, MatchStandardQuestion> out = new LinkedHashMap<>();
        for (MatchStandardQuestion question : questions) {
            if (question == null || question.getQuestionId() == null || question.getQuestionId().isBlank()) {
                continue;
            }
            if (out.put(question.getQuestionId(), question) != null) {
                throw new IllegalStateException("Duplicate match standard question id: " + question.getQuestionId());
            }
            LinkedHashSet<String> optionIds = new LinkedHashSet<>();
            for (MatchStandardOption option : question.getOptions()) {
                if (!optionIds.add(option.getOptionId())) {
                    throw new IllegalStateException("Duplicate option id for " + question.getQuestionId() + ": "
                            + option.getOptionId());
                }
            }
        }
        return Map.copyOf(out);
    }

    private static List<MatchStandardQuestion> buildQuestions() {
        ArrayList<MatchStandardQuestion> out = new ArrayList<>();

        out.add(single("standard.values.politics", "values_worldview", "How would you describe your politics?",
                starter(),
                opt("left", "Left"),
                opt("center_left", "Center-left"),
                opt("center", "Center"),
                opt("center_right", "Center-right"),
                opt("right", "Right"),
                opt("apolitical", "Not very political")));
        out.add(single("standard.values.worldview_role", "values_worldview", "How much should shared worldview matter in a relationship?",
                none(),
                opt("core", "It should be a core alignment"),
                opt("some", "Some shared ground matters"),
                opt("little", "It matters less than day-to-day fit"),
                opt("open", "I am open across worldviews")));
        out.add(single("standard.religion.identity", "religion_spirituality", "How would you describe your religion or spirituality?",
                starter(),
                opt("christian", "Christian"),
                opt("muslim", "Muslim"),
                opt("hindu", "Hindu"),
                opt("buddhist", "Buddhist"),
                opt("jewish", "Jewish"),
                opt("sikh", "Sikh"),
                opt("spiritual", "Spiritual but not religious"),
                opt("atheist", "Atheist"),
                opt("agnostic", "Agnostic"),
                opt("secular_humanist", "Secular humanist"),
                opt("taoist", "Taoist"),
                opt("shinto", "Shinto"),
                opt("bahai", "Baha'i"),
                opt("jain", "Jain"),
                opt("indigenous", "Indigenous belief"),
                opt("pagan", "Pagan"),
                opt("zoroastrian", "Zoroastrian"),
                opt("rastafarian", "Rastafarian"),
                opt("custom_belief", "Something else"),
                opt("prefer_not_to_say", "Prefer not to say")));
        out.add(single("standard.religion.practice", "religion_spirituality", "How much does religious practice shape your life?",
                none(),
                opt("central", "It is central"),
                opt("regular", "It is regular but flexible"),
                opt("occasional", "Mostly holidays or moments"),
                opt("not_part", "It is not part of my life")));
        out.add(multi("standard.kids.future", "kids_family", "What best describes you around kids?",
                starter(),
                opt("has_kids", "I have kids"),
                opt("no_kids", "I do not have kids"),
                opt("wants_kids", "I want kids"),
                opt("open_to_kids", "I am open to kids"),
                opt("not_sure", "I am not sure yet"),
                opt("doesnt_want_kids", "I do not want kids")));
        out.add(single("standard.kids.current", "kids_family", "How do you feel about dating someone who already has kids?",
                none(),
                opt("comfortable", "Comfortable"),
                opt("open_context", "Open, depending on the situation"),
                opt("prefer_no", "I prefer not to"),
                opt("not_for_me", "That is not for me")));
        out.add(single("standard.kids.co_parenting", "kids_family", "If kids are involved, what kind of home rhythm feels right?",
                none(),
                opt("structured", "Structured and planned"),
                opt("flexible", "Flexible but steady"),
                opt("shared_family", "Very family-integrated"),
                opt("independent", "Independent adult relationship first")));
        out.add(single("standard.substances.alcohol", "substances", "What is your relationship with alcohol?",
                starter(),
                opt("non_drinker", "I do not drink"),
                opt("rare", "Rarely"),
                opt("social", "Socially"),
                opt("regular", "Regularly")));
        out.add(single("standard.substances.smoking", "substances", "What is your relationship with smoking or vaping?",
                starter(),
                opt("none", "I do not smoke or vape"),
                opt("rare", "Rarely"),
                opt("vape", "I vape"),
                opt("smoke", "I smoke")));
        out.add(single("standard.substances.cannabis", "substances", "What is your relationship with cannabis?",
                starter(),
                opt("no_cannabis", "I do not use cannabis"),
                opt("cannabis_ok", "Cannabis is okay"),
                opt("occasional_cannabis", "I use cannabis occasionally"),
                opt("regular_cannabis", "I use cannabis regularly")));
        out.add(single("standard.substances.drugs", "substances", "What is your relationship with recreational drugs other than cannabis?",
                starter(),
                opt("no_drugs", "I do not use recreational drugs"),
                opt("rare_recreational_drugs", "Rarely"),
                opt("psychedelics_user", "Psychedelics only"),
                opt("recreational_drugs", "Occasional recreational use")));
        out.add(single("standard.money.ambition", "money_ambition", "What kind of ambition rhythm fits you best?",
                none(),
                opt("high_drive", "High-drive and goal-focused"),
                opt("steady", "Steady and balanced"),
                opt("creative", "Creative or nontraditional path"),
                opt("low_pressure", "Low-pressure lifestyle first")));
        out.add(single("standard.money.spending", "money_ambition", "How do you tend to handle spending?",
                none(),
                opt("saver", "Saver"),
                opt("planner", "Planner with room for treats"),
                opt("spender", "Spender when life calls for it"),
                opt("fluid", "I keep it fluid")));
        out.add(single("standard.money.partner", "money_ambition", "What financial dynamic would feel best with a partner?",
                none(),
                opt("transparent", "Transparent and planned together"),
                opt("independent", "Mostly independent"),
                opt("flexible", "Flexible and case-by-case"),
                opt("traditional", "More traditional roles")));

        out.add(single("standard.conflict.cooldown", "conflict_repair", "After an argument, what usually helps you reconnect?",
                none(),
                opt("talk_now", "Talk it through right away"),
                opt("short_space", "Take a little space, then talk"),
                opt("sleep_on_it", "Sleep on it first"),
                opt("avoid_heavy", "Avoid heavy conflict when possible")));
        out.add(single("standard.conflict.style", "conflict_repair", "In conflict, what do you most need from a partner?",
                none(),
                opt("directness", "Directness"),
                opt("gentleness", "Gentleness"),
                opt("patience", "Patience"),
                opt("accountability", "Accountability")));
        out.add(single("standard.conflict.apology", "conflict_repair", "What makes an apology feel real to you?",
                none(),
                opt("words", "Clear words"),
                opt("changed_behavior", "Changed behavior"),
                opt("repair_action", "A repair action"),
                opt("time", "Time and consistency")));
        out.add(single("standard.conflict.frequency", "conflict_repair", "What conflict frequency feels normal to you?",
                none(),
                opt("rare", "Rare disagreement"),
                opt("occasional", "Occasional direct talks"),
                opt("active", "Active debate is okay"),
                opt("passionate", "Passionate conflict can be normal")));
        out.add(single("standard.conflict.texting", "conflict_repair", "How do you prefer to handle tense topics over text?",
                none(),
                opt("avoid_text", "Avoid text for tense topics"),
                opt("brief_then_call", "Brief text, then call or meet"),
                opt("text_ok", "Texting it out is okay"),
                opt("need_time", "I need time before responding")));

        out.add(single("standard.affection.style", "affection_intimacy", "How do you naturally show affection?",
                none(),
                opt("words", "Words"),
                opt("touch", "Physical affection"),
                opt("acts", "Acts of service"),
                opt("time", "Quality time"),
                opt("gifts", "Thoughtful gifts")));
        out.add(single("standard.affection.public", "affection_intimacy", "How do you feel about affection in public?",
                none(),
                opt("comfortable", "Comfortable"),
                opt("small_doses", "Small doses"),
                opt("private", "Mostly private"),
                opt("depends", "Depends on the setting")));
        out.add(single("standard.affection.reassurance", "affection_intimacy", "How much reassurance do you like in dating?",
                none(),
                opt("lots", "A lot"),
                opt("regular", "Regular but not constant"),
                opt("subtle", "Subtle signs are enough"),
                opt("independent", "I am pretty independent")));
        out.add(single("standard.affection.pace", "affection_intimacy", "What pace feels best for emotional intimacy?",
                none(),
                opt("slow", "Slow and gradual"),
                opt("steady", "Steady and intentional"),
                opt("fast", "Fast when it feels right"),
                opt("context", "Depends on the person")));

        out.add(single("standard.social.weekend", "social_rhythm", "What weekend rhythm feels most like you?",
                none(),
                opt("homebody", "Mostly home"),
                opt("one_plan", "One good plan"),
                opt("social", "Social and active"),
                opt("spontaneous", "Spontaneous")));
        out.add(single("standard.social.friends", "social_rhythm", "How integrated should friend groups become?",
                none(),
                opt("very", "Very integrated"),
                opt("some", "Some overlap"),
                opt("separate", "Mostly separate"),
                opt("flexible", "Flexible")));
        out.add(single("standard.social.alone_time", "social_rhythm", "How much alone time do you need?",
                none(),
                opt("lots", "A lot"),
                opt("regular", "Regular alone time"),
                opt("little", "A little"),
                opt("mostly_together", "I prefer lots of togetherness")));
        out.add(single("standard.social.planning", "social_rhythm", "How do you like plans to happen?",
                none(),
                opt("planned", "Planned ahead"),
                opt("mixed", "A mix"),
                opt("spontaneous", "Spontaneous"),
                opt("partner_leads", "I like when the other person leads")));
        out.add(single("standard.social.nightlife", "social_rhythm", "What nightlife rhythm fits you?",
                none(),
                opt("none", "Not my thing"),
                opt("rare", "Rarely"),
                opt("sometimes", "Sometimes"),
                opt("often", "Often")));

        out.add(single("standard.home.cleanliness", "home_lifestyle", "What home cleanliness rhythm feels livable?",
                none(),
                opt("very_tidy", "Very tidy"),
                opt("generally_tidy", "Generally tidy"),
                opt("lived_in", "Lived-in is fine"),
                opt("chaotic", "I am comfortable with chaos")));
        out.add(single("standard.home.sleep", "home_lifestyle", "What sleep rhythm sounds most like you?",
                none(),
                opt("early", "Early to bed, early to rise"),
                opt("normal", "Pretty standard"),
                opt("night_owl", "Night owl"),
                opt("variable", "Variable")));
        out.add(single("standard.home.food", "home_lifestyle", "How important is food lifestyle alignment?",
                none(),
                opt("very", "Very important"),
                opt("some", "Somewhat important"),
                opt("little", "Not very important"),
                opt("adventurous", "I like trying different lifestyles")));
        out.add(single("standard.home.pets", "home_lifestyle", "How do you feel about pets being part of the home?",
                none(),
                opt("must_love", "Must love pets"),
                opt("open", "Open to pets"),
                opt("prefer_no", "Prefer no pets"),
                opt("allergies", "Limited by allergies or logistics")));
        out.add(single("standard.home.health", "home_lifestyle", "What health rhythm best fits you?",
                none(),
                opt("fitness_core", "Fitness is central"),
                opt("active", "Generally active"),
                opt("balanced", "Balanced and flexible"),
                opt("low_key", "Low-key")));

        out.add(single("standard.relationship.exclusivity", "relationship_behavior", "What dating structure are you looking for?",
                none(),
                opt("monogamous", "Monogamous"),
                opt("open_to_open", "Open to non-monogamy"),
                opt("non_monogamous", "Non-monogamous"),
                opt("unsure", "Unsure")));
        out.add(single("standard.relationship.communication", "relationship_behavior", "What communication cadence feels good while dating?",
                none(),
                opt("daily", "Daily contact"),
                opt("most_days", "Most days"),
                opt("few_times", "A few times a week"),
                opt("flexible", "Flexible")));
        out.add(single("standard.relationship.independence", "relationship_behavior", "How much independence should a relationship preserve?",
                none(),
                opt("high", "A lot"),
                opt("balanced", "Balanced"),
                opt("together", "Lots of togetherness"),
                opt("context", "Depends on life stage")));
        out.add(single("standard.relationship.jealousy", "relationship_behavior", "How should partners handle jealousy?",
                none(),
                opt("talk_openly", "Talk openly"),
                opt("reassure", "Reassure each other"),
                opt("boundaries", "Set clear boundaries"),
                opt("self_manage", "Mostly self-manage")));
        out.add(single("standard.relationship.long_distance", "relationship_behavior", "How open are you to distance?",
                none(),
                opt("no", "Not open"),
                opt("short_term", "Short-term only"),
                opt("open_right_person", "Open for the right person"),
                opt("comfortable", "Comfortable with distance")));

        out.add(multi("standard.values.causes", "values_worldview", "Which causes or values would you want a partner to respect?",
                none(),
                opt("equity", "Equity and justice"),
                opt("environment", "Environment"),
                opt("faith", "Faith or tradition"),
                opt("family", "Family"),
                opt("career", "Career"),
                opt("personal_freedom", "Personal freedom")));
        out.add(multi("standard.social.activities", "social_rhythm", "Which shared social activities sound appealing?",
                none(),
                opt("dinners", "Dinners"),
                opt("bars", "Bars"),
                opt("concerts", "Concerts"),
                opt("game_nights", "Game nights"),
                opt("outdoors", "Outdoors"),
                opt("quiet_nights", "Quiet nights")));
        out.add(multi("standard.home.chores", "home_lifestyle", "Which household rhythms matter to you?",
                none(),
                opt("shared_chores", "Shared chores"),
                opt("cooking", "Cooking at home"),
                opt("quiet_home", "Quiet home"),
                opt("hosting", "Hosting people"),
                opt("decor", "Making the space feel intentional")));
        out.add(multi("standard.affection.needs", "affection_intimacy", "Which forms of closeness matter most to you?",
                none(),
                opt("verbal", "Verbal affection"),
                opt("physical", "Physical closeness"),
                opt("emotional", "Emotional depth"),
                opt("practical", "Practical support"),
                opt("playful", "Playfulness")));
        out.add(multi("standard.relationship.boundaries", "relationship_behavior", "Which boundaries feel important in a relationship?",
                none(),
                opt("phone_privacy", "Phone/privacy respect"),
                opt("friend_time", "Independent friend time"),
                opt("family_limits", "Family limits"),
                opt("exes", "Clarity around exes"),
                opt("work_time", "Protected work time")));

        return List.copyOf(out);
    }

    private static MatchStandardQuestion single(String id, String category, String text, List<String> tags,
            MatchStandardOption... options) {
        return question(id, category, text, MatchStandardQuestionAnswerType.SINGLE_CHOICE, tags, options);
    }

    private static MatchStandardQuestion multi(String id, String category, String text, List<String> tags,
            MatchStandardOption... options) {
        return question(id, category, text, MatchStandardQuestionAnswerType.MULTI_CHOICE, tags, options);
    }

    private static MatchStandardQuestion question(String id, String category, String text,
            MatchStandardQuestionAnswerType type, List<String> tags, MatchStandardOption... options) {
        MatchStandardQuestion question = new MatchStandardQuestion(id, category, text, type, List.of(options));
        question.setVersion(VERSION);
        if (tags != null && !tags.isEmpty()) {
            question.setTags(tags);
        }
        return question;
    }

    private static MatchStandardOption opt(String id, String text) {
        return new MatchStandardOption(id, text);
    }

    private static List<String> starter() {
        return List.of(TAG_STARTER);
    }

    private static List<String> none() {
        return List.of();
    }
}
