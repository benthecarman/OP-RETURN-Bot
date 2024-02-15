# API

OP_RETURN bot has an api to allow for other applications to implement the bot's functionality. The api is a simple
RESTful api that allows for the bot to be controlled from other applications.

## Endpoints

### POST /api/create

Creates an invoice for the message to be OP_RETURN.

#### Request

`message` is the message you want in the transaction. `noTwitter` is a boolean that will disable the bot from sending
the message to twitter or nostr. (Twitter is currently broken).

```json
{
  "message": "Hello, World!",
  "noTwitter": false
}
```

### Response

Raw bolt 11 invoice

### GET /api/status/:invoice

Gets the status of the invoice.

### Response

txid of the transaction, 400 error if unpaid
