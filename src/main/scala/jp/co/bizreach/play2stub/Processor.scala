package jp.co.bizreach.play2stub

import com.fasterxml.jackson.databind.ObjectMapper
import play.api.Play.current
import play.api.Logger
import play.api.http.{MimeTypes, HeaderNames}
import play.api.libs.ws.{WSResponse, WS}
import play.api.mvc._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

/**
 * Created by scova0731 on 10/19/14.
 */
trait Processor {

  def process(implicit request: Request[AnyContent],
             route:Option[StubRoute]): Option[Future[Result]]
}


/**
 * Process template rendering
 *
 * Currently, only string value is supported for extra parameters
 */
class TemplateProcessor extends Results with Processor {
  override def process(implicit request: Request[AnyContent],
                       route: Option[StubRoute]): Option[Future[Result]] = {

    val params = Stub.params.map{ case (k, v) => k -> v.toString }

    route
      .map(r => Stub.render(r.path, Some(r), Stub.json(r, params)))
      .getOrElse( Stub.render(request.path, None, Stub.json(request.path)))
      .map(result => Future { result } )

  }
}


/**
 * Check if HTML file exists in both cases
 */
class StaticHtmlProcessor extends Controller with Processor {

  def process(implicit request: Request[AnyContent],
             route:Option[StubRoute]): Option[Future[Result]] = {

    val path = route.map(_.path).getOrElse(request.path)
    Stub.html(path).map(html =>
      Future {
        Ok(html).withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.HTML)
      }
    )
  }
}


/**
 *
 */
class JsonProcessor extends Results with Processor {
  override def process(implicit request: Request[AnyContent],
                       route: Option[StubRoute]): Option[Future[Result]] = {

    Some(Future {
      route
        .map(r => Ok(Stub.json(r).toString))
        .getOrElse( Ok(Stub.json(request.path).toString))
    })
  }
}


/**
 *
 */
class ProxyProcessor extends Controller with Processor {
  val templateP = new TemplateProcessor

  def process(implicit request: Request[AnyContent],
             route: Option[StubRoute]): Option[Future[Result]] = {

    def buildHolder(url: String) = WS.url(url)
      //.withRequestTimeout(10000)
      .withFollowRedirects(follow = false)
      .withHeaders(request.headers.toSimpleMap.toSeq: _*)
      .withQueryString(request.queryString.mapValues(_.headOption.getOrElse("")).toSeq: _*)
      .withBody(request.body.asText.getOrElse(""))
      .withMethod(request.method)

    // Convert to Jackson node instead of Play.api.Json currently
    def toJson(response: WSResponse) = new ObjectMapper().readTree(
      response.underlying[com.ning.http.client.Response].getResponseBodyAsBytes)

    route.flatMap { r => r.proxyUrl map { url =>

      r.template(request) match {
        case Some(t) =>
          buildHolder(url).execute().map { response =>
            Logger.debug(s"ROUTE: Proxy:$url, Template:${t.path}")

            def resultAsIs() = Status(response.status)(response.body)
              .withHeaders(response.allHeaders.mapValues(_.headOption.getOrElse("")).toSeq: _*)

            if (response.status < 300)
              Stub.render(t.path, None, Some(r.flatParams ++ Stub.params + ("res" -> toJson(response))))
                .map(_.withHeaders((response.allHeaders.mapValues(_.headOption.getOrElse("")) -
                         HeaderNames.CONTENT_LENGTH - HeaderNames.CONTENT_TYPE).toSeq: _*)
                      .withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.HTML))
                .getOrElse(resultAsIs())

            else
              resultAsIs()
          }

        case None =>
          buildHolder(url).stream().map { case (response, body) =>
            Logger.debug(s"ROUTE: Proxy:$url, Stream")

            Status(response.status)
              .chunked(body)
              .withHeaders(response.headers.mapValues(_.headOption.getOrElse("")).toSeq: _*)
          }
        }
      }
    }
  }
}
