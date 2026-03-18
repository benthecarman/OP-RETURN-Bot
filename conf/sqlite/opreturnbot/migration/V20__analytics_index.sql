CREATE INDEX IF NOT EXISTS op_return_requests_analytics_index ON op_return_requests(time, txid, profit, chain_fee, vsize);
