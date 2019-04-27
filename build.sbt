organization := "com.mchange"

name := "ethdocstore"

version := "0.0.1-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.mchange" %% "consuela" % "0.0.13",
  "com.mchange" %% "mchange-commons-scala" % "0.4.9",
  "com.mchange" %% "failable" % "0.0.3"
)

ethcfgScalaStubsPackage := "com.mchange.sc.v1.ethdocstore.contract"

