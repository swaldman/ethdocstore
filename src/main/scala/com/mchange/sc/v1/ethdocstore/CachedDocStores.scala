package com.mchange.sc.v1.ethdocstore

import java.io.BufferedInputStream

import scala.collection._
import scala.concurrent.{blocking, ExecutionContext,Future}
import scala.util.Failure

import com.mchange.v1.cachedstore._

import com.mchange.sc.v2.io._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress}, stub.sol
import com.mchange.sc.v1.ethdocstore.contract.AsyncDocHashStore

import DocStore.GetResponse

object CachedDocStores {
  final case class DocKey( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] )

  final case class UnsuccessfulGetException( getResponse : GetResponse ) extends Exception( s"Unsuccessful DocStore.GetResponse: ${getResponse}" )

  final object MiniCache {
    trait Manager[K <: AnyRef,V <: AnyRef] extends CachedStore.Manager {
      def _isDirty( key : K, cached : V ) : Boolean
      def _recreateFromKey( key : K ) : V

      final def isDirty( key : Object, cached : Object ) : Boolean = _isDirty( key.asInstanceOf[K], cached.asInstanceOf[V] )
      final def recreateFromKey( key : Object )          : Object  = _recreateFromKey( key.asInstanceOf[K] )
    }
    trait OptFutManager[K <: AnyRef,V0] extends Manager[K,Option[Future[V0]]] {
      def notFoundOrAlreadyFailed( cached : Option[Future[V0]] ) : Boolean = {
        cached match {
          case Some( fut ) => {
            fut.value match {
              case Some( _ : Failure[Any] ) => true // already failed
              case _                        => false
            }
          }
          case None => true // not found
        }
      }
      final def _isDirty( key : K, cached : Option[Future[V0]] ) : Boolean = notFoundOrAlreadyFailed( cached )
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

  def attemptGetResponse( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] ) : Option[Future[DocStore.GetResponse.Success]] = SuccessCache.find( DocKey( docStoreAddress, docHash ) )

  def markDirtyGetResponse( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] ) : Unit = SuccessCache.markDirty( DocKey( docStoreAddress, docHash ) )

  def attemptDocRecordSeq( docStoreAddress : EthAddress ) : Option[Future[immutable.Seq[DocRecord]]] = DocRecordSeqCache.find( docStoreAddress )

  def markDirtyDocRecordSeq( docStoreAddress : EthAddress ) : Unit = DocRecordSeqCache.markDirty( docStoreAddress )

  def attemptHandleData( handle : DocStore.Handle ) : Option[Future[immutable.Seq[Byte]]] = HandleDataCache.find( handle )

  def markDirtyHandleData( handle : DocStore.Handle ) : Unit = HandleDataCache.markDirty( handle )

  // MT: access protected by its own lock
  private final object SuccessCache extends MiniCache[DocKey,Option[Future[GetResponse.Success]]] {
    protected override val manager = new MiniCache.OptFutManager[DocKey,GetResponse.Success] {
      def _recreateFromKey( docKey : DocKey ) : Option[Future[GetResponse.Success]] = {
        docStores.get( docKey.docStoreAddress ).flatMap { docStore =>
          Some(
            Future {
              blocking {
                import DocStore.GetResponse._
                docStore.get( docKey.docHash ) match {
                  case yay : Success             => yay
                  case unsuccessful              => throw new UnsuccessfulGetException( unsuccessful )
                }
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

