import java.net.URL
import java.nio.file.{Files, Path, Paths}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import scala.util.Try

/**
  * Created by dakotahrickert on 7/5/17.
  */
object MatchHistorySync {
  def main(args: Array[String]): Unit = {
    println(getHistory(49076228))
  }

  def getHistory(accountId: Integer): Unit = {
    val spark = SparkSession.builder().appName("MatchHistory").master("local[*]").getOrCreate()
    import spark.implicits._
    //API Key expires daily. PM me for new key
    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId?api_key=RGAPI-49d2a0f7-d736-4af6-b28c-bd0b4ab95c4e")
    val temp: Path = Paths.get("MatchHistoryBackup.txt")
    Try(Files.copy(url.openConnection().getInputStream, temp))
    val str = scala.io.Source.fromFile("MatchHistoryBackup.txt").getLines().mkString
    val jsondDataSet = spark.read.json(temp.toString)
    val df1 = jsondDataSet.select(explode($"matches.champion").as("champion_id")).groupBy($"champion_id").count().orderBy($"champion_id")
    df1.show(150,false) // Displays the number of times a champ has been played.
  }
}
