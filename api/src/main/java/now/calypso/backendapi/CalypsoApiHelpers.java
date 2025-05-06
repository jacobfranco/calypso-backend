package now.calypso.backendapi;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.services.s3.S3AsyncClient;

public class CalypsoApiHelpers {

    private static S3AsyncClient S3_CLIENT = null;
    public static void initS3Client() {
        S3_CLIENT = S3AsyncClient.builder().credentialsProvider(EnvironmentVariableCredentialsProvider.create()).build();
    }
    
}
