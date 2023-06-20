package akka.http.scaladsl.model

import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.headers.`Content-Type`;

class OverrideHttpParser {
  HttpHeader.parse("Content-Type", "application/json;charset=\"UTF-8\"",
    akka.http.impl.model.parser.HeaderParser.Settings(customMediaTypes = (main: String, sub: String) ⇒ Some(MediaType.customWithOpenCharset(main, sub)))) match {
    case ParsingResult.Ok(`Content-Type`(contentType), _) ⇒ contentType
  }
}
