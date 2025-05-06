package now.calypso.backend;

public class CalypsoConfig {
    public static String API_URL = System.getProperty("calypso.api.url", "http://localhost:8080");
    public static String API_WEB_SOCKET_URL = System.getProperty("calypso.api.web.socket.url", "ws://localhost:8080");
    public static String API_DOMAIN = System.getProperty("calypso.api.domain", "localhost");
    public static String FRONTEND_URL = System.getProperty("calypso.frontend.url", "http://localhost:8000");
}
