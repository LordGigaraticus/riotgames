import java.net.URL
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

import scala.util.Try

/**
  * Created by dakotahrickert on 7/5/17.
  */
object MatchHistorySync {

  // API Key resets daily
  val APIKEY = "RGAPI-1884dc1e-eb81-4d4c-a59b-f14984f859eb"

  def main(args: Array[String]): Unit = {
    //getHistory(49076228)
    getChampion()
  }

  def getHistory(accountId: Integer): Unit = {
    val spark = SparkSession.builder().appName("MatchHistory").master("local[*]").getOrCreate()
    import spark.implicits._
    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId?api_key=$APIKEY")
    val temp: Path = Paths.get("MatchHistoryBackup.txt")
    Try(Files.copy(url.openConnection().getInputStream, temp, StandardCopyOption.REPLACE_EXISTING))
    val str = scala.io.Source.fromFile("MatchHistoryBackup.txt").getLines().mkString
    val jsondDataSet = spark.read.json(temp.toString)
    val df1 = jsondDataSet.select(explode($"matches.champion").as("champion_id")).groupBy($"champion_id").count().orderBy($"champion_id")
    df1.show(150,false) // Displays the number of times a champ has been played.
  }
  def getChampion(): Unit ={
    val spark = SparkSession.builder().appName("ChampionList").master("local[*]").getOrCreate()
    import spark.implicits._
//    val url = new URL(s"https://na1.api.riotgames.com/lol/static-data/v3/champions?locale=en_US&dataById=true&api_key=$APIKEY")
    val url = new URL(s"https://na1.api.riotgames.com/lol/static-data/v3/champions?locale=en_US&tags=keys&dataById=true&api_key=$APIKEY")
    val temp: Path = Paths.get("ChampionList.txt")
    val errorCheck = Try(Files.copy(url.openConnection().getInputStream, temp, StandardCopyOption.REPLACE_EXISTING))
    println(errorCheck)
    val str: String = scala.io.Source.fromFile("ChampionList.txt").getLines().mkString
    val jsondDataSet: DataFrame = spark.read.json(temp.toString)
//    val df1 = jsondDataSet.select($"data.1.*")
    val df1 = jsondDataSet.select($"keys.*")
    df1.show(false)
  }
}
