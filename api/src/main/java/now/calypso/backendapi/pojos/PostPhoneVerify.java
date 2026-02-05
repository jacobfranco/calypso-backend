package now.calypso.backendapi.pojos;

public class PostPhoneVerify {
    public String phone_number;
    public String code;

    public PostPhoneVerify() {
    }

    public PostPhoneVerify(String phone_number, String code) {
        this.phone_number = phone_number;
        this.code = code;
    }
}
