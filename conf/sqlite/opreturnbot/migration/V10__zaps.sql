CREATE TABLE zaps
(
    r_hash  TEXT PRIMARY KEY NOT NULL,
    invoice TEXT UNIQUE      NOT NULL,
    my_key  TEXT             NOT NULL,
    amount  INTEGER          NOT NULL,
    request TEXT             NOT NULL,
    note_id TEXT UNIQUE
);
