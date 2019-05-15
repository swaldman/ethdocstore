package com.mchange.sc.v1.ethdocstore.http

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela.ethereum.EthAddress

import java.io._
import java.util.Properties

class PropsFilePasswordManager( propsFile : File ) extends PasswordManager.BCrypt[EthAddress] {

  private def load() : Properties = {
    val props = new Properties()
    borrow( new BufferedInputStream( new FileInputStream( propsFile ) ) ) { is =>
      props.load(is)
    }
    props
  }
  private def store( props : Properties ) : Unit = {
    borrow( new BufferedOutputStream( new FileOutputStream( propsFile ) ) ) { os =>
      props.store( os, "Hush. Passwords." )
    }
  }
  private def toValue( credential : String, identity : EthAddress ) = s"${credential} ${identity.hex}"

  private def fromValue( value : String ) = {
    val arr = value.split("""\s""")
    assert( arr.length == 2, s"We expect two space delimited fields, found '${value}'." )
    ( arr(0), EthAddress(arr(1)) )
  }

  def store( username : String, credential : String, identity : EthAddress ) : Unit = this.synchronized {
    val props = this.load()
    props.setProperty( username, toValue( credential, identity ) )
    store( props )
  }

  def storedCredentialAndIdentity( username : String ) : Option[( String, EthAddress )] = this.synchronized {
    val props = this.load()
    Option( props.getProperty( username ) ).map( fromValue )
  }
}
