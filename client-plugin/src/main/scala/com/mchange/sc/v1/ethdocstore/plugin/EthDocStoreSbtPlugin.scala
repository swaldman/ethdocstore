package com.mchange.sc.v1.ethdocstore.plugin

import com.mchange.sc.v1.ethdocstore.{Metadata,Registration}
import com.mchange.sc.v1.ethdocstore.contract.DocHashStore

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

import com.mchange.sc.v1.sbtethereum.SbtEthereumPlugin
import com.mchange.sc.v1.sbtethereum.SbtEthereumPlugin.autoImport._

import com.mchange.sc.v3.failable._

import com.mchange.sc.v2.io._
import com.mchange.sc.v2.lang._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress, EthChainId, EthHash, EthSignature}
import stub.sol

import _root_.io.circe._, _root_.io.circe.generic.auto._, _root_.io.circe.generic.semiauto._, _root_.io.circe.parser._, _root_.io.circe.syntax._

object EthDocStoreSbtPlugin extends AutoPlugin {

  object autoImport {
    val webServiceUrl       = settingKey[String]("URL of the ethdocstore web service.")
    val docHashStoreAddress = settingKey[String]("Address of the DocHashStore contract (under the session Chain ID and Node URL).")

    val ingestFilePdf = inputKey[Unit]("Hashes a file, stores the hash in the DocHashStore, uploads the doc to web-based storage, marks as a PDF file.")
    val registerUser  = taskKey[Unit] ("Registers a user, associating the username with the current sender Ethereum address.")
  }

  import autoImport._

  lazy val defaults : Seq[sbt.Def.Setting[_]] = Seq(
    Compile / ingestFilePdf := { ingestFileTask( "application/pdf" )( Compile ).evaluated },
    Compile / registerUser  := { registerUserTask( Compile ).value }
  )

  private def ingestFileTask( contentType : String )( config : Configuration ) : Initialize[InputTask[EthHash]] = Def.inputTask {
    val log = streams.value.log
    val wsUrl = webServiceUrl.value

    val ( name, description, filePath, mbPublic ) = {
      for {
        n <- Space ~> token(NotSpace, "<name>")
        d <- Space ~> token(StringEscapable, "<quoted-description>")
        fp <- Space ~> token(StringEscapable,"<file-path>")
        mp <- Space ~> token(literal("public").?, "[public]")
      } yield {
        ( n, d, fp, mp )
      }
    }.parsed

    val file = new File( filePath ).getAbsoluteFile()

    val contractAddress = docHashStoreAddress.value

    val hash = doStoreFile( log, wsUrl, contractAddress, contentType, file, mbPublic.nonEmpty )

    implicit val ( sctx, ssender ) = ( config / xethStubEnvironment ).value

    val dhs = DocHashStore( contractAddress ) // uses the stub context from the environment, rather than building one from scratch!
    dhs.transaction.store( hash, name, description )

    log.info( s"Successfully ingested '${file}' with hash '0x${hash.widen.hex}' and content type '${contentType}'" )

    EthHash.withBytes( hash.widen )
  }

  private def throwCantReadInteraction = throw new Exception("Can't read interaction!")

  private def registerUserTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val is = interactionService.value
    val log = streams.value.log
    val wsUrl = webServiceUrl.value
    val chainId = (config / ethNodeChainId).value
    val ( _, ssender ) = ( config / xethStubEnvironment ).value

    val registrationAddress = ssender.address

    val addressOkay = {
      is.readLine( s"You would be registering as '0x${registrationAddress.hex}'. Is that okay? [y/n] ", false )
        .getOrElse( throwCantReadInteraction )
        .trim()
        .equalsIgnoreCase("y")
    }
    if (addressOkay) {
      val username = {
        is.readLine( s"Username: ", false )
          .getOrElse( throwCantReadInteraction )
          .trim()
      }

      @tailrec
      def fetchPassword : String = {
        val password = {
          is.readLine( s"Password: ", true )
            .getOrElse( throwCantReadInteraction )
            .trim()
        }
        val confirmation = {
          is.readLine( s"Confirm password: ", true )
            .getOrElse( throwCantReadInteraction )
            .trim()
        }
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
        log.info( s"User '${username}' successfully registered as ''0x${registrationAddress.hex}'." )
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

  private def doRegister( log : sbt.Logger, wsUrl : String, username : String, password : String, challenge : immutable.Seq[Byte], signature : EthSignature.Abstract, registrationAddress : EthAddress) : Boolean = {
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
