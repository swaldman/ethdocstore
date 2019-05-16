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
import akka.stream.scaladsl.{Sink, StreamConverters}

import akka.util.ByteString

import com.typesafe.config.{Config => TSConfig, ConfigFactory => TSConfigFactory}

import io.circe._, io.circe.generic.auto._, io.circe.generic.semiauto._, io.circe.parser._, io.circe.syntax._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress, EthChainId, EthSignature}
import com.mchange.sc.v1.consuela.io.setUserOnlyFilePermissions
import stub.sol

import com.mchange.sc.v1.ethdocstore._

import com.mchange.sc.v2.lang.ThrowableOps
import com.mchange.sc.v2.io._

import com.mchange.sc.v3.failable._

import scala.collection._
import scala.concurrent.{Await,ExecutionContext, Future}
import scala.concurrent.duration.Duration

import java.io.{BufferedInputStream, IOException, File}
import java.time.{Instant, ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.nio.charset.StandardCharsets

import com.mchange.sc.v1.log.MLevel._

import DocStore.{PutResponse,GetResponse,PutCheck}

import SynkreBCryptBasicAuthUtils._

object AkkaHttpServer {

  lazy implicit val logger = mlogger( this )

  private val EthAddressRegex = """(?:0x)?\p{XDigit}{40}""".r

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
      setUserOnlyFilePermissions( file )
      file
    }

    val nodeInfo = NodeInfo( config.getString( "ethdocstore.node.url" ), Failable( config.getInt( "ethdocstore.node.chainId" ) ).toOption.map( EthChainId.apply(_) ) )

    val OneMB = 1024L * 1024 //hardcoded for now, make a config param soon

    val server = new AkkaHttpServer( iface, port, authProperties, docStoreRecords, OneMB, nodeInfo, mbPath )
    server.bind()
  }
}

import AkkaHttpServer._

class AkkaHttpServer(
  iface : String,
  port : Int,
  authProperties : File,
  docStoreRecords : immutable.Map[EthAddress, AkkaHttpServer.DocStoreRecord],
  cacheableDataMaxBytes : Long,
  nodeInfo : NodeInfo,
  mbPathToApp : Option[String]
)( implicit ec : ExecutionContext = ExecutionContext.global ) {

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

  private lazy val caches = new CachedDocStores( docStores, nodeInfo, cacheableDataMaxBytes )

  private lazy val challengeManager = new ChallengeManager( () => new java.security.SecureRandom(), validityMillis = 60000, challengeLength = 32 )

  private lazy val passwordManager = new PropsFilePasswordManager( authProperties )

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
        pathPrefix("test") {
          synkreAuthenticateBasicAsync("test", (username, _) => Future.successful(Some(username))) { name =>
            complete {
              Future {
                HttpResponse( entity = HttpEntity( `text/plain(UTF-8)`, name ) )
              }
            }
          }
        },
        pathPrefix("challenge") {
          complete {
            Future {
              HttpResponse( entity = HttpEntity( `application/octet-stream`, challengeManager.newChallenge().toArray ) )
            }
          }
        },
        pathPrefix("register") {
          post {
            complete {
              val f_bytestring : Future[ByteString] = ctx.request.entity.dataBytes.runWith( Sink.reduce( _ ++ _ ) )
              f_bytestring map { bytestring =>
                val strData = new String( bytestring.compact.toArray, StandardCharsets.UTF_8 )
                decode[Registration](strData) match {
                  case Left( error ) => WARNING.logEval( HttpResponse( status = StatusCodes.BadRequest, entity = HttpEntity( `text/plain(UTF-8)`,  error.fullStackTrace ) ) )
                  case Right( registration ) => {
                    val challenge = registration.challengeHex.decodeHexAsSeq
                    if ( challengeManager.verifyChallenge( challenge ) ) {

                      val failableResponse = {
                        for {
                          expectedAddress <- Failable( EthAddress( registration.expectedAddressHex ) )
                          signature       <- Failable( EthSignature.fromBytesRSV( registration.signatureHexRSV.decodeHex ) )
                        }
                        yield {
                          if ( signature.signsForAddress( challenge.toArray, expectedAddress ) ) {
                            passwordManager.set( registration.username, registration.password, expectedAddress )
                            HttpResponse( status = StatusCodes.OK, entity = HttpEntity( `text/plain(UTF-8)`, s"User '${registration.username}' successfully registered." ) )
                          }
                          else {
                            HttpResponse( status = StatusCodes.Unauthorized, entity = HttpEntity( `text/plain(UTF-8)`, s"Requested identity did not match signature of challenge." ) )
                          }
                        }
                      }

                      val recoveredResponse = failableResponse recover { f =>
                        WARNING.logEval( HttpResponse( status = StatusCodes.BadRequest, entity = HttpEntity( `text/plain(UTF-8)`, "Bad Request:\n" + f ) ) )
                      }

                      recoveredResponse.assert
                    }
                    else {
                      HttpResponse( status = StatusCodes.Unauthorized, entity = HttpEntity( `text/plain(UTF-8)`, s"Registration failed. Challenge '${registration.challengeHex}' unknown." ) )
                    }
                  }
                }
              }
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
                  Future.unit flatMap { _ =>
                    if ( resourceName.endsWith(".css") ) { // .css files only here
                      caches.attemptResource( s"/assets/css/${resourceName}" ) match {
                        case Some( f_byteseq ) => f_byteseq.map( byteseq => HttpResponse( status = StatusCodes.OK, entity = HttpEntity( MediaTypes.`text/css` withCharset HttpCharsets.`UTF-8`, byteseq.toArray ) ) )
                        case None              => Future( HttpResponse( status = StatusCodes.NotFound ) )
                      }
                    }
                    else {
                      Future( HttpResponse( status = StatusCodes.Forbidden, entity = HttpEntity( `text/plain(UTF-8)`, "Only .css files ae permitted from this directory." ) ) )
                    }
                  }
                }
              }
            }
          }
        },
        pathPrefix(EthAddressRegex) { addressHex =>
          Failable( EthAddress(addressHex.decodeHex) ) match {
            case f : Failed[EthAddress] => {
              complete {
                val report = {
                  s"""|Bad Ethereum address: ${addressHex}
                      |
                      |${f.toThrowable.fullStackTrace}""".stripMargin
                }
                Future( WARNING.logEval( HttpResponse( status = StatusCodes.BadRequest, entity = HttpEntity( `text/plain(UTF-8)`,  report) ) ) )
              }
            }
            case s : Succeeded[EthAddress] => {
              val address = s.get
              docStores.get( address ) match {
                case None => {
                  complete {
                    Future( WARNING.logEval( HttpResponse( status = StatusCodes.BadRequest, entity = HttpEntity( `text/plain(UTF-8)`,  s"Not configured for contract at address '0x${address.hex}'.") ) ) )
                  }
                }
                case Some( docStore ) => {
                  def fseq : Future[immutable.Seq[DocRecord]] = caches.attemptDocRecordSeq( address ).get // this should be availble, we've checked address first
                  concat(
                    pathPrefix("doc-store") {
                      concat(
                        pathPrefix("post") {
                          pathEnd {
                            post {
                              complete {
                                Future {
                                  val query = ctx.request.uri.query()
                                  val visibility : Either[HttpResponse,String] = {
                                    query.get( Metadata.Key.Visibility ) match {
                                      case Some( v @ "public" )  => Right(v)
                                      case Some( v @ "private" ) => Right(v)
                                      case Some( other )         => Left( WARNING.logEval( HttpResponse( status = StatusCodes.BadRequest, entity = HttpEntity( `text/plain(UTF-8)`, s"Unexpected visibility query string param value: ${other}" ) ) ) )
                                      case None if docStoreRecords(address).defaultToPublic => Right("public")
                                      case None                                             => Right("private")
                                    }
                                  }
                                  visibility
                                } flatMap {
                                  case Left( response ) => Future.successful( response )
                                  case Right( viz ) => {
                                    val f_bytestring : Future[ByteString] = ctx.request.entity.dataBytes.runWith( Sink.reduce( _ ++ _ ) )
                                    f_bytestring map { bytestring =>
                                      val data = bytestring.compact.toArray.toImmutableSeq
                                      val contentType = ctx.request.entity.contentType.toString
                                      val metadata = new Properties()
                                      metadata.setProperty( Metadata.Key.ContentType, contentType )
                                      metadata.setProperty( Metadata.Key.Visibility, viz )
                                      val putResponse = docStore.put( data, metadata )
                                      putResponse match {
                                        case PutResponse.Success( hash, handle, metadata ) => {
                                          caches.markDirtyGetResponse( address, hash )
                                          caches.markDirtyDocRecordSeq( address )
                                          caches.markDirtyHandleData( handle )
                                          HttpResponse( status = StatusCodes.OK, entity = HttpEntity( `application/octet-stream`, hash.toArray ) )
                                        }
                                        case PutResponse.Error( message, Some( t ) ) => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( `text/plain(UTF-8)`, message + "\n\n" + t.fullStackTrace ) )
                                        case PutResponse.Error( message, None )      => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( `text/plain(UTF-8)`, message ) )
                                        case PutResponse.Forbidden( message )        => HttpResponse( status = StatusCodes.Forbidden, entity = HttpEntity( `text/plain(UTF-8)`, message ) )
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        },
                        pathPrefix("get") {
                          path(Segment) { hashHex =>
                            pathEnd {
                              def authenticatedCompletion = {
                                synkreAuthenticateBasicAsync(s"Etherea", (username, password) => Future( passwordManager.authenticate( username, password ) ) ) { userAddress =>
                                  complete {
                                    Future.unit flatMap { _ =>
                                      caches.attemptUserCanUpdate( address, userAddress ) match {
                                        case Some( f_userIsAuthorized ) => {
                                          f_userIsAuthorized flatMap { userIsAuthorized =>
                                            if ( userIsAuthorized ) {
                                              handleDataStoreGet( address, hashHex )
                                            }
                                            else {
                                              Future {
                                                HttpResponse(
                                                  status = StatusCodes.Unauthorized,
                                                  entity = HttpEntity( `text/plain(UTF-8)`, s"User with address 0x${userAddress.hex} is not authorized on doc hash store @ ${address.hex}" )
                                                )
                                              }
                                            }
                                          }
                                        }
                                        case None => Future {
                                          HttpResponse(
                                            status = StatusCodes.InternalServerError,
                                            entity = HttpEntity( `text/plain(UTF-8)`, s"Failed to read whether 0x${userAddress.hex} is authorized on doc hash store @ ${address.hex}" )
                                          )
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                              caches.attemptSynchronousMetadata( address, hashHex.decodeHexAsSeq ) match {
                                case Some( props ) => {
                                  if ( props.getProperty( Metadata.Key.Visibility ) == "public" ) {
                                    complete {
                                      handleDataStoreGet( address, hashHex )
                                    }
                                  }
                                  else {
                                    authenticatedCompletion
                                  }
                                }
                                case None => authenticatedCompletion
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

  private def handleDataStoreGet( address : EthAddress, hashHex : String ) : Future[HttpResponse] = {
    Future( caches.attemptGetResponse( address, hashHex.decodeHexAsSeq ) ) flatMap {
      case None => Future( HttpResponse( status = StatusCodes.NotFound ) )
      case Some( f_getResponse ) => deliverGetResponse( f_getResponse )
    }
  }

  private def deliverGetResponse( f_getResponse : Future[DocStore.GetResponse] ) : Future[HttpResponse] = {
    f_getResponse flatMap {
      case GetResponse.Success( handle, metadata ) => {
        val metadataContentType = metadata.getProperty( Metadata.Key.ContentType )
        val contentType = {
          if ( metadataContentType == null ) `application/octet-stream` else ContentType.parse( metadataContentType ).right.get
        }
        caches.attemptHandleData( handle ) match {
          case Some( f_data ) => f_data.map( data => HttpResponse( entity = HttpEntity( contentType, data.toArray ) ) )
          case None => Future( HttpResponse( entity = HttpEntity( contentType, StreamConverters.fromInputStream( () => handle.newInputStream() ) ) ) ) // should we make chunk size configurable?
        }
      }
      case GetResponse.NotFound                    => Future( HttpResponse( status=StatusCodes.NotFound ) )
      case GetResponse.Error( message, Some( t ) ) => Future( HttpResponse( status=StatusCodes.InternalServerError, entity=HttpEntity( `text/plain(UTF-8)`, message + "\n\n" + t.fullStackTrace ) ) )
      case GetResponse.Error( message, None )      => Future( HttpResponse( status=StatusCodes.InternalServerError, entity=HttpEntity( `text/plain(UTF-8)`, message ) ) )
      case GetResponse.Forbidden( message )        => Future( HttpResponse( status=StatusCodes.Forbidden, entity=HttpEntity( `text/plain(UTF-8)`, message ) ) )
    } recover {
      case t => HttpResponse( status = StatusCodes.InternalServerError, entity = HttpEntity( `text/plain(UTF-8)`, t.fullStackTrace ) )
    }
  }

  private def produceDocStoreIndex( docStoreAddress : EthAddress, fseq : Future[immutable.Seq[DocRecord]] )( implicit ec : ExecutionContext ) : Future[HttpResponse]= {
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
