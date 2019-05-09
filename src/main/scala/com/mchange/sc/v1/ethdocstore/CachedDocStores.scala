package com.mchange.sc.v1.ethdocstore

import java.io.BufferedInputStream
import java.util.Properties

import scala.collection._
import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure,Success}

import com.mchange.v1.cachedstore._

import com.mchange.sc.v2.io._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress}, stub.sol
import com.mchange.sc.v1.ethdocstore.contract.AsyncDocHashStore

import DocStore.GetResponse

object CachedDocStores {
  final case class DocKey( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] )

  final object MiniCache {
    trait Manager[K <: AnyRef,V <: AnyRef] extends CachedStore.Manager {
      protected def _isDirty( key : K, cached : V ) : Boolean
      protected def _recreateFromKey( key : K ) : V

      final def isDirty( key : Object, cached : Object ) : Boolean = _isDirty( key.asInstanceOf[K], cached.asInstanceOf[V] )
      final def recreateFromKey( key : Object )          : Object  = _recreateFromKey( key.asInstanceOf[K] )
    }
    trait OptFutManager[K <: AnyRef,V0] extends Manager[K,Option[Future[V0]]] {
      protected def uncacheableValue( value : V0 ) = false

      protected def notFoundOrAlreadyFailedOrUncacheableValue( cached : Option[Future[V0]] ) : Boolean = {
        cached match {
          case Some( fut ) => {
            fut.value match {
              case Some( _ : Failure[V0] )  => true // already failed
              case Some( Success( value ) ) => uncacheableValue( value )
              case None                     => false
            }
          }
          case None => true // not found
        }
      }
      protected final def _isDirty( key : K, cached : Option[Future[V0]] ) : Boolean = notFoundOrAlreadyFailedOrUncacheableValue( cached )
    }
  }
  // MT: access protected by its own lock
  private trait MiniCache[K <: AnyRef, V <: AnyRef] {
    protected val manager : MiniCache.Manager[K,V]

    private lazy val cachedStore = CachedStoreFactory.createSynchronousCleanupSoftKeyCachedStore( manager )

    def find( k : K ) : V = this.synchronized {
      cachedStore.find( k ).asInstanceOf[V]
    }

    def markDirty( k : K ) : Unit = this.synchronized {
      cachedStore.removeFromCache( k )
    }
  }
}

import CachedDocStores._

class CachedDocStores( docStores : immutable.Map[EthAddress,DocStore], nodeInfo : NodeInfo, cacheableDataMaxBytes : Long )( implicit ec : ExecutionContext ) {

  def attemptSynchronousMetadata( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] ) : Option[Properties] = {
    attemptGetResponse( docStoreAddress, docHash ) flatMap { f_gr =>
      Await.result( f_gr, Duration.Inf ) match {
        case GetResponse.Success( handle, metadata ) => Some( metadata )
        case _                                       => None
      }
    }
  }

  def attemptGetResponse( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] ) : Option[Future[DocStore.GetResponse]] = GetResponseCache.find( DocKey( docStoreAddress, docHash ) )

  def markDirtyGetResponse( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] ) : Unit = GetResponseCache.markDirty( DocKey( docStoreAddress, docHash ) )

  def attemptDocRecordSeq( docStoreAddress : EthAddress ) : Option[Future[immutable.Seq[DocRecord]]] = DocRecordSeqCache.find( docStoreAddress )

  def markDirtyDocRecordSeq( docStoreAddress : EthAddress ) : Unit = DocRecordSeqCache.markDirty( docStoreAddress )

  def attemptHandleData( handle : DocStore.Handle ) : Option[Future[immutable.Seq[Byte]]] = HandleDataCache.find( handle )

  def markDirtyHandleData( handle : DocStore.Handle ) : Unit = HandleDataCache.markDirty( handle )

  // MT: access protected by its own lock
  private final object GetResponseCache extends MiniCache[DocKey,Option[Future[GetResponse]]] {
    protected override val manager = new MiniCache.OptFutManager[DocKey,GetResponse] {

      protected override def uncacheableValue( value : GetResponse ) = {
        value match {
          case GetResponse.Success( _, _ ) => false
          case _                           => true
        }
      }

      def _recreateFromKey( docKey : DocKey ) : Option[Future[GetResponse]] = {
        docStores.get( docKey.docStoreAddress ).flatMap { docStore =>
          Some(
            Future {
              blocking {
                docStore.get( docKey.docHash )
              }
            }
          )
        }
      }
    }
  }

  // MT: access protected by its own lock
  private final object DocRecordSeqCache extends MiniCache[EthAddress,Option[Future[immutable.Seq[DocRecord]]]] {
    protected val manager = new MiniCache.OptFutManager[EthAddress,immutable.Seq[DocRecord]] {
      def _recreateFromKey( address : EthAddress ) : Option[Future[immutable.Seq[DocRecord]]] = {
        docStores.get( address ) map { _ =>
          implicit val sender = stub.Sender.Default

          val docHashStore = AsyncDocHashStore.build( jsonRpcUrl = nodeInfo.nodeUrl, chainId = nodeInfo.mbChainId, contractAddress = address )
          docHashStore.constant.size() flatMap { sz =>
            Future.sequence {
              for ( i <- (BigInt(0) until sz.widen).reverse ) yield {
                for {
                  docHash     <- docHashStore.constant.docHashes(sol.UInt(i))
                  name        <- docHashStore.constant.name( docHash )
                  description <- docHashStore.constant.description( docHash )
                  timestamp   <- docHashStore.constant.timestamp( docHash )
                }
                yield {
                  DocRecord( docHash, name, description, timestamp )
                }
              }
            }
          }
        }
      }
    }
  }

  // MT: access protected by its own lock
  private final object HandleDataCache extends MiniCache[DocStore.Handle,Option[Future[immutable.Seq[Byte]]]] {
    protected val manager = new MiniCache.OptFutManager[DocStore.Handle,immutable.Seq[Byte]] {
      def _recreateFromKey( handle : DocStore.Handle ) : Option[Future[immutable.Seq[Byte]]] = {
        handle.contentLength flatMap { len =>
          if ( len < cacheableDataMaxBytes ) {
            Some( Future( blocking ( new BufferedInputStream( handle.newInputStream() ).remainingToByteSeq ) ) )
          }
          else {
            None
          }
        }
      }
    }
  }
}

