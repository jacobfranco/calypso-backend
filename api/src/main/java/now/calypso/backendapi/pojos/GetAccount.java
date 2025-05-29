package now.calypso.backendapi.pojos;

import now.calypso.backend.CalypsoConfig;
import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.AccountWithId;
import now.calypso.backendapi.CalypsoApiConfig;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class GetAccount {
    public String id;
    public String name;
    public String avatar;
    public String avatar_static;
    public String created_at;

    public GetAccount() { }

    public GetAccount(AccountWithId accountWithId) {
        // Serialize the account ID
        this.id = CalypsoHelpers.serializeAccountId(accountWithId.accountId);

        // Use the account's name
        this.name = accountWithId.account.name;

        if (CalypsoApiConfig.S3_OPTIONS != null) {
            if (accountWithId.account.isSetAvatar()
                    && accountWithId.account.avatar.attachment.path != null
                    && !accountWithId.account.avatar.attachment.path.isEmpty()) {
                String path = accountWithId.account.avatar.attachment.path;
                String url = String.format("%s/%s",
                        CalypsoApiConfig.S3_OPTIONS.url,
                        path
                );
                this.avatar = url;
                this.avatar_static = url;
            }
        } else {
            if (accountWithId.account.isSetAvatar()
                    && accountWithId.account.avatar.attachment.path != null
                    && !accountWithId.account.avatar.attachment.path.isEmpty()) {
                String path = accountWithId.account.avatar.attachment.path;
                String url = String.format("%s/%s/%s",
                        CalypsoConfig.API_URL,
                        CalypsoApiConfig.STATIC_FILE_URL_PATH_NAME,
                        path
                );
                this.avatar = url;
                this.avatar_static = url;
            }
        }

        // Default placeholder if no avatar set
        if (this.avatar == null) {
            String placeholder = CalypsoConfig.API_URL + "/missing_avatar.png";
            this.avatar = placeholder;
            this.avatar_static = placeholder;
        }

        // Format creation timestamp as ISO 8601
        this.created_at = DateTimeFormatter.ISO_INSTANT
                .format(Instant.ofEpochMilli(accountWithId.account.timestamp));
    }
}
