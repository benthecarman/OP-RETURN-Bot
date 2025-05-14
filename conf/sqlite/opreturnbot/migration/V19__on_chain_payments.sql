CREATE TABLE "on_chain_payments"
(
    "address"                TEXT PRIMARY KEY NOT NULL,
    "op_return_request_id"   INTEGER          NOT NULL,
    "expected_amount"        INTEGER          NOT NULL,
    "amount_paid"            INTEGER,
    "txid"                   TEXT,
    FOREIGN KEY ("op_return_request_id") REFERENCES "op_return_requests" ("id") ON DELETE CASCADE ON UPDATE NO ACTION
);

CREATE UNIQUE INDEX on_chain_payments_op_return_request_id_idx ON "on_chain_payments" (op_return_request_id);
CREATE INDEX on_chain_payments_txid_idx ON "on_chain_payments" (txid);
