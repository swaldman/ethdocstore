package com.mchange.sc.v1.ethdocstore.plugin

import com.mchange.sc.v1.consuela.ethereum.EthAddress

final class OnlyAuthorizedException(
  unauthorizedAddress : EthAddress
) extends Exception( s"Only authorized addresses can perform this operation. '0x${unauthorizedAddress.hex}' is not authorized.") {
  this.setStackTrace( Array.empty[StackTraceElement] )
}
