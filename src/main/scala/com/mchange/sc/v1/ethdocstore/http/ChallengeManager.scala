package com.mchange.sc.v1.ethdocstore.http

import scala.collection._
import com.mchange.sc.v2.collection.immutable._

class ChallengeManager( randomFactory : () => java.security.SecureRandom, validityMillis : Int, challengeLength : Int = 32 ) {

  val random = randomFactory()

  var challengeToTimestamp = immutable.Map.empty[immutable.Seq[Byte],Long]

  def newChallenge() : immutable.Seq[Byte] = this.synchronized {
    clean()
    val arr = Array.ofDim[Byte]( challengeLength )
    random.nextBytes( arr )
    val out = ImmutableArraySeq.Byte( arr )
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
