package now.calypso.backendapi.pojos;

import now.calypso.backend.data.Application;

public class GetApplication {
    public String name = "Calypso";
    public String redirect_uri;
    public String client_id;
    public String client_secret;

    public GetApplication() {
    } // Default constructor

    public GetApplication(Application app) {
        this.name = app.getName();
        this.redirect_uri = app.getRedirect_uri();
        this.client_id = app.getClient_id();
        this.client_secret = app.getClient_secret();
    }

}
