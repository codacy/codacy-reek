package codacy.reek

import java.io.{File, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import com.codacy.plugins.api
import com.codacy.plugins.api.results.Result.Issue
import com.codacy.plugins.api.results.Tool.Specification
import com.codacy.plugins.api.results.{Parameter, Pattern, Result, Tool}
import com.codacy.plugins.api.{paramValueToJsValue, Options}
import com.codacy.tools.scala.seed.utils.CommandRunner
import play.api.libs.json._

import scala.io.Source
import scala.util.{Failure, Properties, Success, Try}

object Reek extends Tool {

  // Gemfile is analysed
  private val filesToIgnore: Set[String] =
    Set("Gemfile.lock").map(_.toLowerCase())

  override def apply(
      source: api.Source.Directory,
      configuration: Option[List[Pattern.Definition]],
      files: Option[Set[api.Source.File]],
      options: Map[Options.Key, Options.Value]
  )(implicit specification: Tool.Specification): Try[List[Result]] = {
    val cmd = getCommandFor(Paths.get(source.path), configuration, files, specification, resultFilePath)

    CommandRunner.exec(cmd, Some(new File(source.path))) match {

      case Right(resultFromTool) if resultFromTool.exitCode == 0 || resultFromTool.exitCode == 2 =>
        try {
          // Save stdout to result file
          val writer = new PrintWriter(resultFilePath.toFile)
          try {
            resultFromTool.stdout.foreach(writer.println)
          } finally {
            writer.close()
          }

          parseResult(resultFilePath.toFile) match {
            case s @ Success(_) =>
              s
            case Failure(e) =>
              val msg =
                s"""
                   |[Reek] Failed to parse result file.
                   |Reek exited with code ${resultFromTool.exitCode}
                   |message: ${e.getMessage}
                   |stdout: ${resultFromTool.stdout.mkString(Properties.lineSeparator)}
                   |stderr: ${resultFromTool.stderr.mkString(Properties.lineSeparator)}
                """.stripMargin
              Failure(new Exception(msg))
          }
        } catch {
          case e: Throwable =>
            Failure(e)
        }

      case Right(resultFromTool) =>
        val msg =
          s"""
             |[Reek] Command failed with exit code ${resultFromTool.exitCode}
             |stdout: ${resultFromTool.stdout.mkString(Properties.lineSeparator)}
             |stderr: ${resultFromTool.stderr.mkString(Properties.lineSeparator)}
         """.stripMargin
        Failure(new Exception(msg))

      case Left(e) =>
        Failure(e)
    }
  }

  private[this] def parseResult(filename: File): Try[List[Result]] = {
    Try {
      val resultFromTool = Source.fromFile(filename).getLines().mkString
      Json.parse(resultFromTool)
    }.flatMap { json =>
      json.validate[ReekResult] match {
        case JsSuccess(reekResult, _) =>
          Success(reekResult.files.getOrElse(List.empty).flatMap { file =>
            reekFileToResult(file)
          })
        case JsError(err) =>
          Failure(new Throwable(Json.stringify(JsError.toJson(err))))
      }
    }
  }

  private[this] def reekFileToResult(reekFiles: ReekFiles): List[Result] = {
    reekFiles.offenses.getOrElse(List.empty).map { offense =>
      Issue(
        api.Source.File(reekFiles.path.value),
        Result.Message(offense.message.value),
        Pattern.Id(getIdByPatternName(offense.cop_name.value)),
        api.Source.Line(offense.location.line)
      )
    }
  }

  private[this] def getCommandFor(
      path: Path,
      conf: Option[List[Pattern.Definition]],
      files: Option[Set[api.Source.File]],
      spec: Specification,
      outputFilePath: Path
  ): List[String] = {
    val configFile: Option[Path] = {
      val customCodacyConfigFile = path.resolve("/src/.reek.yml")

      if (Files.exists(customCodacyConfigFile)) {
        Some(customCodacyConfigFile)
      } else {
        conf match {
          case Some(patterns) =>
            getConfigFile(patterns)  // use UI config
          case None =>
            throw new RuntimeException("No Reek configuration found: missing both .reek.yml and Codacy UI patterns.")
        }
      }
    }

    val configFileOptions = configFile match {
      case Some(file) =>
        List("-c", file.toString)
      case None =>
        List.empty
    }

    val patternsCmd = (for {
      patterns <- conf.getOrElse(List.empty)
    } yield patterns.patternId.toString) match {
      case patternIds if patternIds.nonEmpty =>
        patternIds.flatMap(p => List("--smell", p))
      case _ =>
        List.empty
    }

    val filesCmd = files
      .getOrElse(List(path.toAbsolutePath))
      .collect {
        case file if !filesToIgnore.contains(file.toString.toLowerCase) =>
          file.toString
      }

    List("reek", "--format", "json") ++ configFileOptions ++ patternsCmd ++ filesCmd
  }

  private[this] lazy val resultFilePath =
    Paths.get(Properties.tmpDir, "reek-result.json")

  private[this] def getConfigFile(conf: List[Pattern.Definition]): Option[Path] = {
    val smellConfigs = conf.map { pattern =>
      generateRule(pattern.patternId, pattern.parameters)
    }

    val detectorsSection =
      s"""detectors:
         |${smellConfigs.mkString(Properties.lineSeparator)}
         |""".stripMargin

    val excludePaths =
      s"""exclude_paths:
         |  - "vendor/**/*"
         |  - "db/schema.rb"
         |  - ".git/**/*"
         |""".stripMargin

    val finalYaml =
      s"""---
         |
         |# Generated Reek configuration
         |
         |$detectorsSection
         |
         |$excludePaths
         |""".stripMargin

    fileForConfig(finalYaml).toOption
  }

  private[this] def fileForConfig(config: String) = tmpFile(config.toString)

  private[this] def tmpFile(content: String, prefix: String = "config", suffix: String = ".yml"): Try[Path] = {
    Try {
      Files.write(
        Files.createTempFile(prefix, suffix),
        content.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE
      )
    }
  }

  private[this] def generateRule(patternId: Pattern.Id, parameters: Set[Parameter.Definition]): String = {
    val name = getPatternNameById(patternId)
    val ymlProperties = parameters.map(generateParameter)

    val header = s"  $name:\n    enabled: true"

    if (ymlProperties.nonEmpty) {
      val props = ymlProperties.mkString(s"\n    ")
      s"$header\n    $props"
    } else {
      header
    }
  }

  private[this] def getPatternNameById(patternId: Pattern.Id): String = {
    patternId.value.replace('_', '/')
  }

  private[this] def getIdByPatternName(id: String): String = {
    id.replace('/', '_')
  }

  private[this] def generateParameter(parameter: Parameter.Definition): String = {
    paramValueToJsValue(parameter.value) match {
      case JsArray(parameters) if parameters.nonEmpty =>
        val finalParameters = parameters
          .map(p => s"    - ${Json.stringify(p)}")
          .mkString(Properties.lineSeparator)
        s"""${parameter.name.value}:
           |$finalParameters
         """.stripMargin

      case JsArray(_) =>
        s"""${parameter.name.value}: []
         """.stripMargin

      case other => s"${parameter.name.value}: ${Json.stringify(other)}"
    }
  }

}
