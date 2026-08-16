package controllers

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Static agent-facing documents served as markdown, XML, or JSON. */
object AgentContent {

  val indexMarkdown: String =
    """# OP_RETURN Bot
      |
      |Write messages to the Bitcoin blockchain via OP_RETURN outputs. You only
      |need to pay the fees, with a Lightning or on-chain Bitcoin payment.
      |
      |## For agents
      |
      |- MCP server (streamable HTTP): `POST /mcp`, discovery document at
      |  `/.well-known/mcp.json`
      |- REST API: `POST /api/create`, `POST /api/unified`,
      |  `GET /api/status/{rHash}`, `GET /api/view/{txId}`,
      |  `GET /api/mempool-limit`. Documentation:
      |  https://github.com/benthecarman/OP-RETURN-Bot/blob/master/docs/API.md
      |- API catalog (RFC 9727 linkset): `/.well-known/api-catalog`
      |- Agent Skills index: `/.well-known/agent-skills/index.json`
      |- Authentication: none required, payment is per request. See `/auth.md`
      |
      |## Pages
      |
      |- `/` — create an OP_RETURN request
      |- `/nip5` — register a NIP-05 nostr identifier
      |- `/connect` — connect to the OP_RETURN Bot Lightning node
      |""".stripMargin

  val authMd: String =
    """# Authentication
      |
      |OP_RETURN Bot does not use authentication. There are no API keys, no
      |OAuth, and no bearer tokens. All REST API and MCP endpoints are public.
      |
      |Payment replaces authentication: a request to create an OP_RETURN returns
      |a Lightning invoice (or a unified Lightning + on-chain payment request).
      |The message is written to the blockchain after the invoice is paid.
      |""".stripMargin

  val skillMd: String =
    """---
      |name: op-return-bot
      |description: Write arbitrary messages to the Bitcoin blockchain as OP_RETURN outputs via the OP_RETURN Bot REST API or MCP server. Use when a task requires timestamping data, anchoring a message, or storing bytes permanently on Bitcoin.
      |license: MIT
      |---
      |
      |# OP_RETURN Bot
      |
      |OP_RETURN Bot writes a message to the Bitcoin blockchain in an OP_RETURN
      |output. The user pays the transaction fees with a Lightning or on-chain
      |Bitcoin payment. No authentication is required.
      |
      |## MCP server
      |
      |Prefer the MCP server when available:
      |
      |- URL: `https://opreturnbot.com/mcp` (streamable HTTP transport)
      |- Discovery document: `https://opreturnbot.com/.well-known/mcp.json`
      |- Tools: `create_op_return`, `create_unified_payment`,
      |  `check_payment_status`, `view_message`
      |
      |## REST API
      |
      |1. Create a payment request with `POST /api/create` (Lightning only) or
      |   `POST /api/unified` (Lightning + on-chain). Send form-encoded or JSON
      |   fields `message` (max 99,000 bytes) and optional `noTwitter` (boolean,
      |   default false, disables posting to social media).
      |2. Give the returned invoice or payment request to the user to pay.
      |3. Poll `GET /api/status/{rHash}` with the payment hash until the
      |   transaction is broadcast and confirmed.
      |4. Read the written message with `GET /api/view/{txId}`.
      |
      |Full API documentation:
      |https://github.com/benthecarman/OP-RETURN-Bot/blob/master/docs/API.md
      |""".stripMargin

  val skillSha256: String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest
      .digest(skillMd.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString
  }

  val sitemapXml: String =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
      |  <url><loc>https://opreturnbot.com/</loc></url>
      |  <url><loc>https://opreturnbot.com/nip5</loc></url>
      |  <url><loc>https://opreturnbot.com/connect</loc></url>
      |</urlset>
      |""".stripMargin
}
