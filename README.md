# riotgames
Playing with the League Of Legends API
This project was created to practice using Scala and Spark to create databases and aggregate data from an API.

# Installation
After pulling the repo, you will need to open the riotgames.properties file at the root of the project and add in your Riot Developers API key. This key can be obtained by going to https://developer.riotgames.com/ and signing in with your League of Legends account. IMPORTANT: This key only lasts about a day, so you will need to regenerate the key and update the file roughly every 24 hours. 

Additionally you will also need to add the absolute path to the RiotGames.db file included in this repository. Example path:  "jdbc:sqlite:/Users/dakotahrickert/dev/DataEngineering/riotgames/RiotGames.db"

After doing all this, you will need to remove the template portion of the file name and rename it "application.conf"

# Usage
Functions are called in the Main function. There are two example calls provided.

# Known Bugs
It is very very easy to hit the rate limit, and the getHistory function will hit it every time it runs. There are commented out URLs that pull the full ranked match history for a given summoner name, and these will hit the rate limit almost every time. There are fall backs put into place to load dummy data if the rate limit is hit, so the DB will not be filled with nonsense.

For some reason timestamps are often represented by negative numbers in both the DB creation and spark calls, even though the API returns valid timestamps. 

SQLite seems to have issues with Boolean values. Sometimes it treats True as 1 and False as 0, however other times True will equal a 1 and False will underflow (0E-18)
