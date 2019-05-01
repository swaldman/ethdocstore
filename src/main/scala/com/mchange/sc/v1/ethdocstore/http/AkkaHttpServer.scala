package com.mchange.sc.v1.ethdocstore.http

import com.mchange.sc.v1.ethdocstore.contract.AsyncDocHashStore

import akka.actor.{ ActorRef, ActorSystem }

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, StatusCodes }
import akka.http.scaladsl.model.ContentTypes._
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

import com.typesafe.config.{Config => TSConfig, ConfigFactory => TSConfigFactory}

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress, EthChainId}
import stub.sol

import com.mchange.sc.v1.ethdocstore._

import com.mchange.sc.v2.lang.ThrowableOps

import scala.concurrent.{Await,Future}
import scala.concurrent.duration.Duration

import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties

import DocStore.{PutResponse,GetResponse,PutCheck}

import com.mchange.sc.v1.log.MLevel._

object AkkaHttpServer {

  private lazy implicit val logger = mlogger( this )
  
  private val ContentTypeKey = "Content-Type"

  case class ContractLocation( nodeUrl : String, chainId : EthChainId, address : EthAddress )

  case class DocRecord( docHash : sol.Bytes32, docHashName : sol.String, docHashDescription : String, docHashTimestamp : sol.UInt )

  def main( argv : Array[String]) : Unit = {
    val config = TSConfigFactory.load()

    val iface = config.getString( "ethdocstore.http.server.interface" )
    val port  = config.getInt( "ethdocstore.http.server.port" )

    val ethHashDocStoreDir = new File( config.getString( "ethdocstore.http.server.localStorageDirectory" ) )

    val mbContractLocation = {
      if ( config.hasPath( "ethdocstore.node.url" ) && config.hasPath( "ethdocstore.node.chainId" ) && config.hasPath( "ethdocstore.contract.address" ) ) {
        Some( ContractLocation( config.getString( "ethdocstore.node.url" ), EthChainId( config.getInt( "ethdocstore.node.chainId" ) ), EthAddress( config.getString( "ethdocstore.contract.address" ) ) ) )
      }
      else {
        None
      }
    }

    val clientUrl = config.getString( "ethdocstore.http.server.clientUrl" )

    val server = new AkkaHttpServer( iface, port, ethHashDocStoreDir, mbContractLocation, clientUrl )
    server.bind()
  }
}

import AkkaHttpServer._

class AkkaHttpServer( iface : String, port : Int, ethHashDocStoreDir : File, mbContractLocation : Option[ContractLocation], clientUrl : String ) {

  private lazy implicit val system       : ActorSystem       = ActorSystem("EthDocStoreAkkaHttp")
  private lazy implicit val materializer : ActorMaterializer = ActorMaterializer()

  private lazy val docStore = EthHashDirectoryDocStore( ethHashDocStoreDir ).assert

  private lazy val mbDocHashStore = mbContractLocation map { loc =>
    AsyncDocHashStore.build( jsonRpcUrl = loc.nodeUrl, chainId = Some( loc.chainId ), contractAddress = loc.address )
  }

  lazy val routes : Route = {
    extractRequestContext { implicit ctx =>

      implicit val ec = ctx.executionContext
      implicit val sender = stub.Sender.Default

      concat(
        pathPrefix("doc-store") {
          concat (
            pathPrefix("post") {
              pathEnd {
                post {
                  complete {
                    val f_bytestring : Future[ByteString] = ctx.request.entity.dataBytes.runWith( Sink.reduce( _ ++ _ ) )
                    f_bytestring map { bytestring =>
                      val data = bytestring.compact.toArray.toImmutableSeq
                      val contentType = ctx.request.entity.contentType.toString
                      val metadata = new Properties()
                      metadata.setProperty( ContentTypeKey, contentType )
                      val putResponse = docStore.put( data, metadata )
                      putResponse match {
                        case PutResponse.Success( hash )             => HttpResponse( status = StatusCodes.OK, entity = HttpEntity( `application/octet-stream`, hash.toArray ) )
                        case PutResponse.Error( message, Some( t ) ) => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( `text/plain(UTF-8)`, message + "\n\n" + t.fullStackTrace ) )
                        case PutResponse.Error( message, None )      => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( `text/plain(UTF-8)`, message ) )
                        case PutResponse.Forbidden( message )        => HttpResponse( status = StatusCodes.Forbidden, entity = HttpEntity( `text/plain(UTF-8)`, message ) )
                      }
                    }
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
                            if ( metadataContentType == null ) `application/octet-stream` else ContentType.parse( metadataContentType ).right.get
                          }
                          HttpResponse( entity = HttpEntity( contentType, data.toArray ) )
                        }
                        case GetResponse.NotFound                    => HttpResponse( status = StatusCodes.NotFound )
                        case GetResponse.Error( message, Some( t ) ) => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( `text/plain(UTF-8)`, message + "\n\n" + t.fullStackTrace ) )
                        case GetResponse.Error( message, None )      => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( `text/plain(UTF-8)`, message ) )
                        case GetResponse.Forbidden( message )        => HttpResponse( status = StatusCodes.Forbidden, entity = HttpEntity( `text/plain(UTF-8)`, message ) )
                      }
                    }
                  }
                }
              }
            }
          )
        },
        pathPrefix("index") {
          complete {
            mbDocHashStore match {
              case None => HttpResponse( status = StatusCodes.NotImplemented )
              case Some( docHashStore ) => {
                docHashStore.constant.size() flatMap { sz =>
                  val frecs = {
                    for {
                      i <- BigInt(0) until sz.widen
                    } yield {
                      for {
                        docHash <- docHashStore.constant.docHashes(sol.UInt(i))
                        docHashName <- docHashStore.constant.name( docHash )
                        docHashDescription <- docHashStore.constant.description( docHash )
                        docHashTimestamp <- docHashStore.constant.timestamp( docHash )
                      }
                      yield {
                        DocRecord( docHash, docHashName, docHashDescription, docHashTimestamp )
                      }
                    }
                  }
                  Future.sequence( frecs ) map { seq =>
                    import scalatags.Text.all._
                    val text = {
                      html(
                        head(
                          tag("title")("Documents")
                        ),
                        body(
                          h1(id:="mainTitle", "Documents"),
                          div(
                            cls:="allDocHashes",
                            for {
                              DocRecord( docHash, docHashName, docHashDescription, docHashTimestamp ) <- seq
                            } yield {
                              div(
                                cls:="docHashItems",
                                div(
                                  div(
                                    cls:="docHash",
                                    "0x"+docHash.widen.hex
                                  ),
                                  div(
                                    cls:="docHashName",
                                    docHashName
                                  ),
                                  div(
                                    cls:="docHashTimestamp",
                                    DateTimeFormatter.ISO_INSTANT.format( Instant.ofEpochSecond(docHashTimestamp.widen.toLong) )
                                  ),
                                  div(
                                    cls:="docHashDescription",
                                    docHashDescription
                                  )
                                )
                              )
                            }
                          )
                        )
                      )
                    }.toString
                    HttpResponse( entity = HttpEntity( `text/html(UTF-8)`, text ) )
                  }
                }
              }
            }
          }
        }
      )
    }
  }
  def bind() : Unit = {
    Http().bindAndHandle(routes, iface, port)

    println(s"Server online at 'http://${iface}:${port}/', should be exported to client URL '${clientUrl}'")

    Await.result(system.whenTerminated, Duration.Inf)
  }
}
