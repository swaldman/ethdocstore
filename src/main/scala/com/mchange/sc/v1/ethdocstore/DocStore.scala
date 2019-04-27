package com.mchange.sc.v1.ethdocstore

import java.util.Properties

import scala.collection._

object DocStore {
  final object PutResponse {
    case class Success( hash : immutable.Seq[Byte] ) extends PutResponse
  }
  sealed trait PutResponse

  final object GetResponse {
    case class Success( data : immutable.Seq[Byte], metadata : Properties ) extends GetResponse
  }
  sealed trait GetResponse

  final object PutCheck {
    case object Success extends PutCheck
  }
  sealed trait PutCheck

  case class Forbidden( message : String ) extends GetResponse with PutResponse with PutCheck
  case class Error( message : String, cause : Option[Throwable] = None ) extends GetResponse with PutResponse

  type Hasher      = immutable.Seq[Byte] => immutable.Seq[Byte] // data => hash
  type PutApprover = immutable.Seq[Byte] => PutCheck

  abstract class Abstract( hasher : Hasher, putApprover : PutApprover ) extends DocStore {
    protected def store( hash : immutable.Seq[Byte], data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse

    def put( data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse = {
      val hash = hasher.apply( data )
      putApprover.apply( hash ) match {
        case oops : Forbidden => oops
        case PutCheck.Success => store( hash, data, metadata )
      }
    }
    def get( hash : immutable.Seq[Byte] ) : DocStore.GetResponse
  }
}
trait DocStore {
  def put( data : immutable.Seq[Byte], metadata : Properties ) : DocStore.PutResponse
  def get( hash : immutable.Seq[Byte] )                        : DocStore.GetResponse
}
