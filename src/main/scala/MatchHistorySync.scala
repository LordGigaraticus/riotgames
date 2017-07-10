import java.net.URL
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.DefaultFormats

import scala.util.Try

/**
  * Created by dakotahrickert on 7/5/17.
  */
object MatchHistorySync {

  // API Key resets daily
  val APIKEY = "RGAPI-6277bb3e-8084-416c-be0e-c32924ba2f3d"
  implicit lazy val formats = DefaultFormats

  def main(args: Array[String]): Unit = {
    getHistory(49076228)
  }

  def getHistory(accountId: Integer): Unit = {

    val champInfo = getStaticChampionInfo()
    def udfConvert = udf((championId: Integer) => {
      champInfo.data(championId).name
    })

    val spark = SparkSession.builder().appName("MatchHistory").master("local[*]").getOrCreate()
    import spark.implicits._
    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId?api_key=$APIKEY")
    val filePath: Path = Paths.get("MatchHistoryBackup.txt")
    Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING))
    val str = scala.io.Source.fromFile("MatchHistoryBackup.txt").getLines().mkString
    val jsondDataSet = spark.read.json(filePath.toString)
    val jsonDF = jsondDataSet.select(explode($"matches")).toDF("matches").select($"matches.platformId", $"matches.gameId", udfConvert($"matches.champion").as("champion"), $"matches.queue", $"matches.season", $"matches.timestamp", $"matches.role", $"matches.lane")
    val df1 = jsonDF.select("champion").groupBy("champion").count().orderBy(count("champion"))
    val df2 = jsonDF.select("lane").groupBy("lane").count().orderBy(count("lane"))
    df1.show(150, false)
    df2.show(5,false)
  }

  def getStaticChampionInfo(): ChampionObject = {
    val spark = SparkSession.builder().appName("ChampionList").master("local[*]").getOrCreate()
    val url = new URL(s"https://na1.api.riotgames.com/lol/static-data/v3/champions?locale=en_US&dataById=true&api_key=$APIKEY")
    val filePath: Path = Paths.get("ChampionList.txt")
    val errorCheck = Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING))
    println(errorCheck)
    val str: String = scala.io.Source.fromFile("ChampionList.txt").getLines().mkString
    val jsonMap: JValue = parse(str)
    val jsonExtract = jsonMap.extract[ChampionObject]
    jsonExtract
  }

  case class ChampionFields(title: String, id: Int, key: String, name: String)

  case class ChampionObject(`type`: String, version: String, data: Map[Integer, ChampionFields])

  case class MatchHistory(matches: List[MatchFields], startIndex: Integer, endIndex: Integer, totalGames: Integer)

  case class MatchFields(platformId: String, gameId: String, champion: Integer, queue: Integer, season: Integer, timestamp: Integer, role: String, lane: String)

}
