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
ThisBuild / version := "0.0.4-SNAPSHOT"
ThisBuild / resolvers += ("releases" at nexusReleases)
ThisBuild / resolvers += ("snapshots" at nexusSnapshots)
ThisBuild / publishTo := {
  if (isSnapshot.value) Some("snapshots" at nexusSnapshots ) else Some("releases"  at nexusReleases )
}

ThisBuild / scalaVersion := "2.12.9"

lazy val root = (project in file(".")).settings (
  name := "ethdocstore",
  libraryDependencies ++= Seq(
    "com.mchange" %% "consuela" % "0.0.15",
    "com.mchange" %% "mchange-commons-scala" % "0.4.10",
    "com.mchange" %% "failable" % "0.0.3",
    "com.lihaoyi" %% "scalatags" % "0.6.7",
    "com.mchange"    %% "mlog-scala"            % "0.3.11",
    "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
    "com.typesafe" % "config"  % "1.3.4",
    "org.mindrot"  % "jbcrypt" % "0.4"
  ),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser"
  ).map(_ % circeVersion),
  ethcfgScalaStubsPackage := "com.mchange.sc.v1.ethdocstore.contract",
  mappings in Universal += file("conf/application.conf.sample") -> "conf/application.conf",
  bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/application.conf"""",
  batScriptExtraDefines += """call :add_java "-Dconfig.file=%APP_HOME%\conf\application.conf"""",
  pomExtra := pomExtraForProjectName( name.value )

  //Compile / unmanagedResourceDirectories += { baseDirectory.value / "conf" }
)

lazy val clientPlugin = (project in file("client-plugin")).dependsOn(root).settings (
  name := "ethdocstore-client-plugin",
  sbtPlugin := true,
  addSbtPlugin("com.mchange" % "sbt-ethereum" % "0.1.15"),
  pomExtra := pomExtraForProjectName( name.value )
)

// publication, pom extra stuff

def pomExtraForProjectName( projectName : String ) = {
    <url>https://github.com/swaldman/{projectName}</url>
    <licenses>
      <license>
        <name>GNU Lesser General Public License, Version 2.1</name>
        <url>http://www.gnu.org/licenses/lgpl-2.1.html</url>
        <distribution>repo</distribution>
      </license>
      <license>
        <name>Eclipse Public License, Version 1.0</name>
        <url>http://www.eclipse.org/org/documents/epl-v10.html</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:swaldman/{projectName}.git</url>
      <connection>scm:git:git@github.com:swaldman/{projectName}</connection>
    </scm>
    <developers>
      <developer>
        <id>swaldman</id>
        <name>Steve Waldman</name>
        <email>swaldman@mchange.com</email>
      </developer>
    </developers>
}
