import java.io.{File, InputStreamReader}
import java.lang.Exception
import java.net.URL
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import com.google.gson._
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scala.util.Try

/**
  * Created by dakotahrickert on 7/5/17.
  */
object MatchHistorySync {
  def main(args: Array[String]): Unit = {
    println(getHistory(49076228))
  }

  def getHistory(accountId: Integer):Unit = {
    val spark = SparkSession.builder().appName("MatchHistory").master("local[*]").getOrCreate()
    import spark.implicits._
    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId?api_key=RGAPI-4849278b-1f04-41af-a137-6040a7313aaf")

    val temp = Paths.get("MatchHistoryBackup.txt")
    Try( Files.copy(url.openConnection().getInputStream, temp))
    val str = scala.io.Source.fromInputStream(url.openConnection.getInputStream).getLines().mkString("\n")
    val jsondDataSet = spark.read.json(temp.toString)
    jsondDataSet.select("matches.champion").show()
  }
}
