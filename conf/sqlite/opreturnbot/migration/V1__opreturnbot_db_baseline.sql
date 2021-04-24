CREATE TABLE IF NOT EXISTS "invoices"
(
    "r_hash"      VARCHAR(254) NOT NULL,
    "invoice"     VARCHAR(254) NOT NULL,
    "message"     VARCHAR(254) NOT NULL,
    "hash"        INTEGER      NOT NULL,
    "fee_rate"    VARCHAR(254) NOT NULL,
    "transaction" VARCHAR(254),
    "txid"        VARCHAR(254),
    PRIMARY KEY ("r_hash")
);
