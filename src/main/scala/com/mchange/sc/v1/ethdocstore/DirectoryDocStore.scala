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

import DocStore.{PutResponse,GetResponse,PutCheck}

object DirectoryDocStore {

  implicit lazy val logger = mlogger(this)

  def apply( dir : File, hasher : DocStore.Hasher, putApprover : DocStore.PutApprover = PutCheck.AlwaysSucceed, postPutHook : PostPutHook = PostPutHook.NoOp )( implicit ec : ExecutionContext ) : Failable[DirectoryDocStore] = {
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

  final object PostPutHook {
    private val OK : Future[Unit] = Future.unit

    val NoOp : PostPutHook = ( pr : PutRecord, ec : ExecutionContext ) => OK

    val GitAddCommitPush = ( pr : PutRecord, ec : ExecutionContext ) => Future {
      val dotgit = new File( pr.dir, ".git" )
      if (dotgit.exists() && dotgit.isDirectory()) {
        import scala.sys.process._

        val processLogger = ProcessLogger( line => DEBUG.log( "GitAddCommitPush - stdout: ${line}" ), line => DEBUG.log( "GitAddCommitPush - stderr: ${line}" ) )

        val f_addDataFile     = relativizePath( pr.dir, pr.dataFile ) map { df => Process("git" :: "add" :: df :: Nil, Some(pr.dir)) }
        val f_addMetadataFile = relativizePath( pr.dir, pr.metadataFile ) map { mdf => Process("git" :: "add" :: mdf :: Nil, Some(pr.dir)) }

        val adds = immutable.Seq( f_addDataFile, f_addMetadataFile ).filter( _.isSucceeded ).map( _.assert )

        if ( adds.nonEmpty ) {
          blocking {
            adds.foreach { add =>
              val aev = add.!( processLogger );
              if (aev != 0) {
                WARNING.log( s"GitAddCommitPush: Add '${add}' failed with exit value ${aev}. See DEBUG logs for output." )
              }
            }

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
        else {
          WARNING.log( s"GitAddCommitPush: Failed to 'git add' any files after storage. See DEBUG logs for output." )
        }
      }
      else {
        WARNING.log("File storage directory '${pr.dir}' does not appear to be a git repository. git add / commit / push skipped.")
      }
    }( ec )
  }
  type PostPutHook = (PutRecord, ExecutionContext) => Future[Unit]
}

import DirectoryDocStore._

final class DirectoryDocStore private ( dir : File, hasher : DocStore.Hasher, putApprover : DocStore.PutApprover, postPutHook : PostPutHook )( implicit ec : ExecutionContext ) extends DocStore.Abstract( hasher, putApprover ) {
  if (! (dir.exists || dir.mkdirs)) throw new FileNotFoundException( s"'${dir}' does not exist and cannot be created." )
  if (! dir.isDirectory) throw new IOException("File storage directory '${dir}' must be a directory, is not.")
  if (! (dir.canRead() && dir.canWrite())) throw new IOException( s"'${dir}' must be both readable and writable, is not." )

  protected def store( hash : immutable.Seq[Byte], data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse = {
    try {
      val hashHex = hash.hex
      val dataFile = new File( dir, hashHex )
      val metadataFile = new File( dir, hashHex + ".properties" )
      dataFile.getCanonicalPath().intern().synchronized {
        dataFile.replaceContents( data )
        borrow( new BufferedOutputStream( new FileOutputStream( metadataFile ) ) ) { os =>
          metadata.store( os, s"Metadata for 0x${hashHex}" )
        }
        val hookFuture = postPutHook(PutRecord(dir, hash, data, metadata, dataFile, metadataFile), ec)
        hookFuture.onComplete {
          case Failure(t) => WARNING.log( "Problem during call to post-put hook.", t )
          case _          => /* ignore */
        }(ec)
        PutResponse.Success( hash )
      }
    }
    catch {
      case NonFatal( t ) => PutResponse.Error( t.getMessage(), Some(t) )
    }
  }

  def get( hash : immutable.Seq[Byte] ) : DocStore.GetResponse = {
    try {
      val hashHex = hash.hex
      val dataFile = new File( dir, hashHex )
      val metadataFile = new File( dir, hashHex + ".properties" )

      dataFile.getCanonicalPath().intern().synchronized {
        val data = dataFile.contentsAsByteSeq
        val metadata = {
          val raw = new Properties
          borrow( new BufferedInputStream( new FileInputStream( metadataFile ) ) ) { is =>
            raw.load(is)
          }
          raw
        }
        GetResponse.Success( data, metadata )
      }
    }
    catch {
      case fnfe : FileNotFoundException => GetResponse.NotFound
      case NonFatal( t )                => GetResponse.Error( t.getMessage(), Some(t) )
    }
  }
}
