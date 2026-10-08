# VIT-AP Buddy

Single-page AI helper for first-year students. Java 17 + Maven, zero external dependencies.

## Run
    mvn compile exec:java
    # open http://localhost:8080

Optional AI phrasing + Telugu/Hindi support:

    export ANTHROPIC_API_KEY=your_key      # Windows: set ANTHROPIC_API_KEY=your_key
    mvn compile exec:java

## Add your data
Edit `src/main/resources/knowledge.txt` (one line per fact):
`category | title | keywords | answer | source`
The shipped entries are SAMPLES - replace them with official information.

## Admin
`GET /api/gaps` shows the questions freshers asked that the bot couldn't answer - add those to knowledge.txt.
