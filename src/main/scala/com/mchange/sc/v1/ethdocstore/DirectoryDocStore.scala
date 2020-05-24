package com.mchange.sc.v1.ethdocstore

import java.io._
import java.util.Properties

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.io._
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._
import com.mchange.sc.v1.log.MLevel._

import scala.collection._
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Failure
import scala.util.control.NonFatal

import akka.http.scaladsl.model.{ContentType,MediaType}

import DocStore.{PutResponse,GetResponse,PutApprover}

object DirectoryDocStore {

  implicit lazy val logger = mlogger(this)

  def apply( dir : File, hasher : DocStore.Hasher = DocStore.Hasher.EthHash, putApprover : DocStore.PutApprover = PutApprover.AlwaysSucceed, postPutHook : PostPutHook = PostPutHook.NoOp )( implicit ec : ExecutionContext ) : Failable[DirectoryDocStore] = {
    Failable ( new DirectoryDocStore( dir.getCanonicalFile, hasher, putApprover, postPutHook )( ec ) )
  }
  final case class PutRecord( dir : File, hash : immutable.Seq[Byte], data : immutable.Seq[Byte], metadata : Properties, dataFile : File, metadataFile : File )

  private def relativizePath( parentDir : File, child : File ) : Failable[String] = {
    require( parentDir.isDirectory(), s"The parent against which we relativize a child path must be a directory. '${parentDir}' is not." )

    val parentPath = parentDir.getCanonicalPath()
    val childPath  = child.getCanonicalPath()

    if ( childPath.startsWith(parentPath) ) {
      val raw = childPath.drop( parentPath.length )
      if ( raw.nonEmpty ) {
        Failable.succeed( if ( raw.startsWith("/") || raw.startsWith("\\") ) raw.substring(1) else raw )
      }
      else {
        Failable.succeed( "." ) // the two paths are identical
      }
    }
    else {
      Failable.fail( s"File '${child}' appears not to be a child of '${parentDir}." )
    }
  }

  def suffixForContentType( contentType : String ) : Option[String] = {
    def fromExtensions( extensions : List[String] ) = {
      if ( extensions.length > 0 ) Some( extensions.head ) else None
    }
    ContentType.parse( contentType ) match {
      case Left( oopsies ) => {
        FINE.log( s"Could not parse '${contentType}' as a content type." )
        oopsies.foreach( oopsie => FINE.log( oopsie.toString ) )
        MediaType.parse( contentType ) match {
          case Left( whoopsies ) => {
            FINE.log( s"Also could not parse '${contentType}' as a media type." )
            whoopsies.foreach( whoopsie => FINE.log( whoopsie.toString ) )
            None
          }
          case Right( mediaType ) => {
            fromExtensions( mediaType.fileExtensions )
          }
        }
      }
      case Right( ct ) => {
        fromExtensions( ct.mediaType.fileExtensions )
      }
    }
  }

  def newDataFile( parentDir : File, hashHex : String, contentType : String ) : File = {
    val mbSuffix = suffixForContentType( contentType )
    mbSuffix match {
      case Some( "properties" ) => new File( parentDir, hashHex ) // nothing sneaky please
      case Some( suffix )       => new File( parentDir, s"${hashHex}.${suffix}" )
      case None                 => new File( parentDir, hashHex )
    }
  }

  def permissiveFindExistingDataFile( parentDir : File, hashHex : String, expectedContentType : Option[String] ) : Option[File] = {
    val mbFirst = expectedContentType.map( contentType => newDataFile( parentDir, hashHex, contentType ) )
    if ( mbFirst.nonEmpty && mbFirst.get.exists() ) {
      mbFirst
    }
    else {
      val second = new File( parentDir, hashHex )
      if ( second.exists() ) {
        Some( second )
      }
      else {
        val candidates = parentDir.list()
        val prefix = hashHex + '.'
        val metadataName = hashHex + ".properties"
        candidates.find( name => name.startsWith(hashHex) && name != metadataName ).map( name => new File( parentDir, name ) )
      }
    }
  }

  final object PostPutHook {
    private val OK : Future[Unit] = Future.unit

    def apply( key : String ) : PostPutHook = {
      key.toLowerCase match {
        case "noop"             => NoOp
        case "gitaddcommitpush" => GitAddCommitPush
        case _                  => throw new Exception( s"PostPutHook '${key}' is unknown and not supported." )
      }
    }
    def apply( mbKey : Option[String] ) : PostPutHook = mbKey.fold( NoOp )( key => apply(key) )

    val NoOp : PostPutHook = ( pr : PutRecord, ec : ExecutionContext ) => OK

    // should we think about locking access to the directory somehow until this completes?
    val GitAddCommitPush = ( pr : PutRecord, ec : ExecutionContext ) => Future {
      val dotgit = new File( pr.dir, ".git" )
      if (dotgit.exists() && dotgit.isDirectory()) {
        import scala.sys.process._

        val processLogger = ProcessLogger( line => DEBUG.log( "GitAddCommitPush - stdout: ${line}" ), line => DEBUG.log( "GitAddCommitPush - stderr: ${line}" ) )

        blocking {
          val aev = Process("git" :: "add" :: "." :: Nil, Some(pr.dir)).!( processLogger )
          if (aev != 0) {
            WARNING.log( s"GitAddCommitPush: Add failed with exit value ${aev}. See DEBUG logs for output." )
          }
          else {
            val cev = Process("git" :: "commit" :: "-m" :: s""""Add files for 0x${pr.hash.hex}."""" :: Nil, Some(pr.dir)).!( processLogger )
            if (cev != 0) {
              WARNING.log( s"GitAddCommitPush: Commit failed with exit value ${cev}. See DEBUG logs for output." )
            }
            else {
              val pev = Process("git" :: "push" :: Nil, Some(pr.dir)).!( processLogger )
              if (pev != 0) {
                WARNING.log( s"GitAddCommitPush: Push failed with exit value ${pev}. See DEBUG logs for output." )
              }
            }
          }
        }
      }
      else {
        WARNING.log( s"File storage directory '${pr.dir}' does not appear to be a git repository. git add / commit / push skipped." )
      }
    }( ec )
  }
  type PostPutHook = (PutRecord, ExecutionContext) => Future[Unit]
}

import DirectoryDocStore._

final class DirectoryDocStore private (
  dir : File,
  hasher : DocStore.Hasher,
  putApprover : DocStore.PutApprover,
  postPutHook : PostPutHook
)( implicit ec : ExecutionContext ) extends DocStore.Abstract( hasher, putApprover ) {
  if (! (dir.exists || dir.mkdirs)) throw new FileNotFoundException( s"'${dir}' does not exist and cannot be created." )
  if (! dir.isDirectory) throw new IOException("File storage directory '${dir}' must be a directory, is not.")
  if (! (dir.canRead() && dir.canWrite())) throw new IOException( s"'${dir}' must be both readable and writable, is not." )

  protected def store( hash : immutable.Seq[Byte], data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse = {
    try {
      val hashHex = hash.hex
      val metadataFile = new File( dir, hashHex + ".properties" )
      metadataFile.getCanonicalPath().intern().synchronized { // lock on an interned string representing the hash in this storage directory

        val mergedMetadata = {
          if ( metadataFile.exists() ) {
            val working = new Properties()
            Failable {
              borrow( new BufferedInputStream( new FileInputStream( metadataFile ) ) ) { is =>
                working.load( is )
              }
            }.xwarn("Error loading original metadata. It will be ignored and overwritten!")


            // workaround Scala 2.12 bug https://github.com/scala/bug/issues/10418
            // working.putAll( metadata )

            val iterator = metadata.stringPropertyNames().iterator()
            while( iterator.hasNext() ) {
              val key = iterator.next()
              working.setProperty( key, metadata.getProperty( key ) )
            }

            working
          }
          else {
            metadata
          }
        }

        // this may get slow... we've really complicated things to support suffixes (which renders the mapping between hashes and filenames fuzzy)
        permissiveFindExistingDataFile( dir, hashHex, Option( mergedMetadata.getProperty( Metadata.Key.ContentType ) ) ).foreach { file =>
          WARNING.log( s"Data for hash 0x${hash.hex} re-put. Deleting old data file '${file.getAbsolutePath}'." )
          file.delete()
        }

        val dataFile = newDataFile( dir, hashHex, metadata.getProperty( Metadata.Key.ContentType ) )
        dataFile.replaceContents( data )

        borrow( new BufferedOutputStream( new FileOutputStream( metadataFile ) ) ) { os =>
          mergedMetadata.store( os, s"Metadata for 0x${hashHex}" )
        }

        val hookFuture = postPutHook(PutRecord(dir, hash, data, mergedMetadata, dataFile, metadataFile), ec)
        hookFuture.onComplete {
          case Failure(t) => WARNING.log( "Problem during call to post-put hook.", t )
          case _          => /* ignore */
        }(ec)
        PutResponse.Success( hash, DocStore.Handle.FastFailFile( dataFile ), mergedMetadata )
      }
    }
    catch {
      case NonFatal( t ) => PutResponse.Error( t.getMessage(), Some(t) )
    }
  }

  def get( hash : immutable.Seq[Byte] ) : DocStore.GetResponse = {
    try {
      val hashHex = hash.hex
      val metadataFile = new File( dir, hashHex + ".properties" )

      metadataFile.getCanonicalPath().intern().synchronized { // lock on an interned string representing the hash in this storage directory
        val metadata = {
          val raw = new Properties
          borrow( new BufferedInputStream( new FileInputStream( metadataFile ) ) ) { is =>
            raw.load(is)
          }
          raw
        }
        val mbDataFile = permissiveFindExistingDataFile( dir, hashHex, Option( metadata.getProperty( Metadata.Key.ContentType ) ) )
        mbDataFile match {
          case Some( dataFile ) => {
            val handle = DocStore.Handle.FastFailFile( dataFile )
            GetResponse.Success( handle, metadata )
          }
          case None => GetResponse.NotFound
        }
      }
    }
    catch {
      case fnfe : FileNotFoundException => GetResponse.NotFound
      case NonFatal( t )                => GetResponse.Error( t.getMessage(), Some(t) )
    }
  }
}
