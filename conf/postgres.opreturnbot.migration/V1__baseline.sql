CREATE TABLE invoices
(
    r_hash      TEXT PRIMARY KEY NOT NULL,
    invoice     TEXT             NOT NULL,
    message     TEXT             NOT NULL,
    hash        BOOLEAN          NOT NULL,
    fee_rate    TEXT             NOT NULL,
    transaction TEXT,
    txid        TEXT,
    profit      INTEGER,
    chain_fee   INTEGER,
    node_id     TEXT,
    telegram_id BIGINT,
    closed      BOOLEAN          NOT NULL DEFAULT false
);

CREATE INDEX txid_index on invoices (txid);
CREATE INDEX closed_index on invoices (closed);

CREATE TABLE nip5
(
    r_hash     TEXT PRIMARY KEY NOT NULL,
    name       TEXT             NOT NULL,
    public_key TEXT             NOT NULL,
    constraint nip5_fk foreign key (r_hash) references invoices (r_hash) on update NO ACTION on delete NO ACTION
);

CREATE INDEX nip5_name_idx ON nip5 (name);

CREATE TABLE zaps
(
    r_hash  TEXT PRIMARY KEY NOT NULL,
    invoice TEXT UNIQUE      NOT NULL,
    my_key  TEXT             NOT NULL,
    amount  INTEGER          NOT NULL,
    request TEXT             NOT NULL,
    note_id TEXT UNIQUE
);
