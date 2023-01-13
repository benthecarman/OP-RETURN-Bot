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
