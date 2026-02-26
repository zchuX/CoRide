package com.coride.lambda.util

import java.net.{HttpURLConnection, URL}
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Base64

import com.fasterxml.jackson.databind.ObjectMapper

object JwtUtils {
  private val mapper = new ObjectMapper()

  private def base64UrlToBase64(s: String): String = {
    val replaced = s.replace('-', '+').replace('_', '/')
    val padLen = (4 - (replaced.length % 4)) % 4
    replaced + ("=" * padLen)
  }

  private def b64UrlDecode(s: String): Array[Byte] = Base64.getDecoder.decode(base64UrlToBase64(s))

  private def readJson(bytes: Array[Byte]) = mapper.readTree(bytes)

  case class VerifiedClaims(sub: String, username: Option[String], email: Option[String] = None)
}

/**
 * Minimal JWT verifier for Cognito ID tokens (RS256).
 * - Fetches JWKs from the Cognito well-known endpoint
 * - Verifies signature with RSA public key
 * - Validates iss, aud, token_use, and exp
 */
class JwtUtils(userPoolId: String, region: String, clientId: String) {
  import JwtUtils._

  private val issuer = s"https://cognito-idp.$region.amazonaws.com/$userPoolId"
  private val jwksUrl = s"$issuer/.well-known/jwks.json"

  @volatile private var jwksCache: Map[String, (String, String)] = Map.empty // kid -> (n, e)

  private def fetchJwks(): Map[String, (String, String)] = {
    val conn = new URL(jwksUrl).openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(3000)
    val is = conn.getInputStream
    val node = JwtUtils.mapper.readTree(is)
    val keys = node.get("keys")
    val it = keys.elements()
    var map = Map.empty[String, (String, String)]
    while (it.hasNext) {
      val k = it.next()
      val kid = k.get("kid").asText()
      val n = k.get("n").asText()
      val e = k.get("e").asText()
      map += (kid -> (n, e))
    }
    jwksCache = map
    map
  }

  private def getKey(kid: String): Option[(String, String)] = {
    val current = if (jwksCache.isEmpty) fetchJwks() else jwksCache
    current.get(kid).orElse({
      fetchJwks()
      jwksCache.get(kid)
    })
  }

  def verifyIdToken(token: String): Option[VerifiedClaims] = {
    // Development bypass: when DEBUG_BYPASS_JWT=true, accept tokens of the form "debug:<sub>"
    // This is only intended for non-production testing to reach protected handlers without Cognito.
    if (Option(System.getenv("DEBUG_BYPASS_JWT")).exists(_.equalsIgnoreCase("true"))) {
      if (token != null && token.startsWith("debug:")) {
        val sub = token.stripPrefix("debug:")
        return Some(VerifiedClaims(sub = sub, username = None, email = None))
      }
    }

    val parts = token.split("\\.")
    if (parts.length != 3) return None

    val headerJson = readJson(b64UrlDecode(parts(0)))
    val payloadJson = readJson(b64UrlDecode(parts(1)))
    val sigBytes = b64UrlDecode(parts(2))

    val alg = Option(headerJson.get("alg")).map(_.asText()).getOrElse("")
    val kid = Option(headerJson.get("kid")).map(_.asText()).getOrElse("")
    val tokenUse = Option(payloadJson.get("token_use")).map(_.asText()).getOrElse("")
    val audience = Option(payloadJson.get("aud")).map(_.asText()).getOrElse("")
    val iss = Option(payloadJson.get("iss")).map(_.asText()).getOrElse("")
    val exp = Option(payloadJson.get("exp")).map(_.asLong()).getOrElse(0L)
    val nowSec = System.currentTimeMillis() / 1000L

    if (alg != "RS256" || tokenUse != "id" || iss != issuer || audience != clientId || exp <= nowSec) return None

    val keyOpt = getKey(kid)
    if (keyOpt.isEmpty) return None
    val (nStr, eStr) = keyOpt.get
    val n = new BigInteger(1, b64UrlDecode(nStr))
    val e = new BigInteger(1, b64UrlDecode(eStr))
    val spec = new RSAPublicKeySpec(n, e)
    val kf = KeyFactory.getInstance("RSA")
    val pub = kf.generatePublic(spec)

    val sig = Signature.getInstance("SHA256withRSA")
    sig.initVerify(pub)
    sig.update((parts(0) + "." + parts(1)).getBytes("UTF-8"))
    val ok = sig.verify(sigBytes)
    if (!ok) return None

    val sub = Option(payloadJson.get("sub")).map(_.asText())
    val username = Option(payloadJson.get("cognito:username")).map(_.asText())
    val email = Option(payloadJson.get("email")).map(_.asText()).filter(_.nonEmpty)
    sub.map(s => VerifiedClaims(s, username, email))
  }
}