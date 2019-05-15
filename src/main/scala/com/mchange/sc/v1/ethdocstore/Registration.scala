package com.mchange.sc.v1.ethdocstore

case class Registration( username : String, password : String, challengeHex : String, signatureHexRSV : String, expectedAddressHex : String )
