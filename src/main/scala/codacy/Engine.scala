package codacy

import codacy.reek.Reek
import com.codacy.tools.scala.seed.DockerEngine

object Engine extends DockerEngine(Reek)()
