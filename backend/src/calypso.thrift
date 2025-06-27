namespace java now.calypso.backend.data

typedef i64 AccountId
typedef i64 Timestamp
typedef i64 Index

enum AttachmentKind {
  Image = 1,
  Video = 2
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
  1: required string accountId;
  2: optional ModeFilter relationshipMode;         // casual, serious, open to both
  3: optional OneToManyFilter gender;              // self + seeking genders
  4: optional RangeFilter age;                     // self + desired age range
  5: optional LocationFilter location;             // self location + scope (e.g. city, state)
  6: optional OneToManyFilter religion;            // self label + importance level
  7: optional OneToManyFilter politics;            // same as above
  8: optional ManyToManyFilter lifestyle;          // tags like "vegan", "non-drinker"
  9: optional ManyToManyFilter interests;          // tags like "video games", "surfing"
}

// For filters like gender, religion, politics
struct OneToManyFilter {
  1: optional string self;              // Your own label (e.g. "female", "agnostic")
  2: optional list<string> seeking;     // Who you're open to (e.g. ["male", "nonbinary"])
  3: optional string importance;        // "not_important", "preference", "dealbreaker"
}

// For numeric ranges like age
struct RangeFilter {
  1: optional i32 self;                 // e.g. 26
  2: optional i32 min;                  // e.g. 22
  3: optional i32 max;                  // e.g. 30
  4: optional string importance;        // optional: how strict this is
}

// For tag-based filters (lifestyle, interests)
struct ManyToManyFilter {
  1: optional list<string> self;        // e.g. ["vegan", "night owl"]
  2: optional list<TagPreference> preferences; // per-tag importance
}

struct TagPreference {
  1: required string tag;
  2: required string importance;        // "not_important", "preference", "dealbreaker"
}

struct LocationFilter {
  1: optional string city;              // e.g. "Charlotte, NC"
  2: optional string radius;            // e.g. "my_city", "my_state", "worldwide"
  3: optional string importance;        // optional: for scoring
}

struct ModeFilter {
  1: required string self;              // "casual", "serious"
}

