# Integration with Chipyard 

Note that you will need to change

`chipyard/build.sbt` each time - 


```scala
// Memorysim Integration
lazy val memorysim_core  = freshProject("memorysim-core", file("generators/memorysim/memorysim/memctrl"))
  .dependsOn(rocketchip)
  .settings(
    libraryDependencies ++= rocketLibDeps.value ++ Seq(
      "io.circe" %% "circe-core" % "0.14.7",
      "io.circe" %% "circe-generic" % "0.14.7",
      "io.circe" %% "circe-parser" % "0.14.7"
    )
  )
  .settings(commonSettings)

lazy val memorysim  = freshProject("memorysim-integration", file("generators/memorysim/memorysim/integration"))
  .dependsOn(rocketchip, memorysim_core)
  .settings(
    libraryDependencies ++= rocketLibDeps.value ++ Seq(
      "io.circe" %% "circe-core" % "0.14.7",
      "io.circe" %% "circe-generic" % "0.14.7",
      "io.circe" %% "circe-parser" % "0.14.7"
    )
  )
  .settings(commonSettings)
```

Sample integration. See how there's a module for `memctrl`, which is connected to the "core" integration module? 

This format needs to be followed for all additional modules added in.

Ideally, we'd just have Chipyard call `generators/memorysim/build.sbt` ... 

This is a big wish!!