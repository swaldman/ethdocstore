package com.mchange.sc.v1.ethdocstore.http

object PasswordManager {
  trait Standard[T] extends PasswordManager[T] {
    def store( username : String, credential : String, identity : T ) : Unit
    def storedCredentialAndIdentity( username : String ) : Option[( String, T )]

    def createStorableCredential( password : String ) : String
    def checkAgainstCredential( password : String, storedCredential : String ) : Boolean

    def set( username : String, password : String, identity : T ) : Unit = {
      val storableCredential = createStorableCredential( password )
      store( username, storableCredential, identity )
    }
    def authenticate( username : String, password : String ) : Option[T] = {
      for {
        ( storedCredential, identity ) <- storedCredentialAndIdentity( username )
        if (checkAgainstCredential( password, storedCredential ))
      }
      yield {
        identity
      }
    }
  }
  trait BCrypt[T] extends Standard[T] {
    import org.mindrot.jbcrypt.BCrypt

    def log_rounds = 10 // see https://www.mindrot.org/projects/jBCrypt/

    def createStorableCredential( password : String ) : String = {
      BCrypt.hashpw( password, BCrypt.gensalt( log_rounds ) )
    }
    def checkAgainstCredential( password : String, storedCredential : String ) : Boolean = {
      BCrypt.checkpw( password, storedCredential )
    }
  }
}
trait PasswordManager[T] {
  def set( username : String, password : String, identity : T ) : Unit
  def authenticate( username : String, password : String )      : Option[T]
}
