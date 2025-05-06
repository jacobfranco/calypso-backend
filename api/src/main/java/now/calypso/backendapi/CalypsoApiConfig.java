package now.calypso.backendapi;

public class CalypsoApiConfig {
    
    public static class S3Options {
        public String bucketName;
        public String url;
    }
    public static S3Options S3_OPTIONS = null;
    static {
        S3_OPTIONS = new S3Options();
        S3_OPTIONS.bucketName = "yourbucket";
        S3_OPTIONS.url = "https://yourbucket.s3.amazonaws.com";
    }
    
}
