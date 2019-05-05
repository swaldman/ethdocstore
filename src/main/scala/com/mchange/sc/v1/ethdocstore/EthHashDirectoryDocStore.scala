package com.mchange.sc.v1.ethdocstore

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._

import com.mchange.sc.v3.failable._

import scala.collection._
import scala.concurrent.ExecutionContext

import java.io.File

object EthHashDirectoryDocStore {
  def apply( dir : File, putApprover : DocStore.PutApprover = DocStore.PutCheck.AlwaysSucceed )( implicit ec : ExecutionContext ) : Failable[DocStore] = Failable.flatCreate {
    def hasher( data : immutable.Seq[Byte] ) = EthHash.hash( data ).bytes
    DirectoryDocStore( dir, hasher, putApprover )
  }
}
