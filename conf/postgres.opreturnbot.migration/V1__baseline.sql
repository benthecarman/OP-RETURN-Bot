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
    closed      BOOLEAN          NOT NULL DEFAULT false
);