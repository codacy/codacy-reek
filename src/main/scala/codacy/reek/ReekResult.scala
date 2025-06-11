package codacy.reek

import play.api.libs.json._
import scala.collection.mutable

case class ReekLocation(line: Int, column: JsValue = JsNull, length: JsValue = JsNull)

case class ReekOffense(
                        severity: JsValue = JsString("Warning"),
                        message: JsString,
                        cop_name: JsString,
                        corrected: JsValue = JsBoolean(false),
                        location: ReekLocation
                      )

case class ReekFiles(path: JsString, offenses: Option[List[ReekOffense]])

// ⚠️ Wrap Reek's top-level array of smells into our existing model
case class ReekResult(files: Option[List[ReekFiles]])

object ReekResult {
  implicit val RLocation: Format[ReekLocation] = Json.format[ReekLocation]
  implicit val ROffense: Format[ReekOffense] = Json.format[ReekOffense]
  implicit val RFiles: Format[ReekFiles] = Json.format[ReekFiles]

  // Custom reads for ReekResult to map Reek-style JSON to your existing structure
  implicit val RResult: Reads[ReekResult] = Reads { json =>
    json.validate[JsArray].map { array =>
      val grouped = mutable.Map.empty[String, List[ReekOffense]]

      array.value.foreach { js =>
        val context = (js \ "context").asOpt[String].getOrElse("UnknownContext")
        val path = (js \ "source").asOpt[String].getOrElse("unknown_file.rb")
        val lines = (js \ "lines").asOpt[List[Int]].getOrElse(List(1))
        val message = (js \ "message").as[String]
        val smellType = (js \ "smell_type").as[String]

        val offense = ReekOffense(
          message = JsString(message),
          cop_name = JsString(smellType),
          location = ReekLocation(line = lines.headOption.getOrElse(1))
        )

        val existing = grouped.getOrElse(path, List.empty)
        grouped.update(path, offense :: existing)
      }

      val files = grouped.map {
        case (path, offenses) => ReekFiles(JsString(path), Some(offenses.reverse))
      }.toList

      ReekResult(files = Some(files))
    }
  }
}
