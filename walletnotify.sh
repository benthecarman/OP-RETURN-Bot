#!/bin/sh
curl -H "Content-Type: application/json" \
     -d "{\"txid\":\"$1\", \"key\":\"$ORB_ADMIN_KEY\"}" \
     http://127.0.0.1:9000/admin/walletnotify