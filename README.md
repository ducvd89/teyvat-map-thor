# Teyvat Map for AYN Thor

An offline Genshin Impact interactive map for the AYN Thor's bottom screen. It tracks the player's live position by
reading the in-game minimap through Android's screen capture. Nothing touches the game itself.

- Design and plan: [PLAN.md](PLAN.md)
- Android app: `app/` (Kotlin, Jetpack Compose, MapCompose, OpenCV)
- PC tools: `tools/` (`fetch_pack.py` downloads the HoYoLAB map data pack)

## Map data

The map data (tiles, points, icons, overlays) comes from HoYoLAB and is **not** included in this repository. Build it
with `python3 tools/fetch_pack.py`, then zip `data/pack` and install it in the app with
**Settings → Install maps from zip**, or let the app download it on first run.

## License

[CC BY-ND 4.0](LICENSE). Genshin Impact and its map data belong to HoYoverse. This project is not affiliated with
HoYoverse.
