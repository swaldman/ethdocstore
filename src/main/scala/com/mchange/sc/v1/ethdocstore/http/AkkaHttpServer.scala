package com.mchange.sc.v1.ethdocstore.http

import akka.actor.{ ActorRef, ActorSystem }

import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, StatusCodes }
import akka.http.scaladsl.server.{RequestContext, Route}

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RequestContext, Route}
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.directives.MethodDirectives.put
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.directives.PathDirectives.path

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink

import akka.util.ByteString

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress, EthChainId}

import com.mchange.sc.v1.ethdocstore._

import com.mchange.sc.v2.lang.ThrowableOps

import scala.concurrent.Future

import java.io.File
import java.util.Properties

object AkkaHttpServer {
  private val ApplicationOctetStream = ContentType.parse("application/octet-stream").right.get
  private val TextPlain = ContentType.parse("text/plain").right.get.asInstanceOf[ContentType.NonBinary]

  private val ContentTypeKey = "Content-Type"
}
class AkkaHttpServer( ethHashDocStoreDir : File ) {
  import AkkaHttpServer._

  private lazy implicit val system       : ActorSystem       = ActorSystem("EthDocStoreAkkaHttp")
  private lazy implicit val materializer : ActorMaterializer = ActorMaterializer()

  private lazy val docStore = EthHashDirectoryDocStore( ethHashDocStoreDir ).assert

  lazy val routes : Route = {
    def ec( implicit ctx : RequestContext ) = ctx.executionContext

    extractRequestContext { implicit ctx =>
      concat(
        pathPrefix("doc-store") {
          concat (
            pathPrefix("post") {
              pathEnd {
                post {
                  complete {
                    val f_bytestring : Future[ByteString] = ctx.request.entity.dataBytes.runWith( Sink.reduce( _ ++ _ ) )
                    f_bytestring.map { bytestring =>
                      val data = bytestring.compact.toArray.toImmutableSeq
                      val contentType = ctx.request.entity.contentType.toString
                      val metadata = new Properties()
                      metadata.setProperty( ContentTypeKey, contentType )
                      val putResponse = docStore.put( data, metadata )
                      putResponse match {
                        case DocStore.PutResponse.Success( hash )  => HttpResponse( status = StatusCodes.OK, entity = HttpEntity( ApplicationOctetStream, hash.toArray ) )
                        case DocStore.Error( message, Some( t ) )  => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( TextPlain, message + "\n\n" + t.fullStackTrace ) )
                        case DocStore.Error( message, None )       => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( TextPlain, message ) )
                        case DocStore.Forbidden( message )         => HttpResponse( status = StatusCodes.Forbidden, entity = HttpEntity( TextPlain, message ) )
                      }
                    }( ec )
                  }
                }
              }
            },
            pathPrefix("get") { 
              path(Segment) { hex =>
                pathEnd {
                  complete {
                    Future {
                      docStore.get( hex.decodeHexAsSeq ) match {
                        case DocStore.GetResponse.Success( data, metadata ) => {
                          val metadataContentType = metadata.getProperty( ContentTypeKey )
                          val contentType = {
                            if ( metadataContentType == null ) ApplicationOctetStream else ContentType.parse( metadataContentType ).right.get
                          }
                          HttpResponse( entity = HttpEntity( contentType, data.toArray ) )
                        }
                        case DocStore.GetResponse.NotFound        => HttpResponse( status = StatusCodes.NotFound )
                        case DocStore.Error( message, Some( t ) ) => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( TextPlain, message + "\n\n" + t.fullStackTrace ) )
                        case DocStore.Error( message, None )      => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( TextPlain, message ) )
                        case DocStore.Forbidden( message )        => HttpResponse( status = StatusCodes.Forbidden, entity = HttpEntity( TextPlain, message ) )
                      }
                    }( ec )
                  }
                }
              }
            }
          )
        }
      )
    }
  }
}
