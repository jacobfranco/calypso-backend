package now.calypso.backendapi;

import now.calypso.backend.*;
import now.calypso.backend.data.ActivePrivatePrompt;
import now.calypso.backend.data.MatchStandardAnswer;
import now.calypso.backend.data.Importance;
import now.calypso.backend.data.LocationFilter;
import now.calypso.backend.data.LocationScope;
import now.calypso.backend.data.ModeFilter;
import now.calypso.backend.data.OneToManyFilter;
import now.calypso.backend.data.PublicPromptAnswer;
import now.calypso.backend.data.RangeFilter;
import now.calypso.backend.modules.*;
import now.calypso.backend.serialization.CalypsoSerialization;
import now.calypso.backendapi.llm.OpenAIJson;
import now.calypso.backendapi.pojos.PostAccount;
import now.calypso.backendapi.pojos.PostFilters;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.rpl.rama.*;
import com.rpl.rama.test.*;

import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CalypsoApiApplication {
    private static final List<String> SEED_PROMPT_IDS = List.of(
            "prompt.talk.hours",
            "prompt.ideal.sunday",
            "prompt.life.goal");

    private static final String[] SEED_NAMES = {
            "Avery", "Bhavya", "Chantelle", "Daria", "Eva", "Farah"
    };

    private static final String[] SEED_PHONES = {
            "+11111111111", "+11111111112", "+11111111113",
            "+11111111114", "+11111111115", "+11111111116"
    };

    private static final String[] SEED_MALE_NAMES = {
            "George", "Harold", "Ibrahim", "Kai"
    };

    private static final String[] SEED_MALE_PHONES = {
            "+11111111201", "+11111111202", "+11111111203", "+11111111204"
    };

    private static final String[] SEED_MODES = {
            "balanced", "exploratory", "exploratory", "exploratory", "balanced", "exploratory"
    };

    private static final String[] SEED_MALE_MODES = {
            "exploratory", "balanced", "exploratory", "balanced"
    };

    private static final String[] SEED_TALK_ANSWERS = {
            "my favorite romance novels !",
            "How much I love cooking",
            "high fashion",
            "gym science and nutrition",
            "All of the birds that are local in my area",
            "my favorite traveling adventures"
    };

    private static final String[] SEED_SUNDAY_ANSWERS = {
            "pilates and brunch with the girls !!",
            "A nice cozy day with a book and a homemade meal",
            "a morning gym session and relaxing the rest of the day",
            "watching the panthers and some casual gaming",
            "A sunrise hike and working on some of my side projects",
            "relaxing in bed all day"
    };

    private static final String[] SEED_GOAL_ANSWERS = {
            "travel the world !",
            "Getting my PhD",
            "doing a runway show",
            "going to the World Cup",
            "Launching a startup",
            "go clubbing in Amsterdam"
    };

    private static final String[] SEED_MALE_TALK_ANSWERS = {
            "old RPGs, architecture, and strange bits of history",
            "street food, soccer tactics, and travel plans",
            "synth music, bouldering, and design tools",
            "film cameras, bookstores, and urban planning"
    };

    private static final String[] SEED_MALE_SUNDAY_ANSWERS = {
            "a long coffee walk, pickup soccer, and cooking dinner",
            "museum wandering and trying a new restaurant",
            "gym early, reading outside, then a movie",
            "farmers market, records, and making pasta"
    };

    private static final String[] SEED_MALE_GOAL_ANSWERS = {
            "build a studio that funds creative projects",
            "live in a few countries and get fluent in Spanish",
            "run a marathon and write a small game",
            "start a nonprofit for local arts education"
    };

    private static final String[] SEED_POLITICS = {
            "liberal", "center", "liberal", "apolitical", "center", "apolitical"
    };

    private static final String[] SEED_RELIGIONS = {
            "spiritual", "spiritual", "agnostic", "agnostic", "secular_humanist", "christian"
    };

    private static final List<List<String>> SEED_LIFESTYLE_SELF = List.of(
            List.of("no_kids", "open_to_kids", "social_drinker", "non_smoker", "no_drugs"),
            List.of("no_kids", "wants_kids", "non_drinker", "non_smoker", "no_drugs"),
            List.of("no_kids", "open_to_kids", "social_drinker", "non_smoker", "no_drugs"),
            List.of("no_kids", "open_to_kids", "social_drinker", "non_smoker", "cannabis_user"),
            List.of("no_kids", "open_to_kids", "non_drinker", "non_smoker", "no_drugs"),
            List.of("no_kids", "doesnt_want_kids", "regular_drinker", "non_smoker", "cannabis_user"));

    private static final String IPC_SEED_SIGNAL_EXTRACTION_ENV = "CALYPSO_IPC_SEED_SIGNAL_EXTRACTION";
    private static final int IPC_SEED_CROSS_GENDER_PROMPT_REACTION_COUNT = 10;
    private static final int[] IPC_SEED_PROMPT_REACTION_STRENGTHS = {
            1, 2, 1, -1, 3, 1, 2, -2, 1, -1
    };
    private static final int IPC_SEED_TIMEOUT_SECONDS = 12;
    private static final int IPC_RICH_SILHOUETTE_TIMEOUT_SECONDS = 60;
    private static final long IPC_SEED_ACCOUNT_LOOKUP_TIMEOUT_MS = 12_000L;
    private static final int IPC_SEED_POST_RETRIES = 3;
    private static final String DARIA_SEED_PHONE = "+11111111114";
    private static final String JACOB_SEED_PHONE = "7046890319";
    private static final String JACOB_FORMATIVE_PROMPT_ID = "private.formative.imprints";
    private static final List<String> JACOB_FORMATIVE_MESSAGES = List.of(
            "I'd say like okami and katamari damacy.  also like this game called where in the world is carmen sandiego, treasures of knowledge.  also johnny quest, and then also like dbz and vintage anime like yu yu hakusho.  then like bugdom and nanosaur and those old computer games.",
            "i'd say like for okami and katamari and the animes it was just an exposure to japanese / asian aesthetics early which i guess influenced me later.  also like the carmen sandiego game made me as a kid think international travel was easy and like happened all the time, so i like knew when i was a kid i wanted to do that just to see the world.  also like i wanted to be a secret agent bc of that too, also bc of johnny quest.  lowkey also tintin in there too.  also lowkey the karate kid with jaden smith too made me really want to go to china and it also made me attracted to the female lead in that movie bc when i was a kid i had a crush on her and as an adult i think she's like totaly my type lol at least physically.",
            "also yeah lady gaga i think in the avenue of music esp bc of the karate kid movie that was pretty formative");
    private static final List<SeedPrivatePromptAnswerSpec> JACOB_RICH_PRIVATE_PROMPTS = List.of(
            seedPrivatePrompt(
                    JACOB_FORMATIVE_PROMPT_ID,
                    JACOB_FORMATIVE_MESSAGES),
            seedPrivatePrompt(
                    "private.fictional.characters",
                    List.of(
                            "lowkey the fictional people i tend to remember are like spike spiegel, san from princess mononoke, lupin, and motoko kusanagi.  i think i like competent people who still feel a little strange or hard to pin down.",
                            "i also like characters who have motion to them.  not necessarily loud, but like they have a world they could pull you into, whether that's music, travel, a city, a mystery, or some niche obsession.",
                            "i don't think i like cynical characters as much.  i like when there is wonder and style, but still some emotional sincerity underneath it.")),
            seedPrivatePrompt(
                    "private.drawn.to",
                    List.of(
                            "i'm usually drawn to women who feel like they have a real sense of motion to them.  like curious, worldly, maybe a little artistic, and not afraid to have a weird specific interest.",
                            "the specific interest could be anything honestly.  ceramics, dance, photography, languages, old buildings, fashion history, music scenes, whatever.  i just like when the interest feels lived-in.",
                            "also travel curiosity is big.  i like someone who feels like they would actually want to go see things and not just talk about it as an aesthetic.",
                            "i think i respond to generosity too.  someone who can invite me into their world without making it feel like a test.")),
            seedPrivatePrompt(
                    "private.repair.rhythm",
                    List.of(
                            "i think repair-wise i do best when someone can just talk plainly.  like if something is wrong, say the thing, and then we can actually work with it.",
                            "i can definitely overthink, so specifics help me a lot.  what happened, what landed badly, what do you want different next time.  that kind of thing.",
                            "i really do not like vague punishment or guessing games.  i can handle directness way better than someone acting cold and making me decode the whole situation.",
                            "also after repair i like when the playfulness can come back.  not forced, but like ok we talked about it and we still like each other.")));
    private static final List<SeedPrivatePromptAnswerSpec> DARIA_RICH_PRIVATE_PROMPTS = List.of(
            seedPrivatePrompt(
                    "private.hobbies",
                    List.of(
                            "i'd say my hobbies are like gym programming, lifting notes, nutrition rabbit holes, and casual gaming.  not in a super intense influencer way, more like i like testing what works and tracking it.",
                            "i also get really into sports analysis, especially soccer and the panthers.  i like understanding why a play worked or why a team shape changed.",
                            "but i do not want everything to feel like homework.  the fun part is having a plan, trying it, and then making it social or playful instead of precious.")),
            seedPrivatePrompt(
                    "private.great.night",
                    List.of(
                            "a great night for me is probably a panthers game or a world cup watch party first.  like loud shared energy, people yelling at the tv, snacks everywhere, everyone way too invested.",
                            "then after that i like the night getting smaller.  maybe couch co-op, maybe a strategy game, maybe just talking about the game and making jokes about the ridiculous moments.",
                            "i think i like when someone can do both speeds.  be in the crowd energy with me, then also be normal and cozy when it gets quiet.")),
            seedPrivatePrompt(
                    "private.drawn.to",
                    List.of(
                            "i think i'm drawn to someone who is passionate in a specific way.  like they can explain their weird favorite thing and i can tell they actually care, not that they're performing it.",
                            "travel curiosity is also very attractive to me.  not necessarily someone who has been everywhere, but someone who would actually want to go and be curious when they get there.",
                            "and i like nerdy interests when the person is not embarrassed by them.  if someone has a niche game or anime or history thing and can invite me into it, that's cute.",
                            "i do not love when someone makes their interests feel like a test.  it should feel generous, not like i'm being quizzed.")),
            seedPrivatePrompt(
                    "private.repair.rhythm",
                    List.of(
                            "when things get tense i like direct but calm.  i don't need it to be solved in five seconds, but i do want us to name what actually happened.",
                            "i can sleep on something or take space if needed, but i want there to be a real return point.  like ok, tomorrow after work we talk about it with specifics.",
                            "i really dislike passive-aggressive tests.  also pretending nothing happened is not it for me, because then i just feel like i'm waiting for the same thing to come back later.",
                            "the best repair for me is calm, specific, and still kind.  i don't need perfect wording, i just need honesty and follow-through.")));

    public static void main(String[] args) throws NoSuchAlgorithmException, IOException, NoSuchProviderException {
        if (args.length > 1) {
            CalypsoConfig.API_URL = args[1];
            CalypsoConfig.API_WEB_SOCKET_URL = args[2];
            CalypsoConfig.API_DOMAIN = args[3];
            CalypsoConfig.FRONTEND_URL = args[4];
        }

        // init s3
        try {
            CalypsoApiHelpers.initS3Client();
        } catch (SdkClientException e) {
            e.printStackTrace();
            CalypsoApiConfig.S3_OPTIONS = null;
        }

        // Build openAI client

        OpenAIClient openAI = OpenAIOkHttpClient.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();

        // init cluster manager
        if (args.length > 0) {
            CalypsoApiController.manager = new CalypsoApiManager(RamaClusterManager.openInternal(new HashMap() {
                {
                    put("conductor.host", args[0]);
                    put("custom.serializations",
                            Arrays.asList("now.calypso.backend.serialization.CalypsoSerialization"));
                }
            }), openAI);
        } else
            initIPC();

        // init spring
        SpringApplication.run(CalypsoApiApplication.class, args);

    }

    public static InProcessCluster initIPC() throws NoSuchAlgorithmException, IOException, NoSuchProviderException {
        List<Class> sers = new ArrayList<>();
        sers.add(CalypsoSerialization.class);
        InProcessCluster ipc = InProcessCluster.create(sers);

        Core coreModule = new Core();
        String coreModuleName = Core.class.getName();
        LaunchConfig coreConfig = new LaunchConfig(2, 2);
        coreConfig.numWorkers(2);
        ipc.launchModule(coreModule, coreConfig);

        Agent agentModule = new Agent();
        LaunchConfig agentConfig = new LaunchConfig(2, 2);
        agentConfig.numWorkers(2);
        ipc.launchModule(agentModule, agentConfig);

        // Build openAI Client
        OpenAIClient openAI = OpenAIOkHttpClient.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();

        CalypsoApiController.manager = new CalypsoApiManager(ipc, openAI);
        String seedToggle = System.getenv("CALYPSO_IPC_SEED");
        boolean seedEnabled = seedToggle == null
                || (!"false".equalsIgnoreCase(seedToggle.trim()) && !"0".equals(seedToggle.trim()));
        if (seedEnabled) {
            seedIpcUsers(CalypsoApiController.manager);
        } else {
            System.out.println("IPC seed bootstrap skipped (CALYPSO_IPC_SEED=false).");
        }

        return ipc;
    }

    private static void seedIpcUsers(CalypsoApiManager manager) {
        String extractionToggle = System.getenv(IPC_SEED_SIGNAL_EXTRACTION_ENV);
        boolean extractionEnabled = extractionToggle == null
                || (!"false".equalsIgnoreCase(extractionToggle.trim()) && !"0".equals(extractionToggle.trim()));
        String openAiKey = System.getenv("OPENAI_API_KEY");
        boolean hasOpenAiKey = openAiKey != null && !openAiKey.trim().isEmpty();
        boolean useSeedExtraction = extractionEnabled && hasOpenAiKey;
        boolean mockSeedExtractionActive = false;
        if (!useSeedExtraction) {
            OpenAIJson.setTestOverride((system, user) -> "{\"signals\":[]}");
            mockSeedExtractionActive = true;
            if (!extractionEnabled) {
                System.out.println(
                        "IPC seed signal extraction disabled (CALYPSO_IPC_SEED_SIGNAL_EXTRACTION=false).");
            } else {
                System.out.println("IPC seed signal extraction disabled (missing OPENAI_API_KEY).");
            }
        }
        try {
            List<Long> accountIds = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            if (useSeedExtraction) {
                OpenAIJson.setTestOverride((system, user) -> "{\"signals\":[]}");
                mockSeedExtractionActive = true;
                System.out.println(
                        "IPC public seed signal extraction mocked; rich private silhouette seed remains LLM-derived.");
            }
            Map<Long, List<PublicPromptAnswer>> femaleAnswersByAccount = seedPromptedAccounts(
                    manager,
                    "woman",
                    SEED_NAMES,
                    SEED_PHONES,
                    SEED_MODES,
                    SEED_TALK_ANSWERS,
                    SEED_SUNDAY_ANSWERS,
                    SEED_GOAL_ANSWERS,
                    0,
                    accountIds,
                    failures);
            Map<Long, List<PublicPromptAnswer>> maleAnswersByAccount = seedPromptedAccounts(
                    manager,
                    "man",
                    SEED_MALE_NAMES,
                    SEED_MALE_PHONES,
                    SEED_MALE_MODES,
                    SEED_MALE_TALK_ANSWERS,
                    SEED_MALE_SUNDAY_ANSWERS,
                    SEED_MALE_GOAL_ANSWERS,
                    100,
                    accountIds,
                    failures);
            Long jacobAccountId = seedJacobPublicPromptScenario(manager, maleAnswersByAccount, accountIds, failures);
            seedCrossGenderPromptReactions(manager, femaleAnswersByAccount, maleAnswersByAccount);
            if (useSeedExtraction) {
                OpenAIJson.clearTestOverride();
                mockSeedExtractionActive = false;
            }

            seedDariaRichSilhouetteScenario(manager, useSeedExtraction);
            seedJacobFormativeScenario(manager, useSeedExtraction, jacobAccountId);

            int expectedSeedAccounts = SEED_NAMES.length + SEED_MALE_NAMES.length + 1;
            System.out.println("Seeded " + accountIds.size() + "/" + expectedSeedAccounts
                    + " IPC users with filters and prompts.");
            if (!failures.isEmpty()) {
                System.err.println("IPC seed bootstrap had " + failures.size() + " account-level failure(s):");
                for (String failure : failures) {
                    System.err.println(" - " + failure);
                }
            }
        } catch (Exception e) {
            System.err.println("IPC seed bootstrap failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (mockSeedExtractionActive) {
                OpenAIJson.clearTestOverride();
            }
        }
    }

    private static Map<Long, List<PublicPromptAnswer>> seedPromptedAccounts(
            CalypsoApiManager manager,
            String gender,
            String[] names,
            String[] phones,
            String[] modes,
            String[] talkAnswers,
            String[] sundayAnswers,
            String[] goalAnswers,
            int seedIndexOffset,
            List<Long> accountIds,
            List<String> failures) {
        LinkedHashMap<Long, List<PublicPromptAnswer>> answersByAccount = new LinkedHashMap<>();
        if (names == null || phones == null) {
            return answersByAccount;
        }
        int count = Math.min(names.length, phones.length);
        for (int i = 0; i < count; i++) {
            String name = names[i];
            String phone = phones[i];
            String mode = valueAt(modes, i, "exploratory");
            int seedIndex = seedIndexOffset + i;
            try {
                long accountId = ensureSeedAccount(manager, name, phone, seedIndex);
                accountIds.add(accountId);

                PostFilters filters = seedFilters(seedIndex, gender, mode);
                manager.postFilters(filters, accountId).get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                seedMatchStandardAnswers(manager, accountId, seedIndex);
                manager.postPublicPromptSelection(accountId, SEED_PROMPT_IDS)
                        .get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                ArrayList<PublicPromptAnswer> answers = new ArrayList<>();
                answers.add(postSeedPromptAnswer(
                        manager,
                        accountId,
                        SEED_PROMPT_IDS.get(0),
                        valueAt(talkAnswers, i, "")));
                answers.add(postSeedPromptAnswer(
                        manager,
                        accountId,
                        SEED_PROMPT_IDS.get(1),
                        valueAt(sundayAnswers, i, "")));
                answers.add(postSeedPromptAnswer(
                        manager,
                        accountId,
                        SEED_PROMPT_IDS.get(2),
                        valueAt(goalAnswers, i, "")));
                answers.removeIf(Objects::isNull);
                answersByAccount.put(accountId, answers);
            } catch (Exception accountSeedError) {
                String reason = accountSeedError.getMessage() == null
                        ? accountSeedError.getClass().getSimpleName()
                        : accountSeedError.getMessage();
                failures.add(phone + ": " + reason);
                System.err.println("IPC seed account bootstrap failed for " + phone + ": " + reason);
            }
        }
        return answersByAccount;
    }

    private static void seedCrossGenderPromptReactions(
            CalypsoApiManager manager,
            Map<Long, List<PublicPromptAnswer>> femaleAnswersByAccount,
            Map<Long, List<PublicPromptAnswer>> maleAnswersByAccount) {
        int seeded = 0;
        seeded += seedPromptReactionsFromViewers(
                manager,
                new ArrayList<>(femaleAnswersByAccount.keySet()),
                seedPromptAnswerRefs(maleAnswersByAccount));
        seeded += seedPromptReactionsFromViewers(
                manager,
                new ArrayList<>(maleAnswersByAccount.keySet()),
                seedPromptAnswerRefs(femaleAnswersByAccount));
        System.out.println("Seeded " + seeded + " IPC cross-gender prompt reactions.");
    }

    private static int seedPromptReactionsFromViewers(
            CalypsoApiManager manager,
            List<Long> viewerIds,
            List<SeedPromptAnswerRef> targetAnswers) {
        if (manager == null || viewerIds == null || viewerIds.isEmpty()
                || targetAnswers == null || targetAnswers.isEmpty()) {
            return 0;
        }
        int seeded = 0;
        Map<Long, List<SeedPromptAnswerRef>> targetAnswersByOwner = seedPromptAnswerRefsByOwner(targetAnswers);
        for (int viewerIndex = 0; viewerIndex < viewerIds.size(); viewerIndex++) {
            Long viewerId = viewerIds.get(viewerIndex);
            if (viewerId == null || viewerId.longValue() < 0L) {
                continue;
            }
            int targetCount = Math.min(
                    IPC_SEED_CROSS_GENDER_PROMPT_REACTION_COUNT,
                    targetAnswers.size());
            HashSet<String> reactedAnswerIds = new HashSet<>();
            ArrayList<Long> targetOwnerIds = new ArrayList<>(targetAnswersByOwner.keySet());
            int ownerOffset = targetOwnerIds.isEmpty() ? 0 : Math.floorMod(viewerIndex, targetOwnerIds.size());
            for (int i = 0; i < targetOwnerIds.size() && seeded < Integer.MAX_VALUE; i++) {
                Long ownerId = targetOwnerIds.get((ownerOffset + i) % targetOwnerIds.size());
                if (ownerId == null || ownerId.longValue() == viewerId.longValue()) {
                    continue;
                }
                List<SeedPromptAnswerRef> ownerAnswers = targetAnswersByOwner.get(ownerId);
                if (ownerAnswers == null || ownerAnswers.isEmpty()) {
                    continue;
                }
                SeedPromptAnswerRef ref = ownerAnswers.get(Math.floorMod(viewerIndex + i, ownerAnswers.size()));
                if (ref == null || ref.answerId == null || ref.answerId.isBlank()
                        || !reactedAnswerIds.add(ref.answerId)) {
                    continue;
                }
                int strength = Math.max(1, Math.abs(seedPromptReactionStrength(viewerIndex, i)));
                if (postSeedPromptReaction(manager, viewerId.longValue(), ref.answerId, strength)) {
                    seeded++;
                }
            }
            int offset = targetAnswers.isEmpty() ? 0 : Math.floorMod(viewerIndex * 3, targetAnswers.size());
            int attempts = 0;
            while (reactedAnswerIds.size() < targetCount && attempts < targetAnswers.size() * 2) {
                SeedPromptAnswerRef ref = targetAnswers.get((offset + attempts) % targetAnswers.size());
                attempts++;
                if (ref == null || ref.ownerId == viewerId.longValue()
                        || ref.answerId == null || ref.answerId.isBlank()
                        || !reactedAnswerIds.add(ref.answerId)) {
                    continue;
                }
                int strength = seedPromptReactionStrength(viewerIndex, attempts);
                if (postSeedPromptReaction(manager, viewerId.longValue(), ref.answerId, strength)) {
                    seeded++;
                }
            }
        }
        return seeded;
    }

    private static Map<Long, List<SeedPromptAnswerRef>> seedPromptAnswerRefsByOwner(
            List<SeedPromptAnswerRef> targetAnswers) {
        LinkedHashMap<Long, List<SeedPromptAnswerRef>> refsByOwner = new LinkedHashMap<>();
        if (targetAnswers == null || targetAnswers.isEmpty()) {
            return refsByOwner;
        }
        for (SeedPromptAnswerRef ref : targetAnswers) {
            if (ref == null || ref.ownerId < 0L || ref.answerId == null || ref.answerId.isBlank()) {
                continue;
            }
            refsByOwner.computeIfAbsent(ref.ownerId, ignored -> new ArrayList<>()).add(ref);
        }
        return refsByOwner;
    }

    private static List<SeedPromptAnswerRef> seedPromptAnswerRefs(
            Map<Long, List<PublicPromptAnswer>> publicAnswersByAccount) {
        if (publicAnswersByAccount == null || publicAnswersByAccount.isEmpty()) {
            return List.of();
        }
        ArrayList<SeedPromptAnswerRef> refs = new ArrayList<>();
        for (Map.Entry<Long, List<PublicPromptAnswer>> entry : publicAnswersByAccount.entrySet()) {
            Long ownerId = entry.getKey();
            List<PublicPromptAnswer> answers = entry.getValue();
            if (ownerId == null || ownerId.longValue() < 0L || answers == null) {
                continue;
            }
            for (PublicPromptAnswer answer : answers) {
                if (answer == null || answer.getAnswerId() == null || answer.getAnswerId().isBlank()) {
                    continue;
                }
                refs.add(new SeedPromptAnswerRef(ownerId.longValue(), answer.getAnswerId()));
            }
        }
        return refs;
    }

    private static boolean postSeedPromptReaction(
            CalypsoApiManager manager,
            long viewerId,
            String answerId,
            int reactionStrength) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= IPC_SEED_POST_RETRIES; attempt++) {
            try {
                Boolean ok = manager.postPublicPromptReaction(viewerId, answerId, Integer.valueOf(reactionStrength))
                        .get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return Boolean.TRUE.equals(ok);
            } catch (Exception e) {
                lastError = e;
                if (attempt < IPC_SEED_POST_RETRIES) {
                    try {
                        sleepQuietly(250L * attempt);
                    } catch (InterruptedException interrupted) {
                        return false;
                    }
                }
            }
        }
        String reason = lastError == null ? "unknown" : lastError.getMessage();
        System.err.println("IPC seed prompt reaction failed for viewer " + viewerId
                + " answer " + answerId + ": " + reason);
        return false;
    }

    private static int seedPromptReactionStrength(int viewerIndex, int answerIndex) {
        if (IPC_SEED_PROMPT_REACTION_STRENGTHS.length == 0) {
            return 1;
        }
        return IPC_SEED_PROMPT_REACTION_STRENGTHS[
                Math.floorMod(viewerIndex + answerIndex, IPC_SEED_PROMPT_REACTION_STRENGTHS.length)];
    }

    private static String valueAt(String[] values, int idx, String fallback) {
        if (values == null || values.length == 0) {
            return fallback;
        }
        String value = values[Math.floorMod(idx, values.length)];
        return value == null ? fallback : value;
    }

    private static final class SeedPromptAnswerRef {
        final long ownerId;
        final String answerId;

        SeedPromptAnswerRef(long ownerId, String answerId) {
            this.ownerId = ownerId;
            this.answerId = answerId;
        }
    }

    private static long ensureSeedAccount(CalypsoApiManager manager, String name, String phone, int idx) throws Exception {
        return ensureSeedAccount(manager, name, phone, idx, "1998-01-01");
    }

    private static long ensureSeedAccount(CalypsoApiManager manager, String name, String phone, int idx, String birthday)
            throws Exception {
        Long existing = waitForAccountId(manager, phone, IPC_SEED_ACCOUNT_LOOKUP_TIMEOUT_MS);
        if (existing != null && existing.longValue() >= 0L) {
            return existing.longValue();
        }
        PostAccount account = new PostAccount();
        account.name = name;
        account.phone_number = phone;
        account.locale = "en-US";
        account.agreement = true;
        account.verification_token = "ipc-seed-token-" + idx;
        account.birthday = birthday == null || birthday.isBlank() ? "1998-01-01" : birthday;
        manager.postAccount(account).get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Long created = waitForAccountId(manager, phone, IPC_SEED_ACCOUNT_LOOKUP_TIMEOUT_MS);
        if (created == null || created.longValue() < 0L) {
            throw new IllegalStateException("Unable to create IPC seed account for " + phone);
        }
        return created.longValue();
    }

    private static Long waitForAccountId(CalypsoApiManager manager, String phone, long timeoutMs) throws Exception {
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + Math.max(500L, timeoutMs);
        while (System.currentTimeMillis() <= deadline) {
            Long accountId = manager.getAccountId(phone).get(5, TimeUnit.SECONDS);
            if (accountId != null && accountId.longValue() >= 0L) {
                return accountId;
            }
            sleepQuietly(150L);
        }
        return null;
    }

    private static PublicPromptAnswer postSeedPromptAnswer(
            CalypsoApiManager manager,
            long accountId,
            String promptId,
            String body)
            throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= IPC_SEED_POST_RETRIES; attempt++) {
            try {
                return manager.postPublicPromptAnswer(accountId, promptId, body).get(IPC_SEED_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
            } catch (Exception e) {
                lastError = e;
                if (attempt < IPC_SEED_POST_RETRIES) {
                    sleepQuietly(250L * attempt);
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Failed to post seed answer for prompt " + promptId);
    }

    private static void seedDariaRichSilhouetteScenario(CalypsoApiManager manager, boolean useSeedExtraction) {
        try {
            Long accountId = waitForAccountId(manager, DARIA_SEED_PHONE, IPC_SEED_ACCOUNT_LOOKUP_TIMEOUT_MS);
            if (accountId == null || accountId.longValue() < 0L) {
                throw new IllegalStateException("Unable to find Daria seed account.");
            }
            seedPrivatePromptAnswers(
                    manager,
                    accountId.longValue(),
                    "Daria",
                    DARIA_RICH_PRIVATE_PROMPTS,
                    useSeedExtraction);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("Daria rich silhouette seed scenario failed: " + reason);
        }
    }

    private static Long seedJacobPublicPromptScenario(
            CalypsoApiManager manager,
            Map<Long, List<PublicPromptAnswer>> maleAnswersByAccount,
            List<Long> accountIds,
            List<String> failures) {
        try {
            long accountId = ensureSeedAccount(manager, "Jacob", JACOB_SEED_PHONE, 704689031, "1999-03-24");
            if (accountIds != null && !accountIds.contains(accountId)) {
                accountIds.add(accountId);
            }
            manager.postFilters(jacobSeedFilters(), accountId).get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            manager.postPublicPromptSelection(accountId, List.of("prompt.talk.hours"))
                    .get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            PublicPromptAnswer answer = postSeedPromptAnswer(
                    manager,
                    accountId,
                    "prompt.talk.hours",
                    "Jojo's bizarre adventure");
            if (maleAnswersByAccount != null && answer != null) {
                maleAnswersByAccount.put(accountId, List.of(answer));
            }
            return Long.valueOf(accountId);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (failures != null) {
                failures.add(JACOB_SEED_PHONE + ": " + reason);
            }
            System.err.println("Jacob public seed scenario failed: " + reason);
            return null;
        }
    }

    private static void seedJacobFormativeScenario(
            CalypsoApiManager manager,
            boolean useSeedExtraction,
            Long existingAccountId) {
        try {
            long accountId = existingAccountId != null && existingAccountId.longValue() >= 0L
                    ? existingAccountId.longValue()
                    : ensureSeedAccount(manager, "Jacob", JACOB_SEED_PHONE, 704689031, "1999-03-24");
            if (existingAccountId == null || existingAccountId.longValue() < 0L) {
                manager.postFilters(jacobSeedFilters(), accountId).get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                manager.postPublicPromptSelection(accountId, List.of("prompt.talk.hours"))
                        .get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                postSeedPromptAnswer(manager, accountId, "prompt.talk.hours", "Jojo's bizarre adventure");
            }

            seedPrivatePromptAnswers(
                    manager,
                    accountId,
                    "Jacob",
                    JACOB_RICH_PRIVATE_PROMPTS,
                    useSeedExtraction);
            System.out.println("Seeded Jacob formative test account " + JACOB_SEED_PHONE + ".");
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("Jacob formative seed scenario failed: " + reason);
        }
    }

    private static void seedPrivatePromptAnswers(
            CalypsoApiManager manager,
            long accountId,
            String label,
            List<SeedPrivatePromptAnswerSpec> specs,
            boolean useSeedExtraction)
            throws Exception {
        if (specs == null || specs.isEmpty()) {
            return;
        }
        if (!useSeedExtraction) {
            System.out.println("Skipped " + label
                    + " rich silhouette seed; OPENAI_API_KEY and seed extraction are required for LLM-derived silhouettes.");
            return;
        }
        Map<String, Object> initialSilhouette = manager.flushSilhouetteUpdatesForAccount(accountId)
                .get(IPC_RICH_SILHOUETTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int existingConcepts = seedSilhouetteConceptCount(initialSilhouette);
        Set<String> answeredPromptIds = seedAnsweredPrivatePromptIds(
                manager.getPrivatePromptSchedulerStateSnapshot(accountId)
                        .get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        LinkedHashMap<String, SeedPrivatePromptAnswerSpec> remaining = new LinkedHashMap<>();
        for (SeedPrivatePromptAnswerSpec spec : specs) {
            if (spec != null && spec.promptId != null && !spec.promptId.isBlank()
                    && !answeredPromptIds.contains(spec.promptId)) {
                remaining.put(spec.promptId, spec);
            }
        }
        if (remaining.isEmpty()) {
            Map<String, Object> drainedSilhouette = manager.flushSilhouetteUpdatesForAccount(accountId)
                    .get(IPC_RICH_SILHOUETTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int conceptCount = seedSilhouetteConceptCount(drainedSilhouette);
            System.out.println("Skipped " + label
                    + " rich silhouette seed; target private prompts already answered; silhouette concepts="
                    + conceptCount + ".");
            return;
        }
        int answered = 0;
        for (SeedPrivatePromptAnswerSpec spec : remaining.values()) {
            ActivePrivatePrompt active = manager.seedPrivatePromptAnswerByPromptId(
                    accountId,
                    spec.promptId,
                    spec.answer,
                    spec.conversationLines)
                    .get(IPC_RICH_SILHOUETTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (active != null) {
                answered++;
            }
        }
        Map<String, Object> drainedSilhouette = answered <= 0
                ? initialSilhouette
                : manager.flushSilhouetteUpdatesForAccount(accountId)
                        .get(IPC_RICH_SILHOUETTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int conceptCount = answered <= 0 ? existingConcepts : seedSilhouetteConceptCount(drainedSilhouette);
        System.out.println("Seeded " + answered + "/" + specs.size() + " " + label
                + " private prompt silhouette answers; silhouette concepts=" + conceptCount + ".");
    }

    private static Set<String> seedAnsweredPrivatePromptIds(Map<String, Object> schedulerState) {
        if (schedulerState == null || schedulerState.isEmpty()) {
            return Set.of();
        }
        Object raw = schedulerState.get("answeredPromptIds");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                String promptId = item == null ? "" : item.toString().trim();
                if (!promptId.isBlank()) {
                    out.add(promptId);
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                String promptId = key == null ? "" : key.toString().trim();
                if (!promptId.isBlank()) {
                    out.add(promptId);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int seedSilhouetteConceptCount(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return 0;
        }
        Object rawModes = snapshot.get("modes");
        if (!(rawModes instanceof List<?> modes)) {
            return 0;
        }
        int count = 0;
        List<String> conceptBuckets = List.of(
                "selfExpression",
                "seekingExpression",
                "sparkTriggers",
                "sustainabilityNeeds",
                "aestheticField",
                "realWorldComps",
                "antiPatterns",
                "tensions");
        for (Object rawMode : modes) {
            if (!(rawMode instanceof Map<?, ?> mode)) {
                continue;
            }
            for (String bucket : conceptBuckets) {
                Object rawBucket = ((Map<String, Object>) mode).get(bucket);
                if (rawBucket instanceof List<?> list) {
                    count += list.size();
                }
            }
        }
        return count;
    }

    private static SeedPrivatePromptAnswerSpec seedPrivatePrompt(
            String promptId,
            List<String> messages) {
        List<String> safeMessages = messages == null ? List.of() : messages;
        return new SeedPrivatePromptAnswerSpec(
                promptId,
                String.join("\n", safeMessages),
                conversationFromUserMessages(safeMessages));
    }

    private static List<String> conversationFromUserMessages(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String message : messages) {
            if (message != null && !message.isBlank()) {
                out.add("User: " + message.trim());
            }
        }
        return out;
    }

    private static final class SeedPrivatePromptAnswerSpec {
        final String promptId;
        final String answer;
        final List<String> conversationLines;

        SeedPrivatePromptAnswerSpec(
                String promptId,
                String answer,
                List<String> conversationLines) {
            this.promptId = promptId == null ? "" : promptId.trim();
            this.answer = answer == null ? "" : answer.trim();
            this.conversationLines = conversationLines == null ? List.of() : List.copyOf(conversationLines);
        }
    }

    private static void sleepQuietly(long millis) throws InterruptedException {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    private static void seedMatchStandardAnswers(CalypsoApiManager manager, long accountId, int idx) throws Exception {
        List<String> lifestyle = SEED_LIFESTYLE_SELF.get(Math.floorMod(idx, SEED_LIFESTYLE_SELF.size()));
        String politics = mapSeedPolitics(SEED_POLITICS[Math.floorMod(idx, SEED_POLITICS.length)]);
        String religion = mapSeedReligion(SEED_RELIGIONS[Math.floorMod(idx, SEED_RELIGIONS.length)]);
        String kidsCurrent = seedKidsCurrent(lifestyle);
        String kidsFuture = seedKidsFuture(lifestyle);
        String alcohol = seedAlcohol(lifestyle);
        String smoking = lifestyle.contains("non_smoker") ? "none" : "smoke";
        String cannabis = lifestyle.contains("cannabis_user") ? "occasional_cannabis" : "no_cannabis";
        String drugs = seedRecreationalDrugs(lifestyle);

        postSeedMatchStandardAnswer(manager, accountId, "standard.values.politics", politics,
                List.of("left", "center_left", "center", "apolitical"), Importance.PREFERENCE);
        postSeedMatchStandardAnswer(manager, accountId, "standard.religion.identity", religion,
                List.of("christian", "muslim", "hindu", "buddhist", "jewish", "sikh", "spiritual", "atheist",
                        "agnostic", "secular_humanist", "taoist", "shinto", "bahai", "jain", "indigenous",
                        "pagan", "zoroastrian", "rastafarian", "custom_belief", "prefer_not_to_say"),
                Importance.NOT_IMPORTANT);
        postSeedMatchStandardAnswer(manager, accountId, "standard.kids.future", List.of(kidsCurrent, kidsFuture),
                List.of("has_kids", "no_kids", "wants_kids", "open_to_kids", "not_sure", "doesnt_want_kids"),
                Importance.PREFERENCE);
        postSeedMatchStandardAnswer(manager, accountId, "standard.substances.alcohol", alcohol,
                alcohol.equals("regular") ? List.of("social", "regular") : List.of("non_drinker", "rare", "social"),
                Importance.PREFERENCE);
        postSeedMatchStandardAnswer(manager, accountId, "standard.substances.smoking", smoking,
                List.of("none"), Importance.PREFERENCE);
        postSeedMatchStandardAnswer(manager, accountId, "standard.substances.cannabis", cannabis,
                cannabis.equals("no_cannabis")
                        ? List.of("no_cannabis", "cannabis_ok")
                        : List.of("no_cannabis", "cannabis_ok", "occasional_cannabis"),
                Importance.PREFERENCE);
        postSeedMatchStandardAnswer(manager, accountId, "standard.substances.drugs", drugs,
                List.of("no_drugs"), Importance.PREFERENCE);
    }

    private static void postSeedMatchStandardAnswer(CalypsoApiManager manager, long accountId, String questionId,
            String own, List<String> acceptable, Importance importance) throws Exception {
        postSeedMatchStandardAnswer(manager, accountId, questionId, List.of(own), acceptable, importance);
    }

    private static void postSeedMatchStandardAnswer(CalypsoApiManager manager, long accountId, String questionId,
            List<String> own, List<String> acceptable, Importance importance) throws Exception {
        MatchStandardAnswer answer = new MatchStandardAnswer();
        answer.setAccountId(accountId);
        answer.setQuestionId(questionId);
        answer.setOwnAnswerOptionIds(own);
        answer.setAcceptableAnswerOptionIds(acceptable);
        answer.setImportance(importance);
        answer.setUpdatedAt(System.currentTimeMillis());
        manager.postMatchStandardAnswer(answer).get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static String mapSeedPolitics(String value) {
        if ("liberal".equals(value)) {
            return "center_left";
        }
        if ("apolitical".equals(value)) {
            return "apolitical";
        }
        return "center";
    }

    private static String mapSeedReligion(String value) {
        if ("spiritual".equals(value)) {
            return "spiritual";
        }
        if ("christian".equals(value)) {
            return "christian";
        }
        if ("secular_humanist".equals(value)) {
            return "secular_humanist";
        }
        return "agnostic";
    }

    private static String seedKidsCurrent(List<String> lifestyle) {
        if (lifestyle.contains("has_kids")) {
            return "has_kids";
        }
        return "no_kids";
    }

    private static String seedKidsFuture(List<String> lifestyle) {
        if (lifestyle.contains("wants_kids")) {
            return "wants_kids";
        }
        if (lifestyle.contains("doesnt_want_kids")) {
            return "doesnt_want_kids";
        }
        if (lifestyle.contains("open_to_kids")) {
            return "open_to_kids";
        }
        return "not_sure";
    }

    private static String seedRecreationalDrugs(List<String> lifestyle) {
        if (lifestyle.contains("psychedelics_user")) {
            return "psychedelics_user";
        }
        if (lifestyle.contains("recreational_drugs")) {
            return "recreational_drugs";
        }
        return "no_drugs";
    }

    private static String seedAlcohol(List<String> lifestyle) {
        if (lifestyle.contains("regular_drinker")) {
            return "regular";
        }
        if (lifestyle.contains("social_drinker")) {
            return "social";
        }
        if (lifestyle.contains("non_drinker")) {
            return "non_drinker";
        }
        return "rare";
    }

    private static PostFilters seedFilters(int idx, String gender, String mode) {
        PostFilters filters = new PostFilters();

        ModeFilter relationshipMode = new ModeFilter();
        relationshipMode.setSelf(mode);
        filters.relationshipMode = relationshipMode;

        OneToManyFilter genderFilter = new OneToManyFilter();
        genderFilter.setSelf(gender);
        genderFilter.setSeeking("man".equals(gender) ? List.of("woman") : List.of("man"));
        filters.gender = genderFilter;

        RangeFilter age = new RangeFilter();
        age.setSelf(23 + (idx % 6));
        age.setMin(21);
        age.setMax(40);
        filters.age = age;

        LocationFilter location = new LocationFilter();
        location.setLat(37.7749);
        location.setLon(-122.4194);
        location.setRadiusKm(30000.0);
        location.setScope(LocationScope.WORLDWIDE);
        filters.location = location;

        return filters;
    }

    private static PostFilters jacobSeedFilters() {
        PostFilters filters = new PostFilters();

        ModeFilter relationshipMode = new ModeFilter();
        relationshipMode.setSelf("exploratory");
        filters.relationshipMode = relationshipMode;

        OneToManyFilter genderFilter = new OneToManyFilter();
        genderFilter.setSelf("man");
        genderFilter.setSeeking(List.of("woman"));
        filters.gender = genderFilter;

        RangeFilter age = new RangeFilter();
        age.setSelf(27);
        age.setMin(18);
        age.setMax(40);
        filters.age = age;

        LocationFilter location = new LocationFilter();
        location.setLat(35.2271);
        location.setLon(-80.8431);
        location.setRadiusKm(30000.0);
        location.setScope(LocationScope.WORLDWIDE);
        filters.location = location;

        return filters;
    }

}
