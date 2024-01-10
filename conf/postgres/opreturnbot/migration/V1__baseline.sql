-- invoices table
CREATE TABLE invoices
(
    r_hash      TEXT PRIMARY KEY,
    invoice     TEXT    NOT NULL,
    message     TEXT    NOT NULL,
    hash        BOOLEAN NOT NULL,
    fee_rate    TEXT    NOT NULL,
    transaction TEXT,
    txid        TEXT,
    profit      INTEGER,
    chain_fee   INTEGER,
    node_id     TEXT,
    closed      BOOLEAN NOT NULL DEFAULT false,
    telegram_id INTEGER,
    nostr_key   TEXT,
    dvm_event   TEXT,
);

-- Indices for invoices table
CREATE INDEX txid_index ON invoices (txid);
CREATE INDEX closed_index ON invoices (closed);

-- nip5 table
CREATE TABLE nip5
(
    r_hash     TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    public_key TEXT NOT NULL,
    FOREIGN KEY (r_hash) REFERENCES invoices (r_hash) ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE INDEX nip5_name_idx ON nip5 (name);

CREATE TABLE zaps
(
    r_hash  TEXT PRIMARY KEY,
    invoice TEXT UNIQUE NOT NULL,
    my_key  TEXT        NOT NULL,
    amount  INTEGER     NOT NULL,
    request TEXT        NOT NULL,
    note_id TEXT UNIQUE
);
