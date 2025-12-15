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

struct Account {
  1: required string name;
  2: required string email;
  3: required string pwdHash;
  4: required string locale;
  5: required string uuid;
  6: required string publicKey; 
  7: required Timestamp timestamp;
  8: required bool admin;
  9: optional AttachmentWithId avatar;
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
  2: required ModeFilter relationshipMode;         // casual, serious
  3: required OneToManyFilter gender;              // self + seeking genders
  4: required RangeFilter age;                     // self + desired age range
  5: required LocationFilter location;             // self location + scope (e.g. city, state)
  6: optional OneToManyFilter religion;            // self label + importance level
  7: optional OneToManyFilter politics;            // same as above
  8: optional ManyToManyFilter lifestyle;          // tags like "vegan", "non-drinker"
  9: optional ManyToManyFilter interests;          // tags like "video games", "surfing"
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

// For tag-based filters (lifestyle, interests)
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
}

struct ModeFilter {
  1: required string self;              // "casual", "serious"
}

struct SignalRecord {
  1: required string token;
  2: optional string source;
  3: optional Timestamp firstSeen;
  4: optional Timestamp lastSeen;
  5: optional i32 count;
  6: optional string lastContext;
}

struct Signals {
  1: required AccountId accountId;    // owner
  2: optional list<SignalRecord> records;
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
