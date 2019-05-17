package com.mchange.sc.v1.ethdocstore.http

import scala.collection._
import com.mchange.sc.v2.collection.immutable._

object ChallengeManager {
  private val PrefixBytes = "Signature Challenge - Random Bytes: ".getBytes( "US-ASCII" )
}
class ChallengeManager( randomFactory : () => java.security.SecureRandom, validityMillis : Int, postPrefixChallengeLength : Int = 32 ) {

  val random = randomFactory()

  var challengeToTimestamp = immutable.Map.empty[immutable.Seq[Byte],Long]

  def newChallenge() : immutable.Seq[Byte] = this.synchronized {
    clean()
    val arr = Array.ofDim[Byte]( postPrefixChallengeLength )
    random.nextBytes( arr )
    val out = ImmutableArraySeq.Byte( ChallengeManager.PrefixBytes ++ arr )
    challengeToTimestamp = challengeToTimestamp + Tuple2( out, System.currentTimeMillis() )
    out
  }

  def verifyChallenge( challenge : immutable.Seq[Byte] ) : Boolean = this.synchronized {
    try {
      clean()
      challengeToTimestamp.contains( challenge )
    }
    finally {
      challengeToTimestamp = challengeToTimestamp - challenge // never let a challenge be reused
    }
  }

  private def clean() {
    val time = System.currentTimeMillis()
    challengeToTimestamp = {
      challengeToTimestamp.filter{ case ( _, ts ) =>
        val validUntil = ts + validityMillis
        time < validUntil
      }.toMap
    }
  }
}
