import java.net.URL
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.DefaultFormats

import scala.util.Try
import org.apache.log4j.{Level, Logger}
import scalikejdbc.{AutoSession, ConnectionPool, _}

import com.typesafe.config.ConfigFactory


/**
  * Created by dakotahrickert on 7/5/17.
  */
object MatchHistorySync {

  Logger.getLogger("org").setLevel(Level.ERROR)
  Logger.getLogger("akka").setLevel(Level.ERROR)
  val spark = SparkSession.builder().appName("RiotGames").master("local[*]").getOrCreate()

  import spark.implicits._

  //Configuration File Data
  val APIKEY: String = ConfigFactory.load("application.conf").getString("API_KEY") //API Key resets daily
  val DB_PATH = ConfigFactory.load("application.conf").getString("DATABASE_PATH") //The database path is the path to the RiotGames.db file.

  implicit lazy val formats = DefaultFormats

  val champInfo = getStaticChampionInfo() // Create Dataframe with Static Champion Data

  //Set up Spark Dataframe from match_history table
  val table = spark.read.format("jdbc")
    .option("url", DB_PATH)
    .option("dbtable", "match_history")
    .load()

  implicit val session = AutoSession
  ConnectionPool.singleton(DB_PATH, "", "")

  // UDF Functions
  //--------------------------------------------------------------------

  //UDF for converting champion IDs to champion names
  def udfConvertChampion = udf((championId: Integer) => {
    champInfo.data(championId).name
  })

  //UDF for KDA calculation. Returns number of either number of deaths or one if deaths is zero
  def udfMax = udf((v: Integer) => {
    math.max(1, v)
  })

  //--------------------------------------------------------------------

  //Main function for testing features
  //--------------------------------------------------------------------
  def main(args: Array[String]): Unit = {
    updateDB(getSummonerInfo("LordGigaraticus")) //Change name to add your data
    getHistory(getSummonerInfo("LordGigaraticus")) //Change name to add your data
  }

  //--------------------------------------------------------------------


  def updateDB(accountId: Integer): Unit = {
    //    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId?api_key=$APIKEY") //API Call
    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId/recent?api_key=$APIKEY")
    val filePath: Path = Paths.get("MatchHistoryBackup.txt") //Create backup file
    Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING)) //Attempt to call API, if down load from backup
    val str = scala.io.Source.fromFile("MatchHistoryBackup.txt").getLines().mkString //Load in API call
    val jsonMap: JValue = parse(str)
    val jsonExtract: MatchHistory = jsonMap.extract[MatchHistory]
    val matchExtract: List[MatchDataFields] = jsonExtract.matches.map(x => getMatchData(x.gameId))
    val matchDataExtract = matchExtract.map(a => MatchDataStatsExtract(a.gameId,
      Try(a.participants.filter(t => t.participantId == a.participantIdentities.filter(y => y.player.accountId == accountId).map(z => z.participantId).head).map(x => x.stats).head.win).toOption.getOrElse(false),
      Try(a.participants.filter(t => t.participantId == a.participantIdentities.filter(y => y.player.accountId == accountId).map(z => z.participantId).head).map(x => x.stats).head.goldEarned).toOption.getOrElse(0),
      Try(a.participants.filter(t => t.participantId == a.participantIdentities.filter(y => y.player.accountId == accountId).map(z => z.participantId).head).map(x => x.stats).head.kills).toOption.getOrElse(0),
      Try(a.participants.filter(t => t.participantId == a.participantIdentities.filter(y => y.player.accountId == accountId).map(z => z.participantId).head).map(x => x.stats).head.deaths).toOption.getOrElse(0),
      Try(a.participants.filter(t => t.participantId == a.participantIdentities.filter(y => y.player.accountId == accountId).map(z => z.participantId).head).map(x => x.stats).head.assists).toOption.getOrElse(0)
    ))

    jsonExtract.matches.foreach(x => sql"INSERT OR REPLACE INTO match_history (accountid,gameid,lane,champion,platformid,queue,role,season,`timestamp`,win,gold_earned,kills,deaths,assists,kda) VALUES (${accountId},${x.gameId}, ${x.lane},${x.champion},${x.platformId},${x.queue},${x.role},${x.season},${x.timestamp},NULL,NULL,NULL,NULL,NULL,NULL) ".execute().apply())
    matchDataExtract.foreach(x => sql"UPDATE match_history SET win = ${x.win}, gold_earned = ${x.goldEarned}, kills = ${x.kills}, deaths = ${x.deaths}, assists = ${x.assists} WHERE gameid = ${x.gameId} ".execute().apply())
  }

  // This function aggregates information about match history for a given summoner ID
  // Currently it displays the number of times a champion has been played by a summoner as well as
  // the number of times a summoner has played a specific lane. The API is unable to differentiate
  // whether someone has played ADC or SUPPORT as it lumps both positions into BOTTOM
  def getHistory(accountId: Integer): Unit = {

    //UDF for gathering specific match data
    def udfMatchData = udf((matchId: Long) => {
      val x = getMatchData(matchId)
      Try(x.participants.filter(t => t.participantId == x.participantIdentities.filter(y => y.player.accountId == accountId).map(z => z.participantId).head).map(x => x.stats).head).toOption.getOrElse(new MatchDataStats)
    })

    //val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId?api_key=$APIKEY") //API Call for full Ranked history
    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matchlists/by-account/$accountId/recent?api_key=$APIKEY") // API Call for most recent 20 Ranked games
    val filePath: Path = Paths.get("MatchHistoryBackup.txt") //Create backup file
    Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING)) //Attempt to call API, if down load from backup
    val str = scala.io.Source.fromFile("MatchHistoryBackup.txt").getLines().mkString //Load in API call
    val jsondDataSet = spark.read.json(filePath.toString) //Read JSON with spark

    //Create Dataframe with all data from Match History, alias champion IDs to champion names
    val jsonDF = jsondDataSet.select(explode($"matches")).toDF("matches")
      .select($"matches.platformId", $"matches.gameId", udfConvertChampion($"matches.champion").as("champion"), $"matches.queue", $"matches.season", $"matches.timestamp", $"matches.role", $"matches.lane")
      .withColumn("matchdata", udfMatchData($"gameId"))

    //Create Datafram with all data above plus the extra data provided via getMatchData Function
    val jsonDF2 = jsonDF
      .withColumn("win", $"matchdata.win")
      .withColumn("goldEarned", $"matchdata.goldEarned")
      .withColumn("kills", $"matchdata.kills")
      .withColumn("deaths", $"matchdata.deaths")
      .withColumn("assists", $"matchdata.assists")
      .withColumn("kda", ($"matchdata.kills" + $"matchdata.assists") / udfMax($"matchdata.deaths"))
      .drop($"matchdata")

    //Various Data Aggregations
    val df1 = jsonDF.select("champion").groupBy("champion").count().orderBy(count("champion")) //Aggregate number of times a champ has been played by summoner
    val df2 = jsonDF.select("lane").groupBy("lane").count().orderBy(count("lane")) //Aggregate number of times a lane has been played by summoner
    val df3 = jsonDF2.select("*")
    df1.show(false) //Display Champs played
    df2.show(false) //Display Lanes played
    df3.show(false) //Show full Match History with Match Data
    table.show(false) // Display DB created in update DB
  }

  // This function grabs the Base Static Champion info and returns it as an object
  def getStaticChampionInfo(): ChampionObject = {
    val url = new URL(s"https://na1.api.riotgames.com/lol/static-data/v3/champions?locale=en_US&dataById=true&api_key=$APIKEY")
    val filePath: Path = Paths.get("ChampionList.txt")
    val errorCheck = Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING))
    println(errorCheck)
    val str: String = scala.io.Source.fromFile("ChampionList.txt").getLines().mkString
    val jsonMap: JValue = parse(str)
    val jsonExtract = jsonMap.extract[ChampionObject]
    jsonExtract
  }

  // This function grabs all Static Champion info for later use.
  // This function can currently only be used to update the backup file
  def getAllStaticChampionInfo(): Unit = {
    val url = new URL(s"https://na1.api.riotgames.com/lol/static-data/v3/champions?locale=en_US&tags=all&dataById=true&api_key=$APIKEY")
    val filePath: Path = Paths.get("ChampionListAllData.txt")
    val errorCheck = Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING))
    println(errorCheck)
    val str: String = scala.io.Source.fromFile("ChampionListAllData.txt").getLines().mkString
  }

  // This function grabs all summoner information based on a given
  // username. This function currently only returns the accountID for
  // usage in the getHistory() function
  def getSummonerInfo(summonerName: String): Integer = {
    val url = new URL(s"https://na1.api.riotgames.com/lol/summoner/v3/summoners/by-name/$summonerName?api_key=$APIKEY")
    val filePath: Path = Paths.get("SummonerInfo.txt")
    val errorCheck = Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING))
    println(errorCheck)
    val str: String = scala.io.Source.fromFile("SummonerInfo.txt").getLines().mkString
    val jsonMap: JValue = parse(str)
    val jsonExtract = jsonMap.extract[SummonerObject]
    jsonExtract.accountId
  }

  //THis functions grabs all match data given a specific MatchId.
  def getMatchData(matchId: Long): MatchDataFields = {
    val url = new URL(s"https://na1.api.riotgames.com/lol/match/v3/matches/$matchId?api_key=$APIKEY")
    val filePath: Path = Paths.get("MatchData.txt")
    val errorCheck = Try(Files.copy(url.openConnection().getInputStream, filePath, StandardCopyOption.REPLACE_EXISTING))
    println(errorCheck)
    val str: String = scala.io.Source.fromFile("MatchData.txt").getLines().mkString
    val jsonMap = parse(str)
    val jsonExtract = jsonMap.extract[MatchDataFields]
    jsonExtract
  }

  case class ChampionObject(`type`: String, version: String, data: Map[Integer, ChampionFields])

  case class ChampionFields(title: String, id: Int, key: String, name: String)

  case class MatchHistory(matches: List[MatchFields], startIndex: Integer, endIndex: Integer, totalGames: Integer)

  case class MatchFields(platformId: String, gameId: Long, champion: Integer, queue: Integer, season: Integer, timestamp: Integer, role: String, lane: String)

  case class SummonerObject(profileIconId: Integer, name: String, summonerLevel: Integer, accountId: Integer, id: Integer, revisionDate: Integer)

  case class MatchDataFields(gameId: Long = 0, participantIdentities: List[MatchDataParticipantIdentities] = List(new MatchDataParticipantIdentities), participants: List[MatchDataParticipants] = List(new MatchDataParticipants))

  case class MatchDataParticipantIdentities(player: MatchDataPlayer = new MatchDataPlayer, participantId: Integer = 0)

  case class MatchDataParticipants(stats: MatchDataStats = new MatchDataStats, participantId: Integer = 0)

  case class MatchDataPlayer(summonerName: String = "NULL", accountId: Integer = 0)

  case class MatchDataStats(win: Boolean = false, goldEarned: Integer = 0, kills: Integer = 0, deaths: Integer = 0, assists: Integer = 0)

  case class MatchDataStatsExtract(gameId: Long = 0, win: Boolean = false, goldEarned: Integer = 0, kills: Integer = 0, deaths: Integer = 0, assists: Integer = 0)

}
