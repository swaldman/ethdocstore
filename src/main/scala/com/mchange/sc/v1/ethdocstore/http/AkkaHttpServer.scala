package com.mchange.sc.v1.ethdocstore.http

import com.mchange.sc.v1.ethdocstore.contract.AsyncDocHashStore

import akka.actor.{ ActorRef, ActorSystem }

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, HttpCharsets, MediaTypes, StatusCodes }
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Cache-Control`
import akka.http.scaladsl.model.headers.CacheDirectives.`no-cache`

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

import io.circe._, io.circe.generic.auto._, io.circe.generic.semiauto._, io.circe.parser._, io.circe.syntax._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress, EthChainId}
import stub.sol

import com.mchange.sc.v1.ethdocstore._

import com.mchange.sc.v2.lang.ThrowableOps
import com.mchange.sc.v2.io._

import com.mchange.sc.v3.failable._

import scala.collection._
import scala.concurrent.{Await,ExecutionContext, Future}
import scala.concurrent.duration.Duration

import java.io.{IOException, File}
import java.time.{Instant, ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.Properties

import com.mchange.sc.v1.log.MLevel._

import DocStore.{PutResponse,GetResponse,PutCheck}

object AkkaHttpServer {

  lazy implicit val logger = mlogger( this )

  implicit val docRecordEncoder : Encoder[DocRecord] = new Encoder[DocRecord] {
    final def apply( dr : DocRecord ): Json = Json.obj(
      ("docHash", Json.fromString( dr.docHash.widen.hex )),
      ("name", Json.fromString( dr.name )),
      ("description", Json.fromString( dr.description )),
      ("timestamp", Json.fromBigInt( dr.timestamp.widen ))
    )
  }

  
  private val ContentTypeKey = "Content-Type"
  private val VisibilityKey  = "Visibility"

  case class NodeInfo( nodeUrl : String, mbChainId : Option[EthChainId]  )

  case class DocRecord( docHash : sol.Bytes32, name : sol.String, description : String, timestamp : sol.UInt )

  case class DocStoreRecord( localDir : File, defaultToPublic : Boolean, postPutHook : DirectoryDocStore.PostPutHook )

  case class RegisterRequest( challengeHex : String, challengeSignatureRSVHex : String, userName : String, password : String )

  def main( argv : Array[String]) : Unit = {
    val config = TSConfigFactory.load()

    val iface = config.getString( "ethdocstore.http.server.interface" )
    val port  = config.getInt( "ethdocstore.http.server.port" )

    val mbPath = if ( config.hasPath( "ethdocstore.http.server.path" ) ) Some( config.getString( "ethdocstore.http.server.path" ) ) else None

    def ensureDir( id : String, dir : File ) : File = {
      if (! (dir.mkdirs() || (dir.exists() && dir.isDirectory()))) {
        throw new IOException( s"'${id}=${dir}' must be an existing or creatable directory, is not.")
      }
      else if (! (dir.canRead() && dir.canWrite())) {
        throw new IOException( s"'${id}=${dir}' must be readable and writable, is not.")
      }
      else {
        dir
      }
    }

    val dataDir = {
      val id = "ethdocstore.http.server.dataDir"
      val path = config.getString(id)
      val dir = new File( path )
      ensureDir( id, dir )
    }

    val docStoreRecords = {
      import scala.collection.JavaConverters._

      val contractConfig = config.getConfig("ethdocstore.contracts")
      val keySet = contractConfig.root.keySet.asScala
      val tuples = keySet.map { contractKey =>
        val contractAddress = EthAddress( contractKey )
        val contractDir = {
          val id = s"contract-0x${contractAddress.hex.toLowerCase}"
          val dir = new File( dataDir, id )
          ensureDir( id, dir )
        }
        val pphKey   = s"${contractKey}.postPutHook"
        val postPutHook = if ( contractConfig.hasPath( pphKey ) ) DirectoryDocStore.PostPutHook( contractConfig.getString( pphKey) ) else DirectoryDocStore.PostPutHook.NoOp
        val dtpublicKey = s"${contractKey}.defaultToPublic"
        val defaultToPublic = if ( contractConfig.hasPath( dtpublicKey ) ) contractConfig.getBoolean( dtpublicKey ) else false

        ( contractAddress, DocStoreRecord( contractDir, defaultToPublic, postPutHook ) )
      }.toSeq
      immutable.Map( tuples : _* )
    }

    val authProperties = {
      val file = new File( dataDir, "auth.properties" )
      if (! (file.exists() || file.createNewFile())) {
        throw new IOException( s"File '${file}' does not exist and cannot be created." )
      }
      if (! (file.canRead() && file.canWrite())) {
        throw new IOException( s"File '${file}' is not readable and writable, should be." )
      }
      file
    }

    val nodeInfo = NodeInfo( config.getString( "ethdocstore.node.url" ), Failable( config.getInt( "ethdocstore.node.chainId" ) ).toOption.map( EthChainId.apply(_) ) )

    val server = new AkkaHttpServer( iface, port, authProperties, docStoreRecords, nodeInfo, mbPath )
    server.bind()
  }
}

import AkkaHttpServer._

class AkkaHttpServer( iface : String, port : Int, authProperties : File, docStoreRecords : immutable.Map[EthAddress,DocStoreRecord], nodeInfo : NodeInfo, mbPathToApp : Option[String] ) {

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

  private lazy val mbNakedPath = {
    val len = prefix.length
    if ( len > 1 ) Some( prefix.substring(1, len-1) ) else None
  }

  private lazy val docStores = docStoreRecords.map { case ( address, DocStoreRecord(dir, _, hook) ) => ( address, EthHashDirectoryDocStore( dir, postPutHook = hook )( ExecutionContext.global ).assert ) }

  private lazy val challengeManager = new ChallengeManager( () => new java.security.SecureRandom(), validityMillis = 15000, challengeLength = 32 )

  lazy val routes = mbNakedPath match {
    case Some( nakedPath ) => pathPrefix( nakedPath )( withinAppRoutes )
    case None              => withinAppRoutes
  }

  def withinAppRoutes : Route = {
    extractRequestContext { implicit ctx =>

      implicit val ec = ctx.executionContext
      implicit val sender = stub.Sender.Default

      def addressSeq = (immutable.SortedSet.empty[String] ++ docStoreRecords.keySet.map( _.hex )).toVector

      concat(
        pathPrefix("challenge") {
          complete {
            Future {
              HttpResponse( entity = HttpEntity( `application/octet-stream`, challengeManager.newChallenge().toArray ) )
            }
          }
        },
        pathPrefix("index.html") {
          complete {
            produceMainIndex( addressSeq )
          }
        },
        pathPrefix("") { // is there a better way to specify the path "/"?
          pathEnd {
            complete {
              produceMainIndex( addressSeq )
            }
          }
        },
        path("docHashStores.json"){
          complete {
            Future( HttpResponse( status = StatusCodes.OK, entity = HttpEntity( `application/json`, addressSeq.asJson.noSpaces ) ) )
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
                  def fseq : Future[immutable.Seq[DocRecord]] = {
                    docHashStore.constant.size() flatMap { sz =>
                      Future.sequence {
                        for ( i <- (BigInt(0) until sz.widen).reverse ) yield {
                          for {
                            docHash     <- docHashStore.constant.docHashes(sol.UInt(i))
                            name        <- docHashStore.constant.name( docHash )
                            description <- docHashStore.constant.description( docHash )
                            timestamp   <- docHashStore.constant.timestamp( docHash )
                          }
                          yield {
                            DocRecord( docHash, name, description, timestamp )
                          }
                        }
                      }
                    }
                  }
                  concat(
                    pathPrefix("doc-store") {
                      concat(
                        pathPrefix("register") {
                          post {
                            ???
                          }
                        },
                        pathPrefix("post") {
                          pathEnd {
                            post {
                              complete {
                                val query = ctx.request.uri.query()
                                val visibility = {
                                  query.get("visibility") match {
                                    case Some( str ) => str.toBoolean
                                    case None        => docStoreRecords(address).defaultToPublic
                                  }
                                }
                                val f_bytestring : Future[ByteString] = ctx.request.entity.dataBytes.runWith( Sink.reduce( _ ++ _ ) )
                                f_bytestring map { bytestring =>
                                  val data = bytestring.compact.toArray.toImmutableSeq
                                  val contentType = ctx.request.entity.contentType.toString
                                  val metadata = new Properties()
                                  metadata.setProperty( ContentTypeKey, contentType )
                                  metadata.setProperty( VisibilityKey, visibility.toString )
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
                    path("docHashRecords.json"){
                      complete {
                        fseq.map( recseq => HttpResponse( status = StatusCodes.OK, entity = HttpEntity( `application/json`, recseq.asJson.noSpaces ) ).addHeader(`Cache-Control`(`no-cache`) ) )
                      }
                    },
                    pathPrefix("index.html") {
                      complete {
                        produceDocStoreIndex( address, fseq )
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

  def produceDocStoreIndex( docStoreAddress : EthAddress, fseq : Future[immutable.Seq[DocRecord]] )( implicit ec : ExecutionContext ) = {
    fseq map { seq =>
      import scalatags.Text.all._
      import scalatags.Text.tags2.title
      val titleStr = s"Hashed Documents (${seq.length} found at 0x${docStoreAddress.hex})"
      val text = {
        html(
          head(
            title( titleStr ),
            link(rel:="stylesheet", href:=s"${prefix}assets/css/index.css", `type`:="text/css; charset=utf-8"),
          ),
          body(
            h1(id:="mainTitle", titleStr, " ", div( float:="right", a( href := s"${prefix}index.html", raw("&uarr;")))),
            ol(
              cls:="allDocHashes",
              for {
                DocRecord( docHash, name, description, timestamp ) <- seq
              } yield {
                li (
                  div(
                    cls:="docHashItems",
                    div(
                      cls:="docHashName",
                      a (
                        href:=s"${prefix}0x${docStoreAddress.hex}/doc-store/get/${docHash.widen.hex}",
                        name
                      )
                    ),
                    div(
                      cls:="docHash",
                      "0x"+docHash.widen.hex
                    ),
                    div(
                      cls:="docHashTimestamp",
                      DateTimeFormatter.RFC_1123_DATE_TIME.format( ZonedDateTime.ofInstant( Instant.ofEpochSecond(timestamp.widen.toLong), ZoneOffset.UTC ) )
                    ),
                    div(
                      cls:="docHashDescription",
                      description
                    )
                  )
                )
              }
            )
          )
        )
      }.toString
      HttpResponse( entity = HttpEntity( `text/html(UTF-8)`, text ) ).addHeader(`Cache-Control`(`no-cache`) )
    }
  }

  def produceMainIndex( addressSeq: immutable.Seq[String] )( implicit ec : ExecutionContext ) = {
    Future {
      import scalatags.Text.all._
      import scalatags.Text.tags2.title
      val titleStr = "Known DocHashStores"
      val text = {
        html(
          head(
            title( titleStr ),
            link(rel:="stylesheet", href:=s"${prefix}assets/css/index.css", `type`:="text/css; charset=utf-8"),
          ),
          body(
            h1( titleStr ),
            ul(
              for {
                dhs <- addressSeq
              } yield {
                li(
                  a(
                    href := s"${prefix}0x${dhs}/index.html",
                    s"0x${dhs}"
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

  def bind() : Unit = {
    Http().bindAndHandle(routes, iface, port)

    INFO.log(s"""Server online at 'http://${iface}:${port}/', application located at path '${prefix}'""")

    Await.result(system.whenTerminated, Duration.Inf)
  }
}
