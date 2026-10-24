# Can PhonePi automate Google Timeline?

A third-party app cannot trigger Google's Timeline export. There is no sanctioned intent, content provider, or API. PhonePi can import a Timeline JSON that Alex exports by hand. The log bots should use is PhonePi's own on-device history, plus that import for backfill.

## What changed in 2024

Since late 2024, Google Maps Timeline is stored on the device. There is no website or public API for it, and Google Takeout no longer includes it. The official export is manual: Android Settings, Location, Location services, Timeline, Export Timeline data. That writes a JSON file. [Timeline Exporter README](https://github.com/NikkittaP/timeline-exporter) and the [Google Maps community thread](https://support.google.com/maps/thread/264641290/export-full-location-timeline-data-in-json-or-similar-format-in-the-new-version-of-timeline?hl=en) describe that path. The Play listing for the same exporter, updated 10 Oct 2026, still says there is no Takeout export and no website download. [Timeline Exporter on Google Play](https://play.google.com/store/apps/details?hl=en_US&id=io.github.nikkittap.timelineexporter)

Older answers that point at Takeout are out of date. [Stack Overflow](https://stackoverflow.com/questions/34683653/google-maps-your-timeline-api) was right that there is no Timeline API. The Takeout URL in that answer is not a current source for this data.

## Can a third-party app trigger the export?

No.

Checked, and rejected, for PhonePi:

- Public Android intents. Nothing in the platform SDK starts Settings, Location, Timeline, Export Timeline data. The export button is Google's own UI.
- Content providers. The on-device store is Play services' private database (`odlh-storage.db` under `com.google.android.gms`). A normal app cannot read another app's data directory. [timeline-export](https://github.com/arkenoi/timeline-export) documents that path and says the Settings export button is a file-picker flow, not a pipeline.
- A public Maps or Timeline API. There isn't one. [Stack Overflow](https://stackoverflow.com/questions/34683653/google-maps-your-timeline-api)
- The unofficial Geller endpoint (`geller-pa.googleapis.com`, `BatchSync`, encrypted on-device location history). That project needs a browser sign-in Google does not let you automate, plus a decryption key. It is not a sanctioned API. PhonePi does not call it. [timeline-export](https://github.com/arkenoi/timeline-export)
- Accessibility-service clicks through Settings. Out of scope. PhonePi does not do that.

## Can PhonePi import a file Alex exports by hand?

Yes. The supported way another app receives a file is the share sheet (`ACTION_SEND`) or the system file picker (`ACTION_OPEN_DOCUMENT`). The user picks the file. The app does not reach into Timeline's store. [Timeline Exporter README](https://github.com/NikkittaP/timeline-exporter)

PhonePi's **Import Timeline export** action, and a share of the JSON into PhonePi, parse:

- `semanticSegments` from the current on-device export (visits and activity segments, including `latLng` strings with a degree mark)
- `timelineObjects` from older Semantic Location History (`placeVisit` / `activitySegment`, latitudeE7)

Raw `locations` points (old Records.json) are rejected. They are not visits. Re-importing the same file uses stable ids, so rows update instead of duplicating.

## Recommendation

Use PhonePi's own opt-in location log as the source bots query. Import a Timeline JSON only when Alex wants history from before the toggle was on.

Reasons:

- The export cannot be automated on a sanctioned path, so a bot cannot answer "where was I" from Google's Timeline unless Alex has already handed over a file.
- An on-device log does not depend on Maps' private format, a Google account, or a paid geocoder. Addresses come from Android's `Geocoder`.
- The box keeps a copy, so `get_location_history` and `search_places` work when the phone is offline.
- Location history stays off until Alex turns it on, and he can pause or delete it on the phone.

## Sources

- [google maps your timeline api](https://stackoverflow.com/questions/34683653/google-maps-your-timeline-api)
- [NikkittaP/timeline-exporter](https://github.com/NikkittaP/timeline-exporter)
- [Export full location timeline data in JSON](https://support.google.com/maps/thread/264641290/export-full-location-timeline-data-in-json-or-similar-format-in-the-new-version-of-timeline?hl=en)
- [arkenoi/timeline-export](https://github.com/arkenoi/timeline-export)
- [Timeline Exporter: GPX KML](https://play.google.com/store/apps/details?hl=en_US&id=io.github.nikkittap.timelineexporter) (updated Oct 10, 2026)
