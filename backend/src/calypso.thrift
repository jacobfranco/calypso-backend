namespace java now.calypso.backend.data

typedef i64 AccountId
typedef i64 Timestamp
typedef i64 Index

enum AttachmentKind {
  Image = 1,
  Video = 2
}

enum Importance {
  NOT_IMPORTANT = 1,
  PREFERENCE    = 2,
  DEALBREAKER   = 3
}

enum LocationScope {
  NEARBY    = 1,
  COUNTRY   = 2,
  WORLDWIDE = 3
}

enum DistanceUnit {
  KM = 1,
  MI = 2
}

struct Application {
  1: required string client_id
  2: required string client_secret
  3: required string name
  4: required string redirect_uri
  5: required string scopes
}

struct Account {
  1: required string name;
  2: required string phone_number;
  3: required string locale;
  4: required string uuid;
  5: required string publicKey;
  6: required Timestamp timestamp;
  7: required bool admin;
  8: optional AttachmentWithId avatar;
  9: optional string birthday;
}

struct AccountWithId {
  1: required AccountId accountId;
  2: required Account account;
}

struct IndexedAccountWithId {
  1: required Index index;
  2: required AccountWithId accountWithId;
}

struct AddAuthCode {
  1: required string code;
  2: required AccountId accountId;
}

struct RemoveAuthCode {
  1: required string code;
}

struct Attachment {
  1: required AttachmentKind kind;
  2: required string path;
  3: required string description;
}

struct AttachmentWithId {
  1: required string uuid;
  2: required Attachment attachment;
}

struct Filters {
  1: required AccountId accountId;
  2: required ModeFilter relationshipMode;         // focused, balanced, exploratory
  3: required OneToManyFilter gender;              // self + seeking genders
  4: required RangeFilter age;                     // self + desired age range
  5: required LocationFilter location;             // self location + scope (e.g. city, state)
  6: optional OneToManyFilter religion;            // self label + importance level
  7: optional OneToManyFilter politics;            // same as above
  8: optional ManyToManyFilter lifestyle;          // tags like "vegan", "non-drinker"
}

// For filters like gender, religion, politics
struct OneToManyFilter {
  1: optional string self;              // Your own label (e.g. "female", "agnostic")
  2: optional list<string> seeking;     // Who you're open to (e.g. ["male", "nonbinary"])
  3: optional Importance importance;        // "not_important", "preference", "dealbreaker"
}

// For numeric ranges like age
struct RangeFilter {
  1: optional i32 self;                 // e.g. 26
  2: optional i32 min;                  // e.g. 22
  3: optional i32 max;                  // e.g. 30
  4: optional Importance importance;        // optional: how strict this is
}

// For tag-based filters (lifestyle)
struct ManyToManyFilter {
  1: optional list<string> self;        // e.g. ["vegan", "night owl"]
  2: optional list<TagPreference> preferences; // per-tag importance
}

struct TagPreference {
  1: required string tag;
  2: required Importance importance;        // "not_important", "preference", "dealbreaker"
}

struct LocationFilter {
  1: required double lat;        // e.g. 35.2271
  2: required double lon;        // e.g. -80.8431
  3: required double radiusKm;   // numeric radius in kilometers
  4: optional Importance importance; // (keep if you want for later)
  5: optional LocationScope scope;   // nearby/country/worldwide
  6: optional string countryCode;    // ISO-3166 alpha-2 (e.g. "US")
  7: optional DistanceUnit distanceUnit; // display-only unit for nearby
}

struct ModeFilter {
  1: required string self;              // "focused", "balanced", "exploratory"
}

enum SignalIntent {
  SELF = 1,
  SEEKING = 2,
  META = 3,
  BOTH = 4 // Both self + seeking, TODO maybe change this later
}

struct SignalRecord {
  1: required string token;
  2: optional string source;
  3: optional Timestamp firstSeen;
  4: optional Timestamp lastSeen;
  5: optional i32 count;
  6: optional string lastContext;
  7: optional SignalIntent intent;
 10: optional string sourceId; // pointer back to the originating entity
  11: optional double valence; // -1..1, direction (dislike..like)
  12: optional string rawToken; // normalized raw extracted token before canonical resolution
  13: optional string canonicalToken; // canonical concept token used for scoring
}

struct Signals {
  1: required AccountId accountId;    // owner
  2: optional list<SignalRecord> records;
}

enum PromptReaction {
  LIKE = 1,
  DISLIKE = 2,
  SKIP = 3
}

enum PromptBankKind {
  PUBLIC = 1,
  PRIVATE = 2
}

struct PromptDefinition {
  1: required string promptId;      // stable string, e.g. "public_001"
  2: required PromptBankKind bank;
  3: required string text;
  4: optional string topic;
  5: optional list<string> tags;
  6: optional i32 version;          // optional: bump when wording changes
}

struct PublicPromptAnswer {
  1: required string answerId;    // uuid
  2: required AccountId accountId; // author (server-only exposure)
  3: required string promptId;
  4: required string body;
  5: required Timestamp createdAt;
  6: required Timestamp updatedAt;
  7: optional bool deleted;       // soft delete
  8: optional list<string> signalTokens;
}

struct PublicPromptFeedCard {
  1: required string answerId;
  2: required string promptId;
  3: required string promptText;
  4: required string body;
  5: required Timestamp createdAt;
}

struct PublicPromptReactionEvent {
  1: required AccountId viewerAccountId;
  2: required string answerId;
  3: required string promptId;
  4: required PromptReaction reaction;
  5: required Timestamp reactedAt;
}

struct PublicPromptSelection {
  1: required AccountId accountId;
  2: required list<string> selectedPromptIds;
  3: required Timestamp updatedAt;
}

enum PrivatePromptStatus {
  ACTIVE = 1,
  ANSWERED = 2,
  SKIPPED = 3,
  SNOOZED = 4
}

struct PrivatePromptAssignment {
  1: required string instanceId;
  2: required AccountId accountId;
  3: required string promptId;
  4: required Timestamp scheduledAt;
  5: optional Timestamp surfacedAt;
  6: optional Timestamp completedAt;
  7: required PrivatePromptStatus status;
  8: optional Timestamp snoozeUntil;
}

struct PrivatePromptAnswer {
  1: required string instanceId;
  2: required AccountId accountId;
  3: required string promptId;
  4: required string body;
  5: required Timestamp answeredAt;
  6: optional list<string> signalTokens;
}

struct ActivePrivatePrompt {
  1: required PrivatePromptAssignment assignment;
  2: required PromptDefinition prompt;
  3: optional PrivatePromptAnswer answer;
}

enum AgentMessageSender {
  USER = 1,
  AGENT = 2
}

struct AgentMessage {
  1: required string messageId;
  2: required string sessionId;
  3: required AgentMessageSender sender;
  4: required string text;
  5: required Timestamp timestamp;
}

enum AgentSessionStatus {
  ACTIVE = 1,
  CLOSED = 2
}

struct AgentSession {
  1: required string sessionId;
  2: required AccountId accountId;
  3: required Timestamp createdAt;
  4: required Timestamp lastInteractionAt;
  5: required AgentSessionStatus status;
  6: optional list<AgentMessage> messages;
}

struct MatchCandidate {
  1: required AccountId targetAccountId;
  2: required double    stage0Score;
  3: optional list<string> reasons;
  4: required Timestamp computedAt;
}

struct MatchRefillRequest {
  1: required AccountId accountId;  // viewer
  2: required i32       targetSize; // how many to scan this refill
}

struct ServedPairs {
  1: required AccountId        accountId;  // viewer
  2: required list<AccountId>  targetIds;  // served targets (in order)
  3: required Timestamp        servedAt;   // server time
}

struct CursorAck {
  1: required AccountId accountId;
  2: required i32       lastIndex;
  3: required bool      wrappedOnce;
}

struct DirectMessage {
  1: required string    messageId;
  2: required AccountId senderId;
  3: required AccountId receiverId;
  4: required string    text;
  5: required Timestamp sentAt;
}
