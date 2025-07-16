package now.calypso.backendapi;

import java.util.HashMap;

import org.springframework.security.crypto.bcrypt.*;
import org.springframework.security.crypto.password.*;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.services.s3.S3AsyncClient;

public class CalypsoApiHelpers {

    private static final DelegatingPasswordEncoder PASSWORD_ENCODER;

    static {
        HashMap<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        PASSWORD_ENCODER = new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    public static String encodePassword(String password) {
        return PASSWORD_ENCODER.encode(password);
    }

    private static S3AsyncClient S3_CLIENT = null;

    public static void initS3Client() {
        S3_CLIENT = S3AsyncClient.builder().credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

}
