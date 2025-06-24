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
  10: optional Preferences preferences;
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

struct Preferences {
  1: optional Filters filters;
}

struct Filters {
  1: optional OneToManyFilter gender;
  2: optional RangeFilter age;
  3: optional OneToManyFilter religion;
  4: optional OneToManyFilter politics;
  5: optional ManyToManyFilter lifestyle;
  6: optional ManyToManyFilter interests;
}

struct OneToManyFilter {
  1: optional string self;              // e.g. "male"
  2: optional list<string> seeking;     // e.g. ["female", "nonbinary"]
}

struct RangeFilter {
  1: optional i32 self;                 // e.g. 29
  2: optional i32 min;                  // e.g. 24
  3: optional i32 max;                  // e.g. 35
}

struct ManyToManyFilter {
  1: optional list<string> self;        // e.g. ["vegan", "night owl"]
  2: optional list<string> mustHave;    // tags partner must have
  3: optional list<string> niceToHave;  // tags that would be a plus
}
