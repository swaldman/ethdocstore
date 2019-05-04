// repositories stuff

val nexus = "https://oss.sonatype.org/"
val nexusSnapshots = nexus + "content/repositories/snapshots";
val nexusReleases = nexus + "service/local/staging/deploy/maven2";

// repeated versions

val akkaHttpVersion = "10.1.6"
val akkaVersion     = "2.5.19"
val circeVersion    = "0.10.0"

// non-auto plugins

enablePlugins(JavaAppPackaging)

// settings and projects

ThisBuild / organization := "com.mchange"
ThisBuild / version := "0.0.1-SNAPSHOT"
ThisBuild / resolvers += ("releases" at nexusReleases)
ThisBuild / resolvers += ("snapshots" at nexusSnapshots)
ThisBuild / publishTo := {
  if (isSnapshot.value) Some("snapshots" at nexusSnapshots ) else Some("releases"  at nexusReleases )
}

lazy val root = (project in file(".")).settings (
  name := "ethdocstore",
  libraryDependencies ++= Seq(
    "com.mchange" %% "consuela" % "0.0.13",
    "com.mchange" %% "mchange-commons-scala" % "0.4.10-SNAPSHOT" changing(),
    "com.mchange" %% "failable" % "0.0.3",
    "com.lihaoyi" %% "scalatags" % "0.6.7",
    "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
    "com.typesafe" % "config" % "1.3.4"
  ),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser"
  ).map(_ % circeVersion),
  ethcfgScalaStubsPackage := "com.mchange.sc.v1.ethdocstore.contract",
  Compile / unmanagedResourceDirectories += { baseDirectory.value / "conf" }
)

lazy val clientPlugin = (project in file("client-plugin")).dependsOn(root).settings (
  name := "ethdocstore-client-plugin",
  sbtPlugin := true,
  addSbtPlugin("com.mchange" % "sbt-ethereum" % "0.1.10")
)
