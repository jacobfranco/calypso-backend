package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.Attachment;
import now.calypso.backend.data.AttachmentKind;
import now.calypso.backend.data.AttachmentWithId;
import now.calypso.backend.data.PromptReaction;

public class PostPromptResponseRequest {
    public final String reaction;
    public final String answerText;
    public final String comment;
    public final Long targetAccountId;
    public final List<AttachmentPayload> attachments;

    @JsonCreator
    public PostPromptResponseRequest(
            @JsonProperty("reaction") String reaction,
            @JsonProperty("answerText") String answerText,
            @JsonProperty("comment") String comment,
            @JsonProperty("targetAccountId") Long targetAccountId,
            @JsonProperty("attachments") List<AttachmentPayload> attachments) {
        this.reaction = reaction;
        this.answerText = answerText;
        this.comment = comment;
        this.targetAccountId = targetAccountId;
        this.attachments = attachments == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(attachments));
    }

    public PromptReaction parsedReaction() {
        if (reaction == null)
            return null;
        String normalized = reaction.trim().toUpperCase(Locale.ROOT);
        try {
            return PromptReaction.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public List<AttachmentWithId> toThriftAttachments() {
        if (attachments.isEmpty())
            return Collections.emptyList();
        List<AttachmentWithId> out = new ArrayList<>(attachments.size());
        for (AttachmentPayload payload : attachments) {
            AttachmentWithId thrift = payload.toThrift();
            if (thrift != null)
                out.add(thrift);
        }
        return out;
    }

    public static final class AttachmentPayload {
        public final String uuid;
        public final String kind;
        public final String path;
        public final String description;

        @JsonCreator
        public AttachmentPayload(
                @JsonProperty("uuid") String uuid,
                @JsonProperty("kind") String kind,
                @JsonProperty("path") String path,
                @JsonProperty("description") String description) {
            this.uuid = uuid;
            this.kind = kind;
            this.path = path;
            this.description = description;
        }

        public AttachmentWithId toThrift() {
            if (uuid == null || uuid.isBlank())
                return null;
            String normalizedPath = path == null ? null : path.trim();
            if (normalizedPath == null || normalizedPath.isEmpty())
                return null;
            AttachmentKind parsedKind = parseKind(kind);
            if (parsedKind == null)
                return null;
            AttachmentWithId withId = new AttachmentWithId();
            withId.setUuid(uuid.trim());
            Attachment att = new Attachment();
            att.setKind(parsedKind);
            att.setPath(normalizedPath);
            att.setDescription(description == null ? "" : description);
            withId.setAttachment(att);
            return withId;
        }

        private static AttachmentKind parseKind(String raw) {
            if (raw == null)
                return null;
            String trimmed = raw.trim();
            if (trimmed.isEmpty())
                return null;
            for (AttachmentKind candidate : AttachmentKind.values()) {
                if (candidate.name().equalsIgnoreCase(trimmed))
                    return candidate;
            }
            return null;
        }
    }
}
