package com.mchange.sc.v1.ethdocstore.http

import scala.concurrent.Future

import akka.http.scaladsl.server.{AuthenticationFailedRejection,Directive,Directive1,RequestContext}
import AuthenticationFailedRejection.{CredentialsRejected,CredentialsMissing}
import akka.http.scaladsl.model.headers.{BasicHttpCredentials,HttpChallenge}
import akka.http.scaladsl.model.headers.HttpChallenges
import akka.http.scaladsl.server.directives.BasicDirectives.provide
import akka.http.scaladsl.server.directives.SecurityDirectives.extractCredentials
import akka.http.scaladsl.server.directives.FutureDirectives.onSuccess
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.javadsl.server.Route
import akka.http.javadsl.server.directives.RouteAdapter



import org.mindrot.jbcrypt.BCrypt


// modified from http://synkre.com/blog/bcrypt-for-akka-http-password-encryption/

object SynkreBCryptBasicAuthUtils {
  def synkreAuthenticateBasicAsync[T](realm: String, authenticate: (String, String) => Future[Option[T]]) : Directive1[T] = {
    def challenge = HttpChallenges.basic(realm)
    val out : Directive1[T] = extractCredentials.flatMap {
      case Some(BasicHttpCredentials(username, password)) =>
        onSuccess(authenticate(username, password)).flatMap {
          case Some(client) => provide(client)
          case None => reject(AuthenticationFailedRejection(CredentialsRejected, challenge))
        }
      case _ => reject( AuthenticationFailedRejection(CredentialsMissing, challenge) )
    }
    out
  } 

  def synkreBCryptAuthenticate[T]( findBCryptCredentialAndIdentity : (String) => (String, T) )(username: String, password: String) : Future[Option[T]] = {
    val ( credential, identity ) = findBCryptCredentialAndIdentity( username )
    if (BCrypt.checkpw(password, credential)) {
      Future.successful(Some(identity))
    }
    else {
      Future.successful(None)
    }
  }
}

