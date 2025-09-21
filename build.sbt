import sbt._
import Keys._

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "AnshKetchum"

val chiselVersion = "6.6.0"

// ----------------------
// memctrl core project
// ----------------------
lazy val memctrl = (project in file("memorysim/memctrl"))
  .settings(
    name := "memctrl",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "io.circe" %% "circe-core" % "0.14.7",
      "io.circe" %% "circe-generic" % "0.14.7",
      "io.circe" %% "circe-parser" % "0.14.7"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
  )

// ----------------------
// memorysim "integration" project
// ----------------------
lazy val integration = (project in file("memorysim/integration"))
  .dependsOn(memctrl) // integration builds on core
  .settings(
    name := "integration"
  )

lazy val root = (project in file("."))
  .aggregate(memctrl, integration)
  .settings(
    name := "memorysim"
  )
