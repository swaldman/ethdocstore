package com.mchange.sc.v1.ethdocstore.plugin

import com.mchange.sc.v1.ethdocstore.contract.DocHashStore

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.Def.Initialize
import sbt.complete.Parser
import sbt.complete.DefaultParsers._

import java.io.File
import java.net.{HttpURLConnection, URL}

import com.mchange.sc.v1.sbtethereum.SbtEthereumPlugin
import com.mchange.sc.v1.sbtethereum.SbtEthereumPlugin.autoImport._

import com.mchange.sc.v2.io._
import com.mchange.sc.v2.lang._

import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress,EthHash}
import stub.sol

object EthDocStoreSbtPlugin extends AutoPlugin {

  object autoImport {
    val webServiceUrl = settingKey[String]("URL of the ethdocstore web service.")
    val docHashStoreAddress = settingKey[String]("Address of the DocHashStore contract (under the session Chain ID and Node URL).")

    val ingestFilePdf = inputKey[Unit]("Hashes a file, stores the hash in the DocHashStore, uploads the doc to web-based storage, marks as a PDF file.")
  }

  import autoImport._

  lazy val defaults : Seq[sbt.Def.Setting[_]] = Seq(
    Compile / ingestFilePdf := { ingestFileTask( "application/pdf" )( Compile ).evaluated },
  )

  private def ingestFileTask( contentType : String )( config : Configuration ) : Initialize[InputTask[EthHash]] = Def.inputTask {
    val log = streams.value.log
    val wsUrl = webServiceUrl.value

    val ( name, description, filePath ) = {
      for {
        name <- Space ~> token(NotSpace, "<name>")
        desc <- Space ~> token(StringEscapable, "<quoted-description>")
        filePath <- Space ~> token((any.+).map( _.mkString.trim ),"<file-path>")
      } yield {
        ( name, desc, filePath )
      }
    }.parsed

    val file = new File( filePath ).getAbsoluteFile()

    val hash = doStoreFile( log, wsUrl, contentType, file )

    val contractAddress = docHashStoreAddress.value

    implicit val ( sctx, ssender ) = ( config / xethStubEnvironment ).value

    val dhs = DocHashStore( contractAddress ) // uses the stub context from the environment, rather than building one from scratch!
    dhs.transaction.store( hash, name, description )

    log.info( s"Successfully ingested '${file}' with hash '0x${hash.hex}' and content type '${contentType}'" )

    EthHash.withBytes( hash.widen )
  }

  private def doStoreFile( log : sbt.Logger, wsUrl : String, contentType : String, file : File ) : sol.Bytes32 = {
    val base = if ( wsUrl.endsWith("/") ) wsUrl else wsUrl + "/"

    def mkConn( path : String ) = (new URL( s"${base}${path}" )).openConnection().asInstanceOf[HttpURLConnection]

    log.info( s"Checking file: '${file}'" )
    if (! file.exists()) throw new Exception( s"File '${file}' does not exist." )
    if (! file.canRead()) throw new Exception( s"File '${file}' is not readable." )
    val fileBytes = file.contentsAsByteArray

    borrow( mkConn( "doc-store/post" ) )( _.disconnect() ) { conn =>
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
        case other => throw new Exception( s"Failed to post file. HTTP Status Code: ${other}" )
      }
    }
  }

  // plug-in setup

  override def requires = JvmPlugin && SbtEthereumPlugin

  override def trigger = allRequirements

  override def projectSettings = defaults
}
