package info.kwarc.mmt.MitM.Config

import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.utils._

/** configuration for the MitM Architecture */
case class MitMConfig(gap: MitMSystemLocation, singular: MitMSystemLocation, sage: MitMSystemLocation) {
  /** gets the location of a given system */
  def apply(id: String): MitMSystemLocation = id.toLowerCase match {
    case "gap" => gap
    case "singular" => singular
    case "sage" => sage
    case _ => throw new Exception("Unknown System")
  }
}

object MitMConfig {
  lazy val default: MitMConfig = apply(MMTSystem.getResourceAsString("MitM/config.default.json"))
  def apply(config: String): MitMConfig = apply(JSON.parse(config).asInstanceOf[JSONObject])
  def apply(json: JSONObject): MitMConfig = {
    val parser = new JSONObjectParser(json)

    implicit val converter: JSONConverter[MitMSystemLocation] = MitMSystemLocation
    MitMConfig(parser.take("gap"), parser.take("singular"), parser.take("sage"))
  }
}

/** Represents the location of a single MitM SCSCP System */
case class MitMSystemLocation(hostname: String, port: Int)
object MitMSystemLocation extends JSONConverter[MitMSystemLocation] {
  def toJSON(obj: MitMSystemLocation): JSON = JSONString(obj.hostname + ":" + obj.port)
  def fromJSONOption(j: JSON): Option[MitMSystemLocation] = j match {
    case JSONString(s) => Some(apply(s))
    case _ => None
  }

  val pattern = "^([^\\:]+)(?:\\:(\\d+))?$".r
  def apply(spec: String): MitMSystemLocation = {
    val (hn, port) = pattern
    .findFirstMatchIn(spec)
    .map({m => (m.group(1), m.group(2))})
    .getOrElse((spec, null))
    MitMSystemLocation(hn, Option(port).getOrElse("26133").toInt)
  }
}