package com.mchange.sc.v1.ethdocstore.http

import com.mchange.sc.v1.ethdocstore.contract.AsyncDocHashStore

import akka.actor.{ ActorRef, ActorSystem }

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, HttpCharsets, MediaTypes, StatusCodes }
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
import com.mchange.sc.v2.io._

import com.mchange.sc.v3.failable._

import scala.collection._
import scala.concurrent.{Await,Future}
import scala.concurrent.duration.Duration

import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties

import com.mchange.sc.v1.log.MLevel._

import DocStore.{PutResponse,GetResponse,PutCheck}

object AkkaHttpServer {

  lazy implicit val logger = mlogger( this )
  
  private val ContentTypeKey = "Content-Type"

  case class NodeInfo( nodeUrl : String, mbChainId : Option[EthChainId]  )

  case class DocRecord( docHash : sol.Bytes32, docHashName : sol.String, docHashDescription : String, docHashTimestamp : sol.UInt )

  def main( argv : Array[String]) : Unit = {
    val config = TSConfigFactory.load()

    val iface = config.getString( "ethdocstore.http.server.interface" )
    val port  = config.getInt( "ethdocstore.http.server.port" )

    val docStoreDirs = {
      import scala.collection.JavaConverters._

      val contractConfig = config.getConfig("ethdocstore.contracts")
      val entrySet = contractConfig.entrySet().asScala
      val tuples = entrySet.map { entry =>
        ( EthAddress( entry.getKey() ), new File( entry.getValue().unwrapped().asInstanceOf[String] ) )
      }.toSeq
      immutable.Map( tuples : _* )
    }

    val nodeInfo = NodeInfo( config.getString( "ethdocstore.node.url" ), Failable( config.getInt( "ethdocstore.node.chainId" ) ).toOption.map( EthChainId.apply(_) ) )

    val server = new AkkaHttpServer( iface, port, docStoreDirs, nodeInfo )
    server.bind()
  }
}

import AkkaHttpServer._

class AkkaHttpServer( iface : String, port : Int, docStoreDirs : immutable.Map[EthAddress,File], nodeInfo : NodeInfo, mbPathToApp : Option[String] = None) {

  private lazy implicit val system       : ActorSystem       = ActorSystem("EthDocStoreAkkaHttp")
  private lazy implicit val materializer : ActorMaterializer = ActorMaterializer()

  private lazy val prefix = {
    mbPathToApp.fold("/"){ pathToApp =>
      (pathToApp.endsWith("/"), pathToApp.endsWith("/")) match {
        case ( true,  true  ) => pathToApp
        case ( true,  false ) => s"${pathToApp}/"
        case ( false, true  ) => s"/${pathToApp}"
        case ( false, false ) => s"/${pathToApp}/"
      }
    }
  }

  private lazy val docStores = docStoreDirs.map { case ( address, dir ) => ( address, EthHashDirectoryDocStore( dir ).assert ) }

  lazy val routes = mbPathToApp match {
    case Some( rawPrefix ) => rawPathPrefix( rawPrefix )( withinAppRoutes )
    case None              => withinAppRoutes
  }

  def withinAppRoutes : Route = {
    extractRequestContext { implicit ctx =>

      implicit val ec = ctx.executionContext
      implicit val sender = stub.Sender.Default

      concat(
        pathPrefix("test"){
          complete {
            Future( HttpResponse( status = StatusCodes.OK, entity = HttpEntity( `text/plain(UTF-8)`, "Test." ) ) )
          }
        },
        pathPrefix("assets") {
          pathPrefix("css") {
            path(Segment) { resourceName =>
              pathEnd {
                complete {
                  Future {
                    if ( resourceName.endsWith(".css") ) { // .css files only here
                      Option( this.getClass().getResourceAsStream( s"/assets/css/${resourceName}") ) match {
                        case Some( is ) => HttpResponse( status = StatusCodes.OK, entity = HttpEntity( MediaTypes.`text/css` withCharset HttpCharsets.`UTF-8`, is.remainingToByteArray ) )
                        case None => HttpResponse( status = StatusCodes.NotFound )
                      }
                    }
                    else {
                      HttpResponse( status = StatusCodes.Forbidden, entity = HttpEntity( `text/plain(UTF-8)`, "Only .css files ae permitted from this directory." ) )
                    }
                  }
                }
              }
            }
          }
        },
        pathPrefix(Segment) { addressHex =>
          Failable( EthAddress(addressHex.decodeHex) ) match {
            case f : Failed[EthAddress] => {
              complete {
                val report = {
                  s"""|Bad Ethereum address: ${addressHex}
                      |
                      |${f.toThrowable.fullStackTrace}""".stripMargin
                }
                Future( HttpResponse( status = StatusCodes.BadRequest, entity = HttpEntity( `text/plain(UTF-8)`,  report) ) )
              }
            }
            case s : Succeeded[EthAddress] => {
              val address = s.get
              docStores.get( address ) match {
                case None => {
                  complete {
                    Future( HttpResponse( status = StatusCodes.BadRequest, entity = HttpEntity( `text/plain(UTF-8)`,  s"Not configured for contract at address '0x${address.hex}'.") ) )
                  }
                }
                case Some( docStore ) => {
                  val docHashStore = AsyncDocHashStore.build( jsonRpcUrl = nodeInfo.nodeUrl, chainId = nodeInfo.mbChainId, contractAddress = address )
                  concat(
                    pathPrefix("doc-store") {
                      concat(
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
                    pathPrefix("index.html") {
                      complete {
                        println("--> index.html")
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
                            import scalatags.Text.tags2.title
                            val text = {
                              html(
                                head(
                                  title("Documents"),
                                  link(rel:="stylesheet", href:=s"${prefix}assets/css/index.css", `type`:="text/css; charset=utf-8"),
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
                                          cls:="docHashName",
                                          a (
                                            href:=s"${prefix}0x${address.hex}/doc-store/get/${docHash.widen.hex}",
                                            docHashName
                                          )
                                        ),
                                        div(
                                          cls:="docHash",
                                          tag("tt")("0x"+docHash.widen.hex)
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
                  )
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

    INFO.log(s"Server online at 'http://${iface}:${port}/', application located at path '${prefix}'")

    Await.result(system.whenTerminated, Duration.Inf)
  }
}
