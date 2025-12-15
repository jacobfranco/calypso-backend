package now.calypso.backendapi;

import now.calypso.backend.*;
import now.calypso.backend.modules.*;
import now.calypso.backend.serialization.CalypsoSerialization;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.rpl.rama.*;
import com.rpl.rama.test.*;

import software.amazon.awssdk.core.exception.SdkClientException;

import java.util.*;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CalypsoApiApplication {

    public static void main(String[] args) {
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

    public static InProcessCluster initIPC() {
        List<Class> sers = new ArrayList<>();
        sers.add(CalypsoSerialization.class);
        InProcessCluster ipc = InProcessCluster.create(sers);

        Core coreModule = new Core();
        ipc.launchModule(coreModule, new LaunchConfig(2, 1));
        Matches matchesModule = new Matches();
        ipc.launchModule(matchesModule, new LaunchConfig(2, 1));

        // Build openAI Client
        OpenAIClient openAI = OpenAIOkHttpClient.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();

        CalypsoApiController.manager = new CalypsoApiManager(ipc, openAI);
        return ipc;
    }

}
