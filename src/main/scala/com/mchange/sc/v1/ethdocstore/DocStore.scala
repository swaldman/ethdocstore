package com.mchange.sc.v1.ethdocstore

import java.io._
import java.util.Properties

import scala.collection._

// we could (previously did) share the definitions of Forbidden and Error, but it's just easier
// to read with repetitive, scoped definitions.

object DocStore {
  final object PutResponse {
    case class Success( hash : immutable.Seq[Byte] ) extends PutResponse
    case class Forbidden( message : String ) extends PutResponse
    case class Error( message : String, cause : Option[Throwable] = None ) extends PutResponse
  }
  sealed trait PutResponse

  final object GetResponse {
    case class  Success( handle : Handle, metadata : Properties ) extends GetResponse
    case object NotFound extends GetResponse
    case class  Forbidden( message : String ) extends GetResponse
    case class  Error( message : String, cause : Option[Throwable] = None ) extends GetResponse
  }
  sealed trait GetResponse

  final object PutCheck {
    case object Success extends PutCheck
    case class  Forbidden( message : String ) extends PutCheck

  }
  sealed trait PutCheck

  type Hasher = immutable.Seq[Byte] => immutable.Seq[Byte] // data => hash

  final object PutApprover {
    val AlwaysSucceed : PutApprover = _ => PutCheck.Success
  }
  type PutApprover = immutable.Seq[Byte] => PutCheck            // hash => PutCheck

  object Handle {
    case class FastFailFile( file : File ) extends Handle {
      val contentLength = {
        if (! file.exists() ) throw new FileNotFoundException( s"No such file '${file}'.")
        if (file.isDirectory() ) throw new IOException( s"'${file}' is a directory, not a document.")
        Some( file.length )
      }

      def newInputStream() : InputStream = new FileInputStream( file )
    }
  }
  trait Handle {
    def newInputStream() : InputStream
    def contentLength    : Option[Long]
  }

  abstract class Abstract( hasher : Hasher, putApprover : PutApprover ) extends DocStore {
    protected def store( hash : immutable.Seq[Byte], data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse

    def put( data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse = {
      val hash = hasher.apply( data )
      putApprover.apply( hash ) match {
        case PutCheck.Success              => store( hash, data, metadata )
        case PutCheck.Forbidden( message ) => PutResponse.Forbidden( message )
      }
    }
    def get( hash : immutable.Seq[Byte] ) : DocStore.GetResponse
  }
}
trait DocStore {
  def put( data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse
  def get( hash : immutable.Seq[Byte] )                        : DocStore.GetResponse
}
