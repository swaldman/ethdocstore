package com.mchange.sc.v1.ethdocstore

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
    case class  Success( data : immutable.Seq[Byte], metadata : Properties ) extends GetResponse
    case object NotFound extends GetResponse
    case class  Forbidden( message : String ) extends GetResponse
    case class  Error( message : String, cause : Option[Throwable] = None ) extends GetResponse

  }
  sealed trait GetResponse

  final object PutCheck {
    case object Success extends PutCheck
    case class  Forbidden( message : String ) extends PutCheck

    val AlwaysSucceed : PutApprover = _ => Success
  }
  sealed trait PutCheck

  type Hasher      = immutable.Seq[Byte] => immutable.Seq[Byte] // data => hash
  type PutApprover = immutable.Seq[Byte] => PutCheck            // hash => PutCheck

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
