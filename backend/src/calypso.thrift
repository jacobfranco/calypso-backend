namespace java now.calypso.backend.data

typedef i64 AccountId
typedef i64 Timestamp
typedef i64 Index

struct Account {
  1: required string name;
  2: required string email;
  3: required string pwdHash;
  4: required string locale;
  5: required string uuid;
  6: required string publicKey; 
  7: required Timestamp timestamp;
  8: required bool admin;
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