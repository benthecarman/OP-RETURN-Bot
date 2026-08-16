package controllers

import play.api.libs.json.Json
import play.api.mvc._

import javax.inject.{Inject, Singleton}

/** Serves agent-facing well-known documents and metadata. */
@Singleton
class WellKnownController @Inject() (cc: ControllerComponents)
    extends AbstractController(cc) {

  def sitemap: Action[AnyContent] = Action {
    Ok(AgentContent.sitemapXml).as("application/xml; charset=utf-8")
  }

  def authMd: Action[AnyContent] = Action {
    Ok(AgentContent.authMd).as("text/markdown; charset=utf-8")
  }

  /** RFC 9727 API catalog as a JSON linkset. */
  def apiCatalog: Action[AnyContent] = Action {
    val linkset = Json.obj(
      "linkset" -> Json.arr(
        Json.obj(
          "anchor" -> "https://opreturnbot.com/",
          "service-desc" -> Json.arr(
            Json.obj(
              "href" -> "https://opreturnbot.com/.well-known/mcp.json",
              "type" -> "application/json"
            )
          ),
          "service-doc" -> Json.arr(
            Json.obj(
              "href" -> "https://github.com/benthecarman/OP-RETURN-Bot/blob/master/docs/API.md",
              "type" -> "text/markdown"
            )
          )
        )
      )
    )
    Ok(linkset).as("application/linkset+json")
  }

  /** Agent Skills discovery index, see
    * https://github.com/cloudflare/agent-skills-discovery-rfc
    */
  def agentSkillsIndex: Action[AnyContent] = Action {
    val index = Json.obj(
      "$schema" -> "https://schemas.agentskills.io/discovery/0.2.0/schema.json",
      "skills" -> Json.arr(
        Json.obj(
          "name" -> "op-return-bot",
          "type" -> "skill-md",
          "description" -> "Write arbitrary messages to the Bitcoin blockchain as OP_RETURN outputs via the OP_RETURN Bot REST API or MCP server. Use when a task requires timestamping data, anchoring a message, or storing bytes permanently on Bitcoin.",
          "url" -> "/.well-known/agent-skills/op-return-bot/SKILL.md",
          "digest" -> s"sha256:${AgentContent.skillSha256}"
        )
      )
    )
    Ok(index).as("application/json")
  }

  def agentSkill(name: String): Action[AnyContent] = Action {
    if (name == "op-return-bot") {
      Ok(AgentContent.skillMd).as("text/markdown; charset=utf-8")
    } else {
      NotFound
    }
  }
}
