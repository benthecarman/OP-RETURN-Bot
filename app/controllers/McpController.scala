package controllers

import grizzled.slf4j.Logging
import play.api.libs.json._
import play.api.mvc._
import scodec.bits.ByteVector

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.Try

@Singleton
class McpController @Inject() (
    controller: Controller,
    cc: ControllerComponents
) extends AbstractController(cc)
    with Logging {

  import controller.{
    config,
    ec,
    invoiceDAO,
    invoiceMonitor,
    onChainDAO,
    opReturnDAO
  }

  private val corsHeaders = Seq(
    "Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers" -> "Content-Type, Accept"
  )

  def discovery: Action[AnyContent] = Action {
    val json = Json.obj(
      "name" -> "OP_RETURN Bot",
      "description" -> "Write messages to the Bitcoin blockchain via OP_RETURN outputs",
      "url" -> "https://opreturnbot.com/mcp",
      "transport" -> Json.obj(
        "type" -> "streamable-http",
        "url" -> "/mcp"
      )
    )
    Ok(json)
      .withHeaders(corsHeaders: _*)
  }

  def mcpOptions: Action[AnyContent] = Action {
    NoContent.withHeaders(corsHeaders: _*)
  }

  def mcp: Action[JsValue] = Action(parse.json).async { request =>
    val body = request.body
    val method = (body \ "method").as[String]
    val id = (body \ "id").asOpt[JsValue]

    method match {
      case "initialize" =>
        val result = Json.obj(
          "protocolVersion" -> "2025-03-26",
          "capabilities" -> Json.obj(
            "tools" -> Json.obj()
          ),
          "serverInfo" -> Json.obj(
            "name" -> "OP_RETURN Bot",
            "version" -> "1.0.0"
          )
        )
        Future.successful(
          jsonRpcResult(id, result)
            .withHeaders(corsHeaders: _*)
        )

      case "notifications/initialized" =>
        Future.successful(
          Accepted.withHeaders(corsHeaders: _*)
        )

      case "tools/list" =>
        val tools = Json.arr(
          toolDef(
            "create_op_return",
            "Create a Lightning invoice to write an OP_RETURN message on the Bitcoin blockchain",
            Json.obj(
              "type" -> "object",
              "properties" -> Json.obj(
                "message" -> Json.obj(
                  "type" -> "string",
                  "description" -> "The message to write (max 80 bytes)"
                ),
                "noTwitter" -> Json.obj(
                  "type" -> "boolean",
                  "description" -> "If true, do not post to Twitter",
                  "default" -> false
                )
              ),
              "required" -> Json.arr("message")
            )
          ),
          toolDef(
            "create_unified_payment",
            "Create a unified payment (Lightning + on-chain) for an OP_RETURN message",
            Json.obj(
              "type" -> "object",
              "properties" -> Json.obj(
                "message" -> Json.obj(
                  "type" -> "string",
                  "description" -> "The message to write (max 80 bytes)"
                ),
                "noTwitter" -> Json.obj(
                  "type" -> "boolean",
                  "description" -> "If true, do not post to Twitter",
                  "default" -> false
                )
              ),
              "required" -> Json.arr("message")
            )
          ),
          toolDef(
            "check_payment_status",
            "Check the payment and broadcast status of an OP_RETURN request",
            Json.obj(
              "type" -> "object",
              "properties" -> Json.obj(
                "rHash" -> Json.obj(
                  "type" -> "string",
                  "description" -> "The payment hash (r_hash) in hex"
                )
              ),
              "required" -> Json.arr("rHash")
            )
          ),
          toolDef(
            "view_message",
            "View the OP_RETURN message for a confirmed transaction",
            Json.obj(
              "type" -> "object",
              "properties" -> Json.obj(
                "txId" -> Json.obj(
                  "type" -> "string",
                  "description" -> "The transaction ID in hex"
                )
              ),
              "required" -> Json.arr("txId")
            )
          )
        )
        val result = Json.obj("tools" -> tools)
        Future.successful(
          jsonRpcResult(id, result)
            .withHeaders(corsHeaders: _*)
        )

      case "tools/call" =>
        val params = (body \ "params").as[JsObject]
        val name = (params \ "name").as[String]
        val args = (params \ "arguments")
          .asOpt[JsObject]
          .getOrElse(Json.obj())

        handleToolCall(name, args)
          .map { content =>
            jsonRpcResult(id, content)
              .withHeaders(corsHeaders: _*)
          }
          .recover { case e: Exception =>
            logger.error(s"MCP tool error: ${e.getMessage}", e)
            jsonRpcError(
              id,
              -32603,
              e.getMessage
            ).withHeaders(corsHeaders: _*)
          }

      case other =>
        Future.successful(
          jsonRpcError(id, -32601, s"Method not found: $other")
            .withHeaders(corsHeaders: _*)
        )
    }
  }

  private def handleToolCall(
      name: String,
      args: JsObject
  ): Future[JsObject] = {
    name match {
      case "create_op_return" =>
        val message = (args \ "message").as[String]
        val noTwitter =
          (args \ "noTwitter").asOpt[Boolean].getOrElse(false)
        val bytes = ByteVector(message.getBytes("UTF-8"))

        invoiceMonitor
          .createInvoice(
            message = bytes,
            noTwitter = noTwitter,
            nodeIdOpt = None,
            telegramId = None,
            nostrKey = None,
            dvmEvent = None
          )
          .map { case (invoiceDb, _) =>
            mcpContent(
              Json
                .obj(
                  "invoice" -> invoiceDb.invoice.toString(),
                  "rHash" -> invoiceDb.rHash.hex
                )
                .toString()
            )
          }

      case "create_unified_payment" =>
        val message = (args \ "message").as[String]
        val noTwitter =
          (args \ "noTwitter").asOpt[Boolean].getOrElse(false)
        val bytes = ByteVector(message.getBytes("UTF-8"))

        invoiceMonitor
          .createUnified(message = bytes, noTwitter = noTwitter)
          .map { case (invoiceDb, onChainDb, _) =>
            mcpContent(
              Json
                .obj(
                  "invoice" -> invoiceDb.invoice.toString(),
                  "address" -> onChainDb.address.toString(),
                  "amountSats" -> onChainDb.expectedAmount.satoshis.toLong,
                  "rHash" -> invoiceDb.rHash.hex
                )
                .toString()
            )
          }

      case "check_payment_status" =>
        val rHash = (args \ "rHash").as[String]
        val hash = org.bitcoins.crypto.Sha256Digest.fromHex(rHash)

        import slick.dbio.DBIOAction
        val action = for {
          opt <- invoiceDAO.findOpReturnRequestByRHashAction(hash)
          res <- opt match {
            case None => DBIOAction.successful(None)
            case Some((invoiceDb, requestDb)) =>
              onChainDAO
                .findByOpReturnRequestIdAction(
                  invoiceDb.opReturnRequestId
                )
                .map(o => Some((o, invoiceDb, requestDb)))
          }
        } yield res

        invoiceDAO.safeDatabase.run(action).map {
          case None =>
            mcpContent("Invoice not found")
          case Some((onChainOpt, invoiceDb, requestDb)) =>
            val status = requestDb.txIdOpt match {
              case Some(txId) =>
                Json.obj(
                  "status" -> "confirmed",
                  "txId" -> txId.hex,
                  "message" -> requestDb.getMessage
                )
              case None =>
                if (invoiceDb.paid) {
                  Json.obj(
                    "status" -> "paid",
                    "message" -> "Payment received, awaiting broadcast"
                  )
                } else {
                  onChainOpt match {
                    case Some(onChain) if onChain.txid.isDefined =>
                      Json.obj(
                        "status" -> "paid",
                        "message" -> "On-chain payment received, awaiting broadcast"
                      )
                    case _ =>
                      Json.obj(
                        "status" -> "unpaid",
                        "message" -> "Awaiting payment"
                      )
                  }
                }
            }
            mcpContent(status.toString())
        }

      case "view_message" =>
        val txIdStr = (args \ "txId").as[String]
        val txId = Try(
          org.bitcoins.crypto.DoubleSha256DigestBE(txIdStr)
        ).getOrElse(
          throw new IllegalArgumentException(
            s"Invalid txId: $txIdStr"
          )
        )

        opReturnDAO.findByTxId(txId).map {
          case None =>
            mcpContent(
              "Transaction not found or does not originate from OP_RETURN Bot"
            )
          case Some(db) =>
            mcpContent(
              Json
                .obj(
                  "txId" -> txIdStr,
                  "message" -> db.getMessage
                )
                .toString()
            )
        }

      case other =>
        Future.successful(
          mcpContent(s"Unknown tool: $other")
        )
    }
  }

  private def toolDef(
      name: String,
      description: String,
      inputSchema: JsObject
  ): JsObject = {
    Json.obj(
      "name" -> name,
      "description" -> description,
      "inputSchema" -> inputSchema
    )
  }

  private def mcpContent(text: String): JsObject = {
    Json.obj(
      "content" -> Json.arr(
        Json.obj("type" -> "text", "text" -> text)
      )
    )
  }

  private def jsonRpcResult(
      id: Option[JsValue],
      result: JsObject
  ): Result = {
    Ok(
      Json.obj(
        "jsonrpc" -> "2.0",
        "id" -> id.getOrElse[JsValue](JsNull),
        "result" -> result
      )
    )
  }

  private def jsonRpcError(
      id: Option[JsValue],
      code: Int,
      message: String
  ): Result = {
    Ok(
      Json.obj(
        "jsonrpc" -> "2.0",
        "id" -> id.getOrElse[JsValue](JsNull),
        "error" -> Json.obj(
          "code" -> code,
          "message" -> message
        )
      )
    )
  }
}
