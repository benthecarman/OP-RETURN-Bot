package controllers

import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Unit tests for the agent-facing well-known endpoints. These do not require a
  * running Play application.
  */
class WellKnownControllerTest extends PlaySpec {

  val controller = new WellKnownController(Helpers.stubControllerComponents())

  "WellKnownController" must {

    "serve a sitemap" in {
      val result = controller.sitemap(FakeRequest())

      status(result) mustBe OK
      contentType(result) mustBe Some("application/xml")
      contentAsString(result) must include(
        "<loc>https://opreturnbot.com/</loc>")
    }

    "serve auth.md as markdown" in {
      val result = controller.authMd(FakeRequest())

      status(result) mustBe OK
      contentType(result) mustBe Some("text/markdown")
      contentAsString(result) must include("does not use authentication")
    }

    "serve an RFC 9727 API catalog linkset" in {
      val result = controller.apiCatalog(FakeRequest())

      status(result) mustBe OK
      contentType(result) mustBe Some("application/linkset+json")
      val linkset = (contentAsJson(result) \ "linkset")(0)
      (linkset \ "anchor").as[String] mustBe "https://opreturnbot.com/"
    }

    "serve protected resource metadata with no authorization servers" in {
      val result = controller.oauthProtectedResource(FakeRequest())

      status(result) mustBe OK
      contentType(result) mustBe Some("application/json")
      val json = contentAsJson(result)
      (json \ "resource").as[String] mustBe "https://opreturnbot.com/"
      (json \ "authorization_servers").as[Seq[String]] mustBe empty
    }

    "serve an agent skills index with a matching digest" in {
      val indexResult = controller.agentSkillsIndex(FakeRequest())

      status(indexResult) mustBe OK
      val skill = (contentAsJson(indexResult) \ "skills")(0)
      (skill \ "name").as[String] mustBe "op-return-bot"
      (skill \ "type").as[String] mustBe "skill-md"

      val skillResult =
        controller.agentSkill("op-return-bot")(FakeRequest())
      status(skillResult) mustBe OK
      contentType(skillResult) mustBe Some("text/markdown")

      val sha = MessageDigest
        .getInstance("SHA-256")
        .digest(contentAsString(skillResult).getBytes(StandardCharsets.UTF_8))
        .map("%02x".format(_))
        .mkString
      (skill \ "digest").as[String] mustBe s"sha256:$sha"
    }

    "reject unknown skill names" in {
      val result = controller.agentSkill("not-a-skill")(FakeRequest())

      status(result) mustBe NOT_FOUND
    }
  }
}
