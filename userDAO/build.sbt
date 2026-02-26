ThisBuild / scalaVersion := "2.13.12"

ThisBuild / organization := "com.coride"
ThisBuild / publishMavenStyle := true

lazy val root = (project in file(".")).
  settings(
    name := "userDAO",
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "dynamodb" % "2.25.43",
      "software.amazon.awssdk" % "url-connection-client" % "2.25.43",
      "com.typesafe" % "config" % "1.4.2",
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    ),
    assembly / assemblyJarName := "userDAO-assembly.jar",
    ThisBuild / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case _ => MergeStrategy.first
    }
  )