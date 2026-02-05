package now.calypso.backendapi.pojos;

public class PostAccount {
    public String name;
    public Boolean agreement;
    public String locale;
    public String phone_number;
    public String birthday;
    public String verification_token;

    public PostAccount() {
    }

    public PostAccount(String name, String phone_number, String birthday, String verification_token, Boolean agreement, String locale) {
        this.name = name;
        this.phone_number = phone_number;
        this.birthday = birthday;
        this.verification_token = verification_token;
        this.agreement = agreement;
        this.locale = locale;
    }
}
