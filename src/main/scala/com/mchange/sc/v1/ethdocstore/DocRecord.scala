package com.mchange.sc.v1.ethdocstore

import io.circe._, io.circe.generic.auto._, io.circe.generic.semiauto._, io.circe.parser._, io.circe.syntax._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.stub.sol

object DocRecord {
  implicit val docRecordEncoder : Encoder[DocRecord] = new Encoder[DocRecord] {
    final def apply( dr : DocRecord ): Json = Json.obj(
      ("docHash", Json.fromString( dr.docHash.widen.hex )),
      ("name", Json.fromString( dr.name )),
      ("description", Json.fromString( dr.description )),
      ("timestamp", Json.fromBigInt( dr.timestamp.widen ))
    )
  }
}
case class DocRecord( docHash : sol.Bytes32, name : sol.String, description : String, timestamp : sol.UInt )

