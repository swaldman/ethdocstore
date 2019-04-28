val akkaHttpVersion = "10.1.6"
val akkaVersion     = "2.5.19"

organization := "com.mchange"

name := "ethdocstore"

version := "0.0.1-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.mchange" %% "consuela" % "0.0.13",
  "com.mchange" %% "mchange-commons-scala" % "0.4.10-SNAPSHOT" changing(),
  "com.mchange" %% "failable" % "0.0.3",
  "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream"          % akkaVersion
)

ethcfgScalaStubsPackage := "com.mchange.sc.v1.ethdocstore.contract"

// repositories stuff

val nexus = "https://oss.sonatype.org/"
val nexusSnapshots = nexus + "content/repositories/snapshots";
val nexusReleases = nexus + "service/local/staging/deploy/maven2";

resolvers += ("releases" at nexusReleases)
resolvers += ("snapshots" at nexusSnapshots)
