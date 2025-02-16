name := """otoroshi-manual"""
organization := "fr.maif"
version := "16.8.0-dev"
scalaVersion := "2.13.1"

lazy val docs = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Otoroshi",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    paradoxRoots := List("index.html"),
    (Compile / paradoxMarkdownToHtml / excludeFilter) :=
      (Compile / paradoxMarkdownToHtml / excludeFilter).value ||
        ParadoxPlugin.InDirectoryFilter(
          (Compile / paradox / sourceDirectory).value / "includes"
        )
  )