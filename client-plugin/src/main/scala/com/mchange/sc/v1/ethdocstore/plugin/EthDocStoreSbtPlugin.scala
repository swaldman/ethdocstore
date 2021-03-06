package com.mchange.sc.v1.ethdocstore.plugin

import com.mchange.sc.v1.ethdocstore.{Metadata,Registration}
import com.mchange.sc.v1.ethdocstore.contract.DocHashStore

import com.mchange.sc.v1.sbtethereum.api.Interaction._
import com.mchange.sc.v1.sbtethereum.api.Parsers._
import com.mchange.sc.v1.sbtethereum.api.Formatting._


import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.Def.Initialize
import sbt.complete.Parser
import sbt.complete.DefaultParsers._

import java.io.File
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

import scala.annotation.tailrec
import scala.collection._
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._

import com.mchange.sc.v1.sbtethereum.SbtEthereumPlugin
import com.mchange.sc.v1.sbtethereum.SbtEthereumPlugin.autoImport._

import com.mchange.sc.v3.failable._

import com.mchange.sc.v2.io._
import com.mchange.sc.v2.lang._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc, stub, EthAddress, EthChainId, EthHash, EthSignature}
import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import stub.sol

import _root_.io.circe._, _root_.io.circe.generic.auto._, _root_.io.circe.generic.semiauto._, _root_.io.circe.parser._, _root_.io.circe.syntax._

object EthDocStoreSbtPlugin extends AutoPlugin {

  val Zero256 = sol.UInt256(0)
  val One256  = sol.UInt256(1)

  object autoImport {
    val docstoreWebServiceUrl    = settingKey[String]("URL of the ethdocstore web service.")
    val docstoreHashStoreAddress = settingKey[String]("Address of the DocHashStore contract (under the session Chain ID and Node URL).")

    val docstoreAmend              = inputKey[Unit]("Amends the name and/or description associated with a hash.")
    val docstoreAuthorizeAddress   = inputKey[Unit]("Adds an address to the list of identities permitted to upload hashes, amend metadata, and close the DocHashStore")
    val docstoreClose              = taskKey[Unit] ("Permently closes (rendering read only) this application's DocHashStore.")
    val docstoreDeauthorizeAddress = inputKey[Unit]("Removes an address from the list of identities permitted to upload hashes, amend metadata, and close the DocHashStore")
    val docstoreHashFile           = inputKey[Unit]("Prints the hash of a file (for verification purposes).")
    val docstoreIngestFile         = inputKey[EthHash]("Hashes a file, stores the hash in the DocHashStore, uploads the doc to web-based storage.")
    val docstoreRegisterUser       = taskKey[Unit] ("Registers a user, associating the username with the current sender Ethereum address.")
  }

  import autoImport._

  lazy val defaults : Seq[sbt.Def.Setting[_]] = Seq(
    Compile / docstoreAmend := { docstoreAmendTask( Compile ).evaluated },
    Compile / docstoreAuthorizeAddress := { docstoreAuthorizeAddressTask( Compile ).evaluated },
    Compile / docstoreClose := { docstoreCloseTask( Compile ).value },
    Compile / docstoreDeauthorizeAddress := { docstoreDeauthorizeAddressTask( Compile ).evaluated },
    Compile / docstoreHashFile := { docstoreHashFileTask( Compile ).evaluated },
    Compile / docstoreIngestFile := { docstoreIngestFileTask( Compile ).evaluated },
    Compile / docstoreRegisterUser  := { docstoreRegisterUserTask( Compile ).value }
  )

  val KnownTypes = immutable.Map (
    "text" -> "text/plain",
    "pdf"  -> "application/pdf",
    "jpeg" -> "image/jpeg",
    "tiff" -> "image/tiff",
    "html" -> "text/html",
    "zip"  -> "application/zip"
  )

  val KnownTypeParser = KnownTypes.keySet.map( literal ).reduceLeft( _ | _ ).map( KnownTypes.apply )

  val MimeTypeParser = {
    for {
      main <- Letter.+.string
      _    <- literal("/")
      sub  <- NotSpace
    }
    yield {
      s"${main}/${sub}"
    }
  }.examples("*/*")

  val ContentTypeParser = {
    for {
      _  <- SpaceClass.+
      ct <- (KnownTypeParser | MimeTypeParser)
    }
    yield {
      ct
    }
  }

  private def docstoreAmendTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val is = interactionService.value
    val log = streams.value.log
    val wsUrl = docstoreWebServiceUrl.value
    val docHash = ethHashParser( "<document-hash-hex>" ).parsed
    val stubHash = sol.Bytes32( docHash.bytes )

    implicit val ( sctx, ssender ) = ( config / xethStubEnvironment ).value

    val contractAddress = docstoreHashStoreAddress.value
    val dhs = DocHashStore( contractAddress ) // uses the stub context from the environment, rather than building one from scratch!

    val canUpdate = dhs.view.canUpdate( ssender.address )
    if ( !canUpdate ) throw new OnlyAuthorizedException( ssender.address )


    val oldName = dhs.view.name( stubHash )
    val oldDesc = dhs.view.description( stubHash )

    val name = {
      val raw = assertReadLine( is, s"Name (or [Enter] for '${oldName}'): ", mask = false ).trim()
      if ( raw.isEmpty ) oldName else raw
    }
    val description = {
      val raw = assertReadLine( is, "Description (or [Enter] to leave the description unchanged): ", mask = false ).trim()
      if ( raw.isEmpty ) oldDesc else raw
    }

    dhs.transaction.amend( stubHash, name, description )

    log.info( s"Successfully amended name and description for file with hash '0x${stubHash.widen.hex}'." )
  }

  private def docstoreAuthorizeAddressTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genAddressParser("<authorized-address>") )

    Def.inputTask {
      val log = streams.value.log
      implicit val ( scontext, sender ) = ( config / xethStubEnvironment).value
      val hashStoreAddress = EthAddress( ( config / docstoreHashStoreAddress ).value )
      val hashStore = DocHashStore( hashStoreAddress )
      val admin = hashStore.view.admin()
      if ( admin != sender.address ) throw new OnlyAdministratorException( admin, sender.address )
      val nonce = stub.Nonce( ( config / ethTransactionNonceOverrideValue).value.map( sol.UInt256.apply ) )
      val authorizee = parser.parsed
      hashStore.txn.authorize( authorizee, nonce )
      log.info( s"Address '${formatHex(authorizee)}' has been successfully authorized on the DocHashStore at address '${formatHex(hashStoreAddress)}'." )
    }
  }

  private def docstoreCloseTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val is = interactionService.value
    implicit val ( scontext, sender ) = ( config / xethStubEnvironment).value
    val hashStoreAddress = EthAddress( ( config / docstoreHashStoreAddress ).value )
    val hashStore = DocHashStore( hashStoreAddress )
    val canUpdate = hashStore.view.canUpdate( sender.address )
    if ( !canUpdate ) throw new OnlyAuthorizedException( sender.address )
    val check = queryYN( is, s"Are you sure you want to permanently close the DocHashStore at address '${formatHex(hashStoreAddress)}'? [y/n] " )
    if (!check) aborted( "User aborted the close operation." )
    val nonce = stub.Nonce( ( config / ethTransactionNonceOverrideValue).value.map( sol.UInt256.apply ) )
    hashStore.txn.close( nonce )
    log.info( s"DocHashStore at address '${formatHex(hashStoreAddress)}' has been permanently closed." )
  }
  
  private def docstoreDeauthorizeAddressTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genAddressParser("<authorized-address>") )

    Def.inputTask {
      val log = streams.value.log
      implicit val ( scontext, sender ) = ( config / xethStubEnvironment).value
      val hashStoreAddress = EthAddress( ( config / docstoreHashStoreAddress ).value )
      val hashStore = DocHashStore( hashStoreAddress )
      val admin = hashStore.view.admin()
      if ( admin != sender.address ) throw new OnlyAdministratorException( admin, sender.address )
      val nonce = stub.Nonce( ( config / ethTransactionNonceOverrideValue).value.map( sol.UInt256.apply ) )
      val deauthorizee = parser.parsed
      hashStore.txn.deauthorize( deauthorizee, nonce )
      log.info( s"Address '${formatHex(deauthorizee)}' has been successfully deauthorized on the DocHashStore at address '${formatHex(hashStoreAddress)}'." )
    }
  }
  private def docstoreHashFileTask( config : Configuration ) : Initialize[InputTask[EthHash]] = Def.inputTask {
    val is = interactionService.value
    val log = streams.value.log
    val file = queryMandatoryGoodFile( is, "Full path to file: ", file => (file.exists() && file.isFile() && file.canRead()), file => s"${file} does not exist, is not readable, or is not a regular file." ).getAbsoluteFile()
    val fileBytes = file.contentsAsByteArray
    val hash = EthHash.hash( fileBytes )
    log.info( s"The hash of this file is '${formatHex(hash)}'." )
    hash
  }
  private def docstoreIngestFileTask( config : Configuration ) : Initialize[InputTask[EthHash]] = Def.inputTask {
    val is = interactionService.value
    val log = streams.value.log
    val wsUrl = docstoreWebServiceUrl.value

    val contentType = ContentTypeParser.parsed

    val file = queryMandatoryGoodFile( is, "Full path to file: ", file => (file.exists() && file.isFile() && file.canRead()), file => s"${file} does not exist, is not readable, or is not a regular file." ).getAbsoluteFile()

    val name = {
      val raw = assertReadLine( is, s"Name (or [Enter] for '${file.getName}'): ", mask = false ).trim()
      if ( raw.isEmpty ) file.getName() else raw
    }

    val description = assertReadLine( is, "Description: ", mask = false )

    val public = queryYN( is, "Should this file be public? [y/n] " )

    val contractAddress = docstoreHashStoreAddress.value

    val hash = doStoreFile( log, wsUrl, contractAddress, contentType, file, public )

    implicit val ( sctx, ssender ) = ( config / xethStubEnvironment ).value

    val dhs = DocHashStore( contractAddress ) // uses the stub context from the environment, rather than building one from scratch!
    dhs.transaction.store( hash, name, description )

    log.info( s"Successfully ingested '${file}' with hash '0x${hash.widen.hex}' and content type '${contentType}'" )

    EthHash.withBytes( hash.widen )
  }

  private def docstoreRegisterUserTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val is = interactionService.value
    val log = streams.value.log
    val wsUrl = docstoreWebServiceUrl.value
    val chainId = (config / ethNodeChainId).value
    val ( _, ssender ) = ( config / xethStubEnvironment ).value

    val registrationAddress = ssender.address

    val addressOkay = queryYN( is, s"You would be registering as '0x${registrationAddress.hex}'. Is that okay? [y/n] " )
    if (addressOkay) {
      val username = assertReadLine( is, s"Username: ", false ).trim()

      @tailrec
      def fetchPassword : String = {
        val password     = assertReadLine( is, s"Password: ", true ).trim()
        val confirmation = assertReadLine( is, s"Confirm password: ", true ).trim()

        if ( password != confirmation ) {
          log.warn("The password and confirmation do not match! Try again!")
          fetchPassword
        }
        else {
          password
        }
      }

      val password = fetchPassword
      println(s"You will be asked to sign a challenge in order to prove you are associated with address '0x${registrationAddress.hex}'.")
      val challenge = doFetchChallenge( log, wsUrl )
      val signature = ssender.findSigner().sign( challenge, EthChainId( chainId ) )
      val registered = doRegister( log, wsUrl, username, password, challenge, signature, registrationAddress )
      if ( registered ) {
        log.info( s"User '${username}' successfully registered as '0x${registrationAddress.hex}'." )
      }
      else {
        log.error("Registration failed!")
      }
    }
    else {
      log.warn("Registration aborted. Consider 'ethAddressSenderOverrideSet' to choose the address for which you wish to register.")
    }
  }

  private def mkConn( wsUrl : String, path : String ) = {
    val base = if ( wsUrl.endsWith("/") ) wsUrl else wsUrl + "/"
    (new URL( s"${base}${path}" )).openConnection().asInstanceOf[HttpURLConnection]
  }

  private def doFetchChallenge( log : sbt.Logger, wsUrl : String ) : immutable.Seq[Byte] = {
    borrow( mkConn( wsUrl, s"challenge" ) )( _.disconnect() ) { conn =>
      val responseCode = conn.getResponseCode()

      responseCode match {
        case 200   => {
          log.info( "Challenge successfully received." )
          borrow( conn.getInputStream() )( _.remainingToByteSeq )
        }
        case other => {
          handleUnexpectedStatusCode(other, "Failed to fetch challenge.", conn)
        }
      }
    }
  }

  private def doRegister( log : sbt.Logger, wsUrl : String, username : String, password : String, challenge : immutable.Seq[Byte], signature : EthSignature, registrationAddress : EthAddress) : Boolean = {
    val registration = Registration( username, password, challenge.hex, signature.rsvBytes.hex, registrationAddress.hex )
    borrow( mkConn( wsUrl, "register" ) )( _.disconnect() ) { conn =>
      conn.setRequestMethod( "POST" )
      conn.setRequestProperty( "Content-Type", "application/json" )
      conn.setDoInput( true )
      conn.setDoOutput( true )
      conn.setUseCaches( false )
      borrow( conn.getOutputStream() ) { os =>
        os.write( registration.asJson.noSpaces.getBytes( StandardCharsets.UTF_8 ) )
      }
      val responseCode = conn.getResponseCode()
      responseCode match {
        case 200   => {
          log.info( "Registration accepted." )
          true
        }
        case other => {
          handleUnexpectedStatusCode(other, "Failed to register.", conn)
        }
      }
    }
  }

  private def doStoreFile( log : sbt.Logger, wsUrl : String, contractAddress : String, contentType : String, file : File, pub : Boolean ) : sol.Bytes32 = {
    log.info( s"Checking file: '${file}'" )
    if (! file.exists()) throw new Exception( s"File '${file}' does not exist." )
    if (! file.canRead()) throw new Exception( s"File '${file}' is not readable." )
    val fileBytes = file.contentsAsByteArray

    val path = {
      val base = s"${contractAddress}/doc-store/post"
      if ( pub ) {
        base + s"?${Metadata.Key.Visibility}=public"
      }
      else {
        base
      }
    }

    borrow( mkConn( wsUrl, path ) )( _.disconnect() ) { conn =>
      conn.setRequestMethod( "POST" )
      conn.setRequestProperty( "Content-Type", contentType )
      conn.setDoInput( true )
      conn.setDoOutput( true )
      conn.setUseCaches( false )
      borrow( conn.getOutputStream() ) { os =>
        os.write( fileBytes )
      }
      val responseCode = conn.getResponseCode()

      responseCode match {
        case 200   => {
          log.info( s"File '${file}' successfully uploaded." )
          val hashBytes = borrow( conn.getInputStream() )( _.remainingToByteSeq )
          sol.Bytes32( hashBytes )
        }
        case other => {
          handleUnexpectedStatusCode(other, "Failed to post file.", conn)
        }
      }
    }
  }

  private def handleUnexpectedStatusCode( statusCode : Int, desc : String, conn : HttpURLConnection ) : Nothing = {
    val bodyBytes = {
      val recoveredFailable = {
        Failable( borrow( conn.getErrorStream() )( _.remainingToByteArray ) ) orElse Failable( borrow( conn.getInputStream() )( _.remainingToByteArray ) ) recover { f =>
          println(f)
          "Not Available".getBytes("UTF8")
        }
      }
      recoveredFailable.get
    }
    throw new Exception( s"""${desc} HTTP Status Code: ${statusCode}\n\n${new String(bodyBytes, "UTF8")}""" )
  }

  // plug-in setup

  override def requires = JvmPlugin && SbtEthereumPlugin

  override def trigger = allRequirements

  override def projectSettings = defaults
}
