package now.calypso.backendapi.pojos;

public class GetPhoneVerification {
    public String verification_token;

    public GetPhoneVerification() {
    }

    public GetPhoneVerification(String verification_token) {
        this.verification_token = verification_token;
    }
}
