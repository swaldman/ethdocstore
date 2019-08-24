package com.mchange.sc.v1.ethdocstore.plugin

import com.mchange.sc.v1.consuela.ethereum.EthAddress

final class OnlyAdministratorException(
  adminAddress : EthAddress,
  otherAddress : EthAddress
) extends Exception( s"Only contract administrator '0x${adminAddress.hex}' can perform this operation. ('0x${otherAddress.hex}' attempted.)") {
  this.setStackTrace( Array.empty[StackTraceElement] )
}
