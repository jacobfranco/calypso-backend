package now.calypso.backendapi;

import now.calypso.backend.*;
import now.calypso.backend.data.Importance;
import now.calypso.backend.data.LocationFilter;
import now.calypso.backend.data.LocationScope;
import now.calypso.backend.data.ManyToManyFilter;
import now.calypso.backend.data.ModeFilter;
import now.calypso.backend.data.OneToManyFilter;
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
    private static final String IPC_SEED_SIGNAL_EXTRACTION_ENV = "CALYPSO_IPC_SEED_SIGNAL_EXTRACTION";
    private static final int IPC_SEED_TIMEOUT_SECONDS = 12;
    private static final long IPC_SEED_ACCOUNT_LOOKUP_TIMEOUT_MS = 12_000L;
    private static final int IPC_SEED_POST_RETRIES = 3;

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
        if (!useSeedExtraction) {
            OpenAIJson.setTestOverride((system, user) -> "{\"signals\":[]}");
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

            for (int i = 0; i < SEED_NAMES.length; i++) {
                String name = SEED_NAMES[i];
                String phone = SEED_PHONES[i];
                String gender = "woman";
                String mode = SEED_MODES[i % SEED_MODES.length];
                try {
                    long accountId = ensureSeedAccount(manager, name, phone, i);
                    accountIds.add(accountId);

                    PostFilters filters = seedFilters(i, gender, mode);
                    manager.postFilters(filters, accountId).get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    manager.postPublicPromptSelection(accountId, SEED_PROMPT_IDS)
                            .get(IPC_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                    postSeedPromptAnswer(
                            manager,
                            accountId,
                            SEED_PROMPT_IDS.get(0),
                            SEED_TALK_ANSWERS[i % SEED_TALK_ANSWERS.length]);
                    postSeedPromptAnswer(
                            manager,
                            accountId,
                            SEED_PROMPT_IDS.get(1),
                            SEED_SUNDAY_ANSWERS[i % SEED_SUNDAY_ANSWERS.length]);
                    postSeedPromptAnswer(
                            manager,
                            accountId,
                            SEED_PROMPT_IDS.get(2),
                            SEED_GOAL_ANSWERS[i % SEED_GOAL_ANSWERS.length]);
                } catch (Exception accountSeedError) {
                    String reason = accountSeedError.getMessage() == null
                            ? accountSeedError.getClass().getSimpleName()
                            : accountSeedError.getMessage();
                    failures.add(phone + ": " + reason);
                    System.err.println("IPC seed account bootstrap failed for " + phone + ": " + reason);
                }
            }

            System.out.println("Seeded " + accountIds.size() + "/" + SEED_NAMES.length
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
            if (!useSeedExtraction) {
                OpenAIJson.clearTestOverride();
            }
        }
    }

    private static long ensureSeedAccount(CalypsoApiManager manager, String name, String phone, int idx) throws Exception {
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
        account.birthday = "1998-01-01";
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

    private static void postSeedPromptAnswer(CalypsoApiManager manager, long accountId, String promptId, String body)
            throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= IPC_SEED_POST_RETRIES; attempt++) {
            try {
                manager.postPublicPromptAnswer(accountId, promptId, body).get(IPC_SEED_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
                return;
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

    private static void sleepQuietly(long millis) throws InterruptedException {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
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
