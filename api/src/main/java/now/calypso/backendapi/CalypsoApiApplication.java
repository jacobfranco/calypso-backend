package now.calypso.backendapi;

import now.calypso.backend.*;
import now.calypso.backend.data.Importance;
import now.calypso.backend.data.LocationFilter;
import now.calypso.backend.data.LocationScope;
import now.calypso.backend.data.ManyToManyFilter;
import now.calypso.backend.data.ModeFilter;
import now.calypso.backend.data.OneToManyFilter;
import now.calypso.backend.data.PromptReaction;
import now.calypso.backend.data.PublicPromptAnswer;
import now.calypso.backend.data.RangeFilter;
import now.calypso.backend.data.TagPreference;
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

    private static final String[] SEED_MODES = {
            "balanced", "exploratory", "exploratory", "exploratory", "balanced", "exploratory"
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

    private static final String[] SEED_LIFESTYLE_PREFERENCE = {
            "non_smoker", "no_drugs", "social_drinker", "cannabis_user", "non_smoker", "regular_drinker"
    };

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
        OpenAIJson.setTestOverride((system, user) -> "{\"signals\":[]}");
        try {
            List<Long> accountIds = new ArrayList<>();
            Map<Long, String> firstAnswerByAccountId = new HashMap<>();

            for (int i = 0; i < SEED_NAMES.length; i++) {
                String name = SEED_NAMES[i];
                String phone = SEED_PHONES[i];
                String gender = "woman";
                String mode = SEED_MODES[i % SEED_MODES.length];
                long accountId = ensureSeedAccount(manager, name, phone, i);
                accountIds.add(accountId);

                PostFilters filters = seedFilters(i, gender, mode);
                manager.postFilters(filters, accountId).get(8, TimeUnit.SECONDS);

                manager.postPublicPromptSelection(accountId, SEED_PROMPT_IDS).get(8, TimeUnit.SECONDS);
                PublicPromptAnswer first = manager.postPublicPromptAnswer(
                        accountId,
                        SEED_PROMPT_IDS.get(0),
                        SEED_TALK_ANSWERS[i % SEED_TALK_ANSWERS.length])
                        .get(8, TimeUnit.SECONDS);
                manager.postPublicPromptAnswer(
                        accountId,
                        SEED_PROMPT_IDS.get(1),
                        SEED_SUNDAY_ANSWERS[i % SEED_SUNDAY_ANSWERS.length])
                        .get(8, TimeUnit.SECONDS);
                manager.postPublicPromptAnswer(
                        accountId,
                        SEED_PROMPT_IDS.get(2),
                        SEED_GOAL_ANSWERS[i % SEED_GOAL_ANSWERS.length])
                        .get(8, TimeUnit.SECONDS);

                if (first != null && first.getAnswerId() != null) {
                    firstAnswerByAccountId.put(accountId, first.getAnswerId());
                }
            }

            for (int i = 0; i < accountIds.size(); i++) {
                long viewerId = accountIds.get(i);
                long likeTargetId = accountIds.get((i + 1) % accountIds.size());
                long dislikeTargetId = accountIds.get((i + 2) % accountIds.size());

                String likeAnswerId = firstAnswerByAccountId.get(likeTargetId);
                if (likeAnswerId != null) {
                    manager.postPublicPromptReaction(viewerId, likeAnswerId, PromptReaction.LIKE)
                            .get(6, TimeUnit.SECONDS);
                }
                String dislikeAnswerId = firstAnswerByAccountId.get(dislikeTargetId);
                if (dislikeAnswerId != null) {
                    manager.postPublicPromptReaction(viewerId, dislikeAnswerId, PromptReaction.DISLIKE)
                            .get(6, TimeUnit.SECONDS);
                }
            }

            if (accountIds.size() >= 2) {
                seedMutualPair(manager, accountIds, firstAnswerByAccountId, 0, 1);
            }
            if (accountIds.size() >= 4) {
                seedMutualPair(manager, accountIds, firstAnswerByAccountId, 2, 3);
            }
            if (accountIds.size() >= 6) {
                seedMutualPair(manager, accountIds, firstAnswerByAccountId, 4, 5);
            }

            System.out.println("Seeded " + accountIds.size() + " IPC users with filters, prompts, and reactions.");
        } catch (Exception e) {
            System.err.println("IPC seed bootstrap failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            OpenAIJson.clearTestOverride();
        }
    }

    private static void seedMutualPair(CalypsoApiManager manager, List<Long> ids, Map<Long, String> firstAnswerByAccountId,
            int leftIdx, int rightIdx) throws Exception {
        if (leftIdx < 0 || rightIdx < 0 || leftIdx >= ids.size() || rightIdx >= ids.size()) {
            return;
        }
        long left = ids.get(leftIdx);
        long right = ids.get(rightIdx);
        String leftAnswer = firstAnswerByAccountId.get(left);
        String rightAnswer = firstAnswerByAccountId.get(right);
        if (leftAnswer != null) {
            manager.postPublicPromptReaction(right, leftAnswer, PromptReaction.LIKE).get(6, TimeUnit.SECONDS);
        }
        if (rightAnswer != null) {
            manager.postPublicPromptReaction(left, rightAnswer, PromptReaction.LIKE).get(6, TimeUnit.SECONDS);
        }
        manager.postFacecardReaction(left, right, PromptReaction.LIKE).get(6, TimeUnit.SECONDS);
        manager.postFacecardReaction(right, left, PromptReaction.LIKE).get(6, TimeUnit.SECONDS);
    }

    private static long ensureSeedAccount(CalypsoApiManager manager, String name, String phone, int idx) throws Exception {
        Long existing = manager.getAccountId(phone).get(5, TimeUnit.SECONDS);
        if (existing != null && existing.longValue() >= 0L) {
            return existing.longValue();
        }
        PostAccount account = new PostAccount();
        account.name = name;
        account.phone_number = phone;
        account.locale = "en-US";
        account.agreement = true;
        account.verification_token = "ipc-seed-token-" + idx;
        account.birthday = "1998-01-01";
        manager.postAccount(account).get(8, TimeUnit.SECONDS);
        Long created = manager.getAccountId(phone).get(5, TimeUnit.SECONDS);
        if (created == null || created.longValue() < 0L) {
            throw new IllegalStateException("Unable to create IPC seed account for " + phone);
        }
        return created.longValue();
    }

    private static PostFilters seedFilters(int idx, String gender, String mode) {
        PostFilters filters = new PostFilters();

        ModeFilter relationshipMode = new ModeFilter();
        relationshipMode.setSelf(mode);
        filters.relationshipMode = relationshipMode;

        OneToManyFilter genderFilter = new OneToManyFilter();
        genderFilter.setSelf(gender);
        genderFilter.setSeeking(List.of("man"));
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

        OneToManyFilter politics = new OneToManyFilter();
        politics.setSelf(SEED_POLITICS[idx % SEED_POLITICS.length]);
        filters.politics = politics;

        OneToManyFilter religion = new OneToManyFilter();
        religion.setSelf(SEED_RELIGIONS[idx % SEED_RELIGIONS.length]);
        filters.religion = religion;

        ManyToManyFilter lifestyle = new ManyToManyFilter();
        lifestyle.setSelf(new ArrayList<>(SEED_LIFESTYLE_SELF.get(idx % SEED_LIFESTYLE_SELF.size())));
        TagPreference pref = new TagPreference();
        pref.setTag(SEED_LIFESTYLE_PREFERENCE[idx % SEED_LIFESTYLE_PREFERENCE.length]);
        pref.setImportance(Importance.PREFERENCE);
        lifestyle.setPreferences(List.of(pref));
        filters.lifestyle = lifestyle;

        return filters;
    }

}
