# Wholphin NoBars: the map

Wholphin NoBars is the official Wholphin Android TV app (by damontecres) plus our own features for one home: the
Jellyfin library on the M2 Mac, Netflix / Prime Video / Theatre, a Watchlist and History kept on Trakt, person pages
with IMDb ratings, and a steadier player. This page says where everything is, so anyone can find a feature in minutes.

## Three parts, one direction of data

```
 M2 Mac scripts (m2-server, Python)  →  Jellyfin plugin (C#)  →  this app (Kotlin, on the TV)
 gather: TMDB, IMDb, Trakt,             serve: small JSON           show: pages, rows, posters
 MDBList, Netflix Top 10, cinemas       answers, cached              never asks the outside world
```

- **m2-server** (`fahadhossainzzz/M2-Server`): the morning run, title cards, poster facts, the History shelf, Trakt
  sync, the importer. Jobs run at 7 AM or later (trickplay 04:30–07:30, paused while anything plays).
- **Plugin** (`fahadhossainzzz/jellyfin-plugin-imdbratings-ng`, branch `main`): IMDb ratings for the library, plus the
  NoBars API under `/Plugins/ImdbRatings/…`. Restart Jellyfin only when nothing is playing.
- **This app** (`fahadhossainzzz/Wholphin`, branch `nobars`): every push builds the APK; the Mac puts it on HDD2.

## The app: where each feature lives

Everything of ours is under `nobars/`. The original app's files are changed only by small hooks (listed below).

| Folder | What it is |
|---|---|
| `nobars/data/` | The stores and models: `StreamingStore` (catalogue titles, library ids, title cards), `WantedStore` (Watchlist), `HistoryStore` (Trakt history, marking), `PosterFacts` (IMDb / box office / 🍅 for any poster), `BoxOffice`, `ImdbVotesStore`, `CriticFallback`, `SizeStore`, `PreparingStore`, `WatchProviders`, `NbQuery`, `ItemPreviewCache` |
| `nobars/ui/` | Shared pieces: `CatalogGrid` ("See all" grids, Movies-style), `TitleActions` (the fixed button block), `QuickView` (pop-up for titles you don't have), `TraktMark` (Watched just now / on release / Remove), `PosterRatingBand`, `PosterInfoLine`, `NbDetailSections`, `BoxOfficeLine`, `WatchProvidersLine`, `PreparingBanner`, `WholeItemsScroll` |
| `nobars/catalog/` | Netflix · Prime Video · Theatre pages and their side panel (`StreamingPage`), a title's own page (`CatalogTitlePage`), browse rows (`BrowseRowsPage`) |
| `nobars/watchlist/` | The Watchlist page: rows Total · On Netflix · On Prime Video · In your library · Others · Best rated, and their See all grids |
| `nobars/history/` | The History page (`HistoryPage`: Total · Films · TV shows · a row per year) and its data (`HistoryShelf`) |
| `nobars/person/` | Anyone's page (`AnyPersonPage`), the career model (`Credit`), the cast overlay in the player (`PeopleOverlay`). The library's person page itself is the original `ui/detail/PersonPage.kt` with our rows (In your library · Best rated on IMDb · All films · All TV · On your watchlist · Your history) |
| `nobars/library/` | Deleting one version of a film (`DeleteVersion`) |
| `nobars/home/` | The custom home-row editor (`CustomRowEditor`) |
| `nobars/playback/` | Player add-ons: `StallGuard`, `ElasticLoadControl`, `UsbReadAhead`, `PatientLoadErrorPolicy`, `EndTolerantExtractor`, `LeanExtractorsFactory`, `NoDtsPassthrough`, `DelayedTextRenderer`, `LanguageKeep`, `StreamHealth`, `DiagCollector`, `NoBarsLog`, `TrickplayCache`, `ZoomedContentScale`, `PlaybackFacts` |

New features get their own folder here (for example `nobars/calendar/`), with one file per job.

## The plugin's NoBars API (one file per feature)

`Api/ImdbRatingsController.cs` holds the shared parts (status, votes, running the Mac's scripts); the rest is split:

| File | Endpoints |
|---|---|
| `ImdbRatingsController.Catalog.cs` | `Streaming/{name}`, `Streaming/title/{key}`, `ServerStatus/{name}`, `Title/{key}`, `Facts?keys=`, `Trailer/{key}` |
| `ImdbRatingsController.Person.cs` | `Person/{tmdbId}`: profile, career with IMDb ids (Trakt), today's IMDb rating and votes, 🍅 / 🍿 (MDBList) |
| `ImdbRatingsController.History.cs` | `History`, `History/Shelf`, `History/Mark`, `History/Unmark` |
| `ImdbRatingsController.Library.cs` | `Origin/{itemId}`, `CheckSubtitle/{itemId}`, `DeleteVersion/{itemId}/{sourceId}` |

## The Mac scripts

| File | What it does |
|---|---|
| `daily/run.py` | The morning run (7 AM or later): catalogue, library facts, ratings, box office |
| `daily/streaming.py`, `cinema.py`, `theatre.py` | Netflix / Prime / Theatre rows (India, US, Bangladesh) |
| `daily/title_service.py`, `title_pages.py` | One title's card on demand (cached 12 h) |
| `daily/poster_facts.py` | IMDb / box office / 🍅 for any list of titles (older titles kept 90 days) |
| `daily/history_shelf.py` | The History page's data from your Trakt history (posters, plots, ratings) |
| `daily/ratings.py`, `money.py`, `journey.py`, `library_facts.py`, `library_patch.py` | 🍅 / 🍿, box office, a title's journey, library facts and quick patches after an import |
| `maintenance/trakt_sync.py` | Watchlist both ways, the Trakt history copy, marking watched |
| `tools/fetch_app.py` | Puts each new APK on HDD2 |

## Small hooks in the original app

These original files carry short NoBars changes (search for "NoBars" in them): the side menu (`ui/nav/NavDrawer.kt`,
`services/NavDrawerService.kt`), navigation (`ui/nav/Destination.kt`, `DestinationContent.kt`, `ApplicationContent.kt`),
poster cards (`ui/cards/BannerCard.kt`, `GridCard.kt`, `ItemCardImage.kt`), library grids and filters
(`ui/components/CollectionFolder*.kt`, `FilterByButton.kt`, `data/filter/ItemFilterBy.kt`, `ui/data/SortAndDirection.kt`),
detail pages (`ui/detail/movie/`, `series/`, `episode/`, `PersonPage.kt`), the home page and its settings
(`ui/main/`), the player (`ui/playback/`, `services/PlayerFactory.kt`, `StreamChoiceService.kt`) and preferences
(`preferences/AppPreference.kt`). Keeping them small is what lets us take the original author's updates.
