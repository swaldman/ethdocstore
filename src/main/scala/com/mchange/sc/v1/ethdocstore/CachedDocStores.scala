package com.mchange.sc.v1.ethdocstore

import java.io.BufferedInputStream
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

import scala.collection._
import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure,Success}

import com.mchange.v1.cachedstore._

import com.mchange.sc.v2.io._
import com.mchange.sc.v1.consuela.ethereum.{stub, EthAddress}, stub.sol
import com.mchange.sc.v1.ethdocstore.contract.AsyncDocHashStore

import com.mchange.sc.v1.log.MLevel._

import DocStore.GetResponse

object CachedDocStores {
  lazy implicit val logger = mlogger(this)

  final case class DocKey( docStoreAddress : EthAddress, docHash : immutable.Seq[Byte] )

  final object MiniCache {
    trait Manager[K <: AnyRef,V <: AnyRef] extends CachedStore.Manager {
      protected def _isDirty( key : K, cached : V ) : Boolean
      protected def _recreateFromKey( key : K ) : V

      final def isDirty( key : Object, cached : Object ) : Boolean = TRACE.logEval( s"dirty? key '${key}', cached: '${cached}'" )(_isDirty( key.asInstanceOf[K], cached.asInstanceOf[V] ) )
      final def recreateFromKey( key : Object )          : Object  = TRACE.logEval( s"Recreating from key '${key}'" )            ( _recreateFromKey( key.asInstanceOf[K] ) )
    }
    trait OptFutManager[K <: AnyRef,V0] extends Manager[K,Option[Future[V0]]] {
      protected def uncacheableValue( value : V0 ) = false

      protected def notFoundOrAlreadyFailedOrUncacheableValue( cached : Option[Future[V0]] ) : Boolean = {
        cached match {
          case Some( fut ) => {
            fut.value match {
              case Some( _ : Failure[V0] )  => true // already failed
              case Some( Success( value ) ) => uncacheableValue( value )
              case None                     => false // still resolving
            }
          }
          case None => true // not found
        }
      }
      protected final def _isDirty( key : K, cached : Option[Future[V0]] ) : Boolean = notFoundOrAlreadyFailedOrUncacheableValue( cached )
    }
  }
  private trait MiniCache[K <: AnyRef, V <: AnyRef] {
    protected val manager : MiniCache.Manager[K,V]

    // MT: access protected by its own lock
    private lazy val cachedStore = CachedStoreFactory.createSynchronousCleanupSoftKeyCachedStore( manager )

    def reset() : Unit = this.synchronized {
      cachedStore.reset()
    }

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

  val docHashStores = docStores.map {
    case ( k, v ) => ( k, AsyncDocHashStore.build( jsonRpcUrl = nodeInfo.nodeUrl, chainId = nodeInfo.mbChainId, contractAddress = k, eventConfirmations = 0 ) )
  }

  private val storeWatcher = new StoreWatcher() // don't construct lazily, so we get publishers set up

  private lazy val miniCaches : List[MiniCache[_,_]] = GetResponseCache :: DocRecordSeqCache :: HandleDataCache :: Nil

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

  def attemptUserCanUpdate( docStoreAddress : EthAddress, userAddress : EthAddress ) : Option[Future[Boolean]] = UserCanUpdateCache.find( Tuple2( docStoreAddress, userAddress ) )

  def markDirtyUserCanUpdate( docStoreAddress : EthAddress, userAddress : EthAddress ) : Unit = UserCanUpdateCache.markDirty( Tuple2( docStoreAddress, userAddress ) )

  def attemptHandleData( handle : DocStore.Handle ) : Option[Future[immutable.Seq[Byte]]] = HandleDataCache.find( handle )

  def markDirtyHandleData( handle : DocStore.Handle ) : Unit = HandleDataCache.markDirty( handle )

  def attemptResource( absoluteResourceKey : String ) : Option[Future[immutable.Seq[Byte]]] = ResourcesCache.find( absoluteResourceKey )

  def markDirtyResource( absoluteResourceKey : String ) : Unit = ResourcesCache.markDirty( absoluteResourceKey )

  def close() : Unit = {
    miniCaches.foreach( _.reset() )
    storeWatcher.close()
  }

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

  private final object DocRecordSeqCache extends MiniCache[EthAddress,Option[Future[immutable.Seq[DocRecord]]]] {
    protected val manager = new MiniCache.OptFutManager[EthAddress,immutable.Seq[DocRecord]] {
      def _recreateFromKey( address : EthAddress ) : Option[Future[immutable.Seq[DocRecord]]] = {
        docStores.get( address ) map { _ =>
          implicit val sender = stub.Sender.Default

          val docHashStore = docHashStores( address )
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

  private final object UserCanUpdateCache extends MiniCache[(EthAddress,EthAddress),Option[Future[Boolean]]] {
    protected val manager = new MiniCache.OptFutManager[Tuple2[EthAddress,EthAddress],Boolean] {
      def _recreateFromKey( docHashStoreUserPair : Tuple2[EthAddress,EthAddress] ) : Option[Future[Boolean]] = {
        val ( docHashStoreAddress, userAddress ) = docHashStoreUserPair
        implicit val sender = stub.Sender.Default
        Some( docHashStores( docHashStoreAddress ).constant.canUpdate( userAddress ) )
      }
    }
  }

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

  private final object ResourcesCache extends MiniCache[String,Option[Future[immutable.Seq[Byte]]]] {
    protected val manager = new MiniCache.OptFutManager[String,immutable.Seq[Byte]] {
      def _recreateFromKey( absoluteResourceKey : String ) : Option[Future[immutable.Seq[Byte]]] = {
        Option( this.getClass().getResourceAsStream( absoluteResourceKey ) ).map { is =>
          Future {
            blocking {
              new BufferedInputStream( is ).remainingToByteSeq
            }
          }
        }
      }
    }
  }


  private class StoreWatcher {
    import org.reactivestreams._
    import AsyncDocHashStore.Event._

    // MT: protected by this' lock
    private val watched = {
      mutable.Map.empty[EthAddress,AddressSubscriber] ++ docStores.map { case ( addr, _ ) => ( addr, new AddressSubscriber( addr ) ) }
    }

    def drop( address : EthAddress ) : Unit = this.synchronized {
      watched.get( address ).foreach( _.unsubscribe() )
      watched -= address
    }

    def close() : Unit = this.synchronized {
      val keys = immutable.Seq.empty ++ watched.keySet
      keys.foreach( drop )
    }

    class AddressSubscriber( address : EthAddress ) extends Subscriber[AsyncDocHashStore.Event] {
      val docHashStore = docHashStores( address )
      val subscriptionRef = new AtomicReference[Option[Subscription]]( None )

      this.subscribe()

      def subscribe() : Unit = {
        docHashStore.subscribe( this )
        INFO.log( s"${this} subscribed." )
      }
      def unsubscribe() : Unit = {
        subscriptionRef.get.foreach { s =>
          s.cancel()
          INFO.log( s"${this} unsubscribed." )
        }
        subscriptionRef.set( None )
      }

      def onComplete() : Unit = {
        INFO.log( s"${docHashStore} subscription has has signaled it has completed." )
        subscriptionRef.set( None )
      }
      def onError( t : Throwable ) = {
        WARNING.log( s"${docHashStore} (as publisher) failed, signaling an error.", t )
        subscriptionRef.set( None )
        subscribe() // see if we can resubscribe
      }
      def onNext(evt : AsyncDocHashStore.Event) = {
        evt match {
          case _ : Stored | _ : Amended => markDirtyDocRecordSeq( address )
          case _ : Closed => {
            subscriptionRef.get.foreach( _.cancel() )
            drop( address )
          }
          case evt @ Authorized( userAddress )   =>  markDirtyUserCanUpdate( evt.sourceAddress, userAddress )
          case evt @ Deauthorized( userAddress ) =>  markDirtyUserCanUpdate( evt.sourceAddress, userAddress )
          case _ => DEBUG.log( s"${this} encountered and ignored event ${evt}" )
        }
      }
      def onSubscribe(s : Subscription) = {
        s.request( Long.MaxValue ) // this is incautious, but it's unlikely we'll be overwhelmed
        subscriptionRef.set( Some( s ) )
        FINE.log( s"${this} has subscribed to ${s}." )
      }
      override def toString() = s"AddressSubscriber( 0x${address.hex} )"
    }
  }
}

