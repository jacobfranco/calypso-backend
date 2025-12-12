package now.calypso.backendapi.pojos;

public class PostAccount {
    public String name;
    public String email;
    public String password;
    public Boolean agreement;
    public String locale;

    public PostAccount() {
    }

    public PostAccount(String name, String email, String password, Boolean agreement, String locale) {
        this.name = name;
        this.email = email;
        this.password = password;
        this.agreement = agreement;
        this.locale = locale;
    }
}
