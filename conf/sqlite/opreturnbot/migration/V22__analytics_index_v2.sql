DROP INDEX IF EXISTS op_return_requests_analytics_index;
CREATE INDEX op_return_requests_analytics_index ON op_return_requests(time, txid, profit, chain_fee, vsize, btc_price);
