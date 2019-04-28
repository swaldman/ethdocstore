package com.mchange.sc.v1.ethdocstore

import java.io._
import java.util.Properties

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.io._
import com.mchange.sc.v3.failable._

import scala.collection._
import scala.util.control.NonFatal

object DirectoryDocStore {
  def apply( dir : File, hasher : DocStore.Hasher, putApprover : DocStore.PutApprover = DocStore.PutCheck.AlwaysSucceed ) : Failable[DirectoryDocStore] = Failable {
    new DirectoryDocStore( dir.getCanonicalFile, hasher, putApprover )
  }
}
final class DirectoryDocStore private ( dir : File, hasher : DocStore.Hasher, putApprover : DocStore.PutApprover ) extends DocStore.Abstract( hasher, putApprover ) {
  if (! (dir.exists || dir.mkdirs)) throw new FileNotFoundException( s"'${dir}' does not exist and cannot be created." )
  if (! (dir.canRead() && dir.canWrite())) throw new IOException( s"'${dir}' must be both readable and writable, is not." )

  protected def store( hash : immutable.Seq[Byte], data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse = {
    try {
      val hashHex = hash.hex
      val mainFile = new File( dir, hashHex )
      val metadataFile = new File( dir, hashHex + ".properties" )
      mainFile.getCanonicalPath().intern().synchronized {
        mainFile.replaceContents( data )
        borrow( new BufferedOutputStream( new FileOutputStream( metadataFile ) ) ) { os =>
          metadata.store( os, s"Metadata for 0x${hashHex}" )
        }
        DocStore.PutResponse.Success( hash )
      }
    }
    catch {
      case NonFatal( t ) => DocStore.Error( t.getMessage(), Some(t) )
    }
  }

  def get( hash : immutable.Seq[Byte] ) : DocStore.GetResponse = {
    try {
      val hashHex = hash.hex
      val mainFile = new File( dir, hashHex )
      val metadataFile = new File( dir, hashHex + ".properties" )

      mainFile.getCanonicalPath().intern().synchronized {
        val data = mainFile.contentsAsByteSeq
        val metadata = {
          val raw = new Properties
          borrow( new BufferedInputStream( new FileInputStream( metadataFile ) ) ) { is =>
            raw.load(is)
          }
          raw
        }
        DocStore.GetResponse.Success( data, metadata )
      }
    }
    catch {
      case fnfe : FileNotFoundException => DocStore.GetResponse.NotFound
      case NonFatal( t )                => DocStore.Error( t.getMessage(), Some(t) )
    }
  }
}
